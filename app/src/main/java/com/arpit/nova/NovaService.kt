package com.arpit.nova

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Alive = this service runs. Killed = it doesn't (mic released, nothing scheduled).
 * Kill / wake only by hand (notification button or app). Voice can only make it quiet for a while.
 */
class NovaService : Service() {
    companion object {
        var running = false
        const val KILL = "kill"
        const val RESUME = "resume"
        const val REMIND_MIN = 5
        const val CLAUDE_KEY = "claude_key"
        // Google TTS's British male voices, best first. Swap the order to taste.
        val JARVIS_VOICES = listOf("en-gb-x-rjs-local", "en-gb-x-gbd-local", "en-gb-x-gbb-local")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private lateinit var ears: SpeechRecognizer
    private var model: Model? = null
    private var rec: Recognizer? = null
    private var wake: WakeWord? = null
    private var awake = false // in a conversation (between "wake up" and going back to sleep)
    private var listening = false
    private var next: ((String) -> Unit)? = null
    private var quietUntil = 0L
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private val announced = HashSet<String>()
    private val after = HashMap<String, () -> Unit>()
    private var utt = 0
    private var claude: Claude? = null
    private var chatGen = 0 // bumps on interrupt, so a late answer is dropped

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("nova", "NOVA", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val google = "com.google.android.tts"
        val engine = google.takeIf { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
        tts = TextToSpeech(this, { if (it == TextToSpeech.SUCCESS) jarvisVoice() }, engine)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { main.post { after.remove(id)?.invoke() } }
            @Deprecated("old api") override fun onError(id: String) = onDone(id)
        })

        ears = SpeechRecognizer.createSpeechRecognizer(this)
        ears.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle) {
                val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                listening = false
                if (text.isNullOrBlank()) return sleep()
                startWake() // so "Nova..." can cut in while it works or talks
                handle(text)
            }
            override fun onError(e: Int) { listening = false; sleep() } // silence: nothing left to do
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })

        StorageService.unpack(this, "model-en-in", "model", { m ->
            main.post {
                model = m
                rec = Recognizer(m, 16000f, """["nova", "wake up", "[unk]"]""")
                startWake()
                remindLoop()
            }
        }, { e -> Log.e("NOVA", "model unpack failed", e); stopSelf() })
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        when (i?.action) {
            KILL -> stopSelf()
            RESUME -> { quietUntil = 0; refresh(); startWake() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopWake()
        stopNetWait()
        ears.destroy()
        tts.shutdown()
        rec?.close()
        model?.close()
        super.onDestroy()
    }

    // ---- listening ----

    private fun startWake() {
        val r = rec ?: return
        if (wake != null || listening || System.currentTimeMillis() < quietUntil) return
        wake = WakeWord(r) { main.post { heard() } }.also { it.start() }
    }

    private fun stopWake() { wake?.quit(); wake = null }

    private fun heard() {
        if (listening) return
        awake = true
        // Barge-in: stop talking right away and drop whatever was queued.
        tts.stop()
        after.clear()
        next = null
        chatGen++
        stopWake()
        say("Haan boss?") { listen() }
    }

    private fun listen() {
        listening = true
        stopWake()
        ears.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    /** Back to sleep mode: only the cheap wake-word loop runs. */
    private fun sleep() {
        awake = false
        next = null
        claude = null // next wake-up starts a fresh conversation
        startWake()
    }

    // ---- speaking ----

    /** British male, offline, a bit slow and deep: as close to JARVIS as the phone's own voices get. */
    private fun jarvisVoice() {
        val offlineGb = tts.voices.orEmpty().filter { it.locale == Locale.UK && !it.isNetworkConnectionRequired }
        tts.voice = JARVIS_VOICES.firstNotNullOfOrNull { n -> offlineGb.firstOrNull { it.name == n } }
            ?: offlineGb.firstOrNull() ?: tts.defaultVoice
        tts.setPitch(0.9f)
        tts.setSpeechRate(0.95f)
    }

    private fun say(text: String, then: () -> Unit = {}) {
        val id = "u${utt++}"
        after[id] = then
        // Never say its own name, or the wake loop would hear itself and interrupt.
        val clean = text.replace(Regex("(?i)\\bnova\\b"), "")
        if (tts.speak(clean, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) after.remove(id)?.invoke()
    }

    /** Task finished: report it, ask for more, keep listening (silence or "nahi" = sleep). */
    private fun reply(text: String) = say("$text Aur kuch, boss?") { listen() }

    /** Say it and go back to sleep without asking for more. */
    private fun end(text: String) = say(text) { sleep() }

    /** Ask something and hand the spoken answer to [f] (no "hey nova" needed). */
    private fun prompt(q: String, f: (String) -> Unit) { next = f; say(q) { listen() } }

    private fun confirm(q: String, yes: () -> Unit) =
        prompt(q) { if (Brain.isYes(it)) yes() else reply("Okie boss, cancel kar diya.") }

    // ---- brain ----

    private fun handle(text: String) {
        next?.let { f -> next = null; return f(text) }
        if (Brain.isDone(text)) return end("Okie boss.")
        val cmd = Brain.parse(text, nowMin())
        when {
            !Settings.canDrawOverlays(this) -> end("Boss, pehle NOVA app mein display over other apps allow karo.")
            getSystemService(KeyguardManager::class.java).isKeyguardLocked && !cmd.safeWhenLocked() ->
                end("Boss, pehle phone unlock karo.")
            cmd.needsInternet() && !online() -> waitForInternet(cmd)
            else -> try { run(cmd) } catch (e: SecurityException) {
                end("Boss, iski permission nahi di hai. NOVA app khol ke permission do.")
            }
        }
    }

    private fun run(cmd: Cmd) {
        when (cmd) {
            is Cmd.Alarm -> if (cmd.minOfDay == null) {
                prompt("Kitne baje ka alarm, boss?") { run(Brain.parse("alarm $it", nowMin())) }
            } else {
                val h = cmd.minOfDay / 60; val m = cmd.minOfDay % 60
                doIt(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "NOVA").putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                    "All done boss. ${clock(h, m)} ka alarm laga diya.")
            }
            is Cmd.Call -> {
                val hits = contacts(cmd.name)
                when {
                    hits.isEmpty() -> reply("Boss, contacts mein ${cmd.name} nahi mila.")
                    hits.size > 1 -> reply("Boss, ${hits.size} log mile: ${hits.keys.take(3).joinToString(", ")}. Pura naam bolo.")
                    else -> {
                        val (name, num) = hits.entries.first()
                        confirm("$name ko call karun, boss?") {
                            doIt(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", num, null)), "Calling $name, boss.", more = false)
                        }
                    }
                }
            }
            is Cmd.Open -> when (cmd.app) {
                App.CALENDAR -> doIt(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()), "Calendar khul gaya, boss.")
                App.PHONE -> doIt(Intent(Intent.ACTION_DIAL), "Dialer khul gaya, boss.")
                App.NOTES -> doIt(Intent(this, MainActivity::class.java), "Notes khul gaye, boss.")
                else -> doIt(packageManager.getLaunchIntentForPackage(cmd.app.pkg!!), "${cmd.app.label} khul gaya, boss.")
            }
            is Cmd.Chat -> chat(cmd.text)
            is Cmd.WhatsApp -> reply("Sorry boss, WhatsApp abhi nahi.")
            // Android doesn't let normal apps flip wifi/data, so one tap is left for you.
            Cmd.NetOn -> doIt(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), "Panel khol diya boss, bas ek tap karo.")
            is Cmd.Navigate -> if (cmd.place.isBlank()) run(Cmd.Open(App.MAPS)) else
                doIt(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(cmd.place))), "Rasta shuru, boss. Drive safe!")
            is Cmd.StoreSearch -> if (cmd.query.isBlank()) run(Cmd.Open(App.PLAY_STORE)) else
                doIt(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(cmd.query))).setPackage("com.android.vending"),
                    "Play Store mein ${cmd.query} khol diya. Install aap khud dabao, boss.")
            is Cmd.Schedule -> {
                val day = Calendar.getInstance().apply { if (cmd.tomorrow) { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) } }
                val end = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }
                val ev = events(day.timeInMillis, end.timeInMillis)
                val which = if (cmd.tomorrow) "kal" else "aaj"
                reply(if (ev.isEmpty()) "Boss, $which koi meeting nahi hai. Chill!"
                      else "Boss, $which ${ev.size} meeting: " + ev.joinToString(". ") { "${clock(it.begin)}, ${it.title}" })
            }
            is Cmd.AddEvent -> {
                val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, cmd.title)
                cmd.minOfDay?.let {
                    val c = Calendar.getInstance().apply {
                        if (cmd.tomorrow) add(Calendar.DAY_OF_YEAR, 1)
                        set(Calendar.HOUR_OF_DAY, it / 60); set(Calendar.MINUTE, it % 60); set(Calendar.SECOND, 0)
                    }
                    i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.timeInMillis)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.timeInMillis + 3_600_000)
                }
                doIt(i, "Calendar khol diya, boss. Bas save daba do.")
            }
            is Cmd.Note -> if (cmd.text.isBlank()) prompt("Kya likhun, boss?") { saveNote(it) } else saveNote(cmd.text)
            Cmd.ReadNotes -> {
                val notes = notesFile().takeIf { it.exists() }?.readLines()?.takeLast(5).orEmpty()
                reply(if (notes.isEmpty()) "Boss, koi note nahi hai." else "Boss, aapke notes: " + notes.joinToString(". ") { it.substringAfter("| ") })
            }
            is Cmd.Quiet -> {
                quietUntil = System.currentTimeMillis() + cmd.minutes * 60_000L
                stopWake()
                refresh()
                main.postDelayed({ if (System.currentTimeMillis() >= quietUntil) { quietUntil = 0; refresh(); startWake() } }, cmd.minutes * 60_000L)
                end("Okie boss, ${clock(quietUntil)} tak chup.")
            }
            Cmd.Unknown -> reply("Sorry boss, ye abhi mere bas ka nahi.")
        }
    }

    /** Ask Claude; the answer is read out and NOVA keeps listening for a cross-question. */
    private fun chat(q: String) {
        val key = getSharedPreferences("nova", MODE_PRIVATE).getString(CLAUDE_KEY, null)
        if (key.isNullOrBlank()) return end("Boss, pehle NOVA app mein Claude API key daalo.")
        val c = claude ?: Claude(key).also { claude = it }
        val gen = ++chatGen
        say("Ek second, boss.")
        Thread {
            val answer = c.ask(q)
            main.post { if (gen == chatGen && awake) reply(answer) }
        }.start()
    }

    private fun doIt(i: Intent?, doneMsg: String, more: Boolean = true) {
        say("Okie boss.")
        val msg = if (i != null && launch(i)) doneMsg else "Boss, iske liye app phone mein nahi mila."
        if (more) reply(msg) else end(msg)
    }

    private fun launch(i: Intent) = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) { false }

    // ---- internet: NOVA can't turn it on itself, so it opens the panel and waits for you ----

    private fun online() = getSystemService(ConnectivityManager::class.java).run {
        getNetworkCapabilities(activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun waitForInternet(cmd: Cmd) {
        launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        end("Boss, net on kar do.")
        stopNetWait()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { main.post { if (netCb === this) { stopNetWait(); awake = true; run(cmd) } } }
        }
        netCb = cb
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
        main.postDelayed({ if (netCb === cb) stopNetWait() }, 120_000)
    }

    private fun stopNetWait() {
        netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        netCb = null
    }

    // ---- data on the phone ----

    private fun contacts(name: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), Phone.DISPLAY_NAME)?.use { c ->
            while (c.moveToNext()) out.putIfAbsent(c.getString(0), c.getString(1))
        }
        return out.filterKeys { it.equals(name, ignoreCase = true) }.ifEmpty { out }
    }

    private data class Ev(val title: String, val begin: Long, val key: String)

    private fun events(from: Long, to: Long): List<Ev> {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val out = ArrayList<Ev>()
        contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_ID),
            "${CalendarContract.Instances.ALL_DAY}=0", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) out += Ev(c.getString(0) ?: "Meeting", c.getLong(1), "${c.getLong(2)}-${c.getLong(1)}")
        }
        return out
    }

    // ponytail: polls every minute while the mic loop keeps the CPU awake; switch to AlarmManager if reminders arrive late.
    private fun remindLoop() {
        if (!awake && wake != null) {
            val now = System.currentTimeMillis()
            for (e in events(now, now + REMIND_MIN * 60_000L)) {
                if (e.begin < now || !announced.add(e.key)) continue
                val mins = (e.begin - now + 59_999) / 60_000
                say("Boss, $mins minute mein meeting hai: ${e.title}, ${clock(e.begin)}.")
            }
        }
        main.postDelayed(::remindLoop, 60_000)
    }

    private fun notesFile() = File(filesDir, "notes.txt")

    private fun saveNote(text: String) {
        notesFile().appendText(SimpleDateFormat("dd MMM, h:mm a", Locale.US).format(Date()) + " | " + text + "\n")
        reply("Likh liya, boss.")
    }

    // ---- small stuff ----

    private fun nowMin() = Calendar.getInstance().run { get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE) }
    private fun clock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))
    private fun clock(h: Int, m: Int) = String.format(Locale.US, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")

    private fun refresh() = getSystemService(NotificationManager::class.java).notify(1, notification())

    private fun notification(): Notification {
        val quiet = System.currentTimeMillis() < quietUntil
        fun act(label: String, action: String) = Notification.Action.Builder(null as Icon?, label,
            PendingIntent.getService(this, action.hashCode(), Intent(this, NovaService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)).build()
        return Notification.Builder(this, "nova")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (quiet) "NOVA quiet till ${clock(quietUntil)}" else "NOVA is listening for \"Hey Nova\"")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .apply { if (quiet) addAction(act("Wake now", RESUME)) }
            .addAction(act("Kill NOVA", KILL))
            .build()
    }
}
