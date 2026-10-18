package com.arpit.nova

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** The hand controls: permissions, WAKE and KILL. */
class MainActivity : Activity() {
    private val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    private lateinit var status: TextView
    private lateinit var notes: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        fun btn(label: String, f: () -> Unit) = root.addView(Button(this).apply { text = label; setOnClickListener { f() } })

        status = TextView(this).apply { textSize = 17f }
        root.addView(status)
        btn("1. Give permissions") { requestPermissions(perms, 1) }
        btn("2. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        // ponytail: key sits in app-private storage (sandboxed, never backed up); Keystore encryption if that's not enough.
        val prefs = getSharedPreferences("nova", MODE_PRIVATE)
        val key = EditText(this).apply {
            hint = "Paste Claude API key (sk-ant-...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(key)
        btn("3. Save Claude key") {
            prefs.edit().putString(NovaService.CLAUDE_KEY, key.text.toString().trim()).apply()
            key.text.clear()
            refresh()
        }
        btn("4. Turn on WhatsApp sender") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("WAKE NOVA") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                startForegroundService(Intent(this, NovaService::class.java))
            status.postDelayed(::refresh, 300)
        }
        btn("KILL NOVA") {
            stopService(Intent(this, NovaService::class.java))
            status.postDelayed(::refresh, 300)
        }
        notes = TextView(this).apply { setPadding(0, 32, 0, 0) }
        root.addView(notes)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) = refresh()

    private fun refresh() {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        status.text = buildString {
            appendLine(if (NovaService.running) "🟢 NOVA is alive" else "⚫ NOVA is killed")
            appendLine(if (missing.isEmpty()) "✅ Permissions OK" else "❌ Missing: " + missing.joinToString { it.substringAfterLast('.') })
            appendLine(if (Settings.canDrawOverlays(this@MainActivity)) "✅ Can open apps" else "❌ Allow display over other apps")
            val hasKey = !getSharedPreferences("nova", MODE_PRIVATE).getString(NovaService.CLAUDE_KEY, null).isNullOrBlank()
            appendLine(if (hasKey) "✅ Claude key saved" else "❌ Add a Claude key to ask questions")
            val sender = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            appendLine(if ("$packageName/" in sender) "✅ WhatsApp sender on" else "❌ WhatsApp sender off")
            appendLine("🔒 Only your questions go online, and only to Claude.")
        }
        notes.text = "Notes:\n" + File(filesDir, "notes.txt").takeIf { it.exists() }?.readText().orEmpty()
    }
}
