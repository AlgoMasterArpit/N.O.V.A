package com.arpit.nova

// Only these apps exist for NOVA. Anything else is "mere bas ka nahi".
enum class App(val label: String, val pkg: String?, val online: Boolean, vararg val names: String) {
    GOOGLE("Google", "com.google.android.googlequicksearchbox", true, "google"),
    GEMINI("Gemini", "com.google.android.apps.bard", true, "gemini", "jemini"),
    CLAUDE("Claude", "com.anthropic.claude", true, "claude", "cloud", "clawd"),
    CHATGPT("ChatGPT", "com.openai.chatgpt", true, "chatgpt", "chat gpt", "chat gbt"),
    CALENDAR("Calendar", null, false, "calendar"),
    PLAY_STORE("Play Store", "com.android.vending", true, "play store", "playstore"),
    MAPS("Maps", "com.google.android.apps.maps", true, "maps", "map"),
    PHONE("Phone", null, false, "dialer", "phone app"),
    NOTES("Notes", null, false, "notepad", "notes app"),
}

sealed interface Cmd {
    data class Alarm(val minOfDay: Int?) : Cmd
    data class Call(val name: String) : Cmd
    data class Open(val app: App) : Cmd
    data class Ask(val app: App, val query: String) : Cmd
    data class Navigate(val place: String) : Cmd
    data class StoreSearch(val query: String) : Cmd
    data class Schedule(val tomorrow: Boolean) : Cmd
    data class AddEvent(val title: String, val minOfDay: Int?, val tomorrow: Boolean) : Cmd
    data class Note(val text: String) : Cmd
    data object ReadNotes : Cmd
    data class Quiet(val minutes: Int) : Cmd
    data object Unknown : Cmd
}

fun Cmd.needsInternet() = when (this) {
    is Cmd.Open -> app.online
    is Cmd.Ask, is Cmd.Navigate, is Cmd.StoreSearch -> true
    else -> false
}

// Someone else talking to your locked phone can only set alarms.
fun Cmd.safeWhenLocked() = this is Cmd.Alarm

// ponytail: keyword rules, not an LLM. Keeps everything offline; add phrases here when NOVA mishears you.
object Brain {
    private val NUM = mapOf(
        "ek" to 1, "one" to 1, "do" to 2, "two" to 2, "teen" to 3, "three" to 3, "char" to 4, "chaar" to 4, "four" to 4,
        "paanch" to 5, "panch" to 5, "five" to 5, "chhe" to 6, "che" to 6, "six" to 6, "saat" to 7, "seven" to 7,
        "aath" to 8, "eight" to 8, "nau" to 9, "nine" to 9, "das" to 10, "ten" to 10, "gyarah" to 11, "eleven" to 11,
        "barah" to 12, "twelve" to 12,
    )
    private val numAlt = NUM.keys.joinToString("|")
    // Only turn "do"/"saat" into digits next to a time word, so "call kar do" stays intact.
    private val numBeforeUnit = Regex("\\b($numAlt)\\b(?=\\s+(baje|bje|ghante|ghanta|hours?|hrs?|minutes?|mins?|am|pm)\\b)")
    private val numAfterWord = Regex("\\b(subah|savere|shaam|sham|raat|dopahar|saade|sade|sawa|paune|at)\\s+($numAlt)\\b")

    private val AM = setOf("am", "subah", "savere", "sawere", "morning")
    private val PM = setOf("pm", "shaam", "sham", "evening", "raat", "night", "dopahar", "afternoon")
    private val YES = setOf("haan", "han", "haa", "ha", "haanji", "ji", "yes", "yeah", "yep", "ok", "okay", "kar", "karo", "kardo", "sure", "bilkul", "chalo")
    private val NO = setOf("nahi", "nahin", "na", "no", "nope", "mat", "cancel", "ruko", "rehne")

    private val FILL = setOf(
        "hey", "nova", "please", "plz", "zara", "jara", "karo", "kar", "kardo", "do", "de", "dena", "to", "the", "a", "an",
        "ko", "ka", "ki", "ke", "se", "pe", "par", "on", "in", "me", "mein", "mujhe", "my", "for", "open", "kholo", "khol",
        "chalao", "launch", "start", "app", "search", "dhoondo", "dhundo", "karna", "batao", "bata", "pucho", "poocho",
        "puchho", "ask", "install", "download", "about",
    )
    private val NAV_FILL = FILL + setOf("navigate", "rasta", "directions", "route", "le", "chalo", "take", "tak", "jana", "hai",
        "chalna", "pahunchna", "kaise", "how", "reach", "go", "get", "dikhao", "show", "maps", "map", "google")
    private val NOTE_FILL = setOf("hey", "nova", "please", "note", "karo", "kar", "kardo", "karlo", "lo", "le", "likho", "likh",
        "likhlo", "take", "a", "write", "down", "yaad", "rakho", "rakh", "lena", "ki", "that", "ek", "1")
    private val CALL_FILL = setOf("hey", "nova", "call", "karo", "kar", "do", "kardo", "ko", "please", "phone", "lagao", "laga",
        "lagado", "dial", "to", "make", "a", "ek", "1", "zara", "jara", "abhi", "now", "plz")
    private val EVENT_FILL = setOf("hey", "nova", "add", "bana", "banao", "banado", "daal", "daalo", "dalo", "create", "fix",
        "set", "rakh", "rakho", "laga", "lagao", "karo", "kar", "do", "kardo", "to", "my", "calendar", "mein", "me", "in",
        "a", "an", "kal", "aaj", "tomorrow", "today", "baje", "bje", "at", "ko", "ki", "ka", "please", "schedule", "event")

    fun normalize(raw: String): String {
        var t = raw.lowercase()
            .replace(Regex("\\b([ap])\\.\\s?m\\.?"), "$1m")
            .replace(Regex("(\\d)\\.(\\d)"), "$1:$2")
            .replace(Regex("[^a-z0-9: ]"), " ")
        t = numBeforeUnit.replace(t) { NUM.getValue(it.groupValues[1]).toString() }
        t = numAfterWord.replace(t) { it.groupValues[1] + " " + NUM.getValue(it.groupValues[2]) }
        return t.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .dropWhile { it == "hey" || it == "hi" || it == "nova" }.joinToString(" ")
    }

    fun isYes(raw: String): Boolean {
        val w = normalize(raw).split(" ")
        return w.none { it in NO } && w.any { it in YES }
    }

    fun parse(raw: String, nowMin: Int): Cmd {
        val t = normalize(raw)
        val w = t.split(" ").toSet()
        fun has(vararg k: String) = k.any { if (' ' in it) " $t ".contains(" $it ") else it in w }
        fun trim(s: String, fill: Set<String>) =
            s.split(" ").dropWhile { it in fill }.dropLastWhile { it in fill }.joinToString(" ")
        val app = App.entries.firstOrNull { a -> a.names.any { has(it) } }

        val askAi = app != null && app.online && app != App.MAPS && app != App.PLAY_STORE &&
            has("ask", "pucho", "poocho", "puchho", "search", "dhoondo", "dhundo", "batao")

        return when {
            has("shaant", "shant", "chup", "quiet", "silent", "disturb", "so jao") -> Cmd.Quiet(duration(t))
            askAi -> Cmd.Ask(app!!, trim(app.names.fold(" $t ") { s, n -> s.replace(" $n ", " ") }.trim(), FILL))
            has("notes", "note") && has("padho", "padh", "read", "sunao", "suna", "batao", "show", "dikhao") -> Cmd.ReadNotes
            has("note", "likho", "likh", "likhlo", "yaad rakhna", "yaad rakho", "write down") -> Cmd.Note(trim(t, NOTE_FILL))
            has("alarm", "jagana", "jaga", "uthana", "utha dena", "wake me") ->
                Cmd.Alarm(time(t)?.let { (h, m, pm) -> resolve(h, m, pm, nowMin) })
            has("call", "dial", "phone karo", "phone lagao", "phone laga", "phone lagado") ->
                t.split(" ").filterNot { it in CALL_FILL }.joinToString(" ")
                    .let { if (it.isBlank()) Cmd.Open(App.PHONE) else Cmd.Call(it) }
            has("install", "download") -> Cmd.StoreSearch(trim(t.replace("play store", " ").replace("playstore", " "), FILL))
            has("navigate", "rasta", "directions", "route", "le chalo", "take me") -> Cmd.Navigate(trim(t, NAV_FILL))
            has("meeting", "meet", "event", "calendar") && has("add", "bana", "banao", "banado", "daal", "daalo", "dalo",
                "create", "fix", "set", "rakh", "rakho", "laga", "lagao") -> {
                val tm = time(t)
                val title = t.split(" ").filterNot { it in EVENT_FILL || it.any(Char::isDigit) || it in AM || it in PM }
                    .joinToString(" ").ifBlank { "Meeting" }
                // Meetings at 1-7 without am/pm are almost always evening.
                Cmd.AddEvent(title, tm?.let { (h, m, pm) -> resolve(h, m, pm ?: (h in 1..7), nowMin) }, has("kal", "tomorrow"))
            }
            has("meeting", "meetings", "meet", "schedule", "plan", "events", "calendar") &&
                !has("open", "kholo", "khol", "chalao") -> Cmd.Schedule(has("kal", "tomorrow"))
            app != null -> {
                val q = trim(app.names.fold(" $t ") { s, n -> s.replace(" $n ", " ") }.trim(), FILL)
                when {
                    q.isBlank() -> Cmd.Open(app)
                    app == App.PLAY_STORE -> Cmd.StoreSearch(q)
                    app == App.MAPS -> Cmd.Navigate(trim(q, NAV_FILL))
                    app.online -> Cmd.Ask(app, q)
                    else -> Cmd.Open(app)
                }
            }
            has("search", "dhoondo", "dhundo") -> Cmd.Ask(App.GOOGLE, trim(t, FILL))
            else -> Cmd.Unknown
        }
    }

    /** (hour, minute, isPm or null if not said) */
    fun time(t: String): Triple<Int, Int, Boolean?>? {
        val m = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\b").find(t) ?: return null
        var h = m.groupValues[1].toInt()
        var min = m.groupValues[2].toIntOrNull() ?: 0
        if (h > 23 || min > 59) return null
        val w = t.split(" ")
        when {
            "saade" in w || "sade" in w -> min = 30
            "sawa" in w -> min = 15
            "paune" in w -> { h = (h + 23) % 24; min = 45 }
        }
        return Triple(h, min, if (w.any { it in PM }) true else if (w.any { it in AM }) false else null)
    }

    /** Minutes of day. With no am/pm, picks whichever of h / h+12 comes next. */
    fun resolve(h: Int, min: Int, pm: Boolean?, nowMin: Int): Int {
        if (h > 12) return h * 60 + min
        val am = (h % 12) * 60 + min
        return when (pm) {
            true -> am + 720
            false -> am
            null -> listOf(am, am + 720).minBy { (it - nowMin - 1 + 1440) % 1440 }
        }
    }

    fun duration(t: String): Int {
        Regex("(\\d+)\\s*(ghante|ghanta|hours?|hrs?)").find(t)?.let { return it.groupValues[1].toInt() * 60 }
        Regex("(\\d+)\\s*(minutes?|mins?|minat)").find(t)?.let { return it.groupValues[1].toInt().coerceAtLeast(1) }
        return if ("aadha" in t || "half" in t) 30 else 60
    }
}
