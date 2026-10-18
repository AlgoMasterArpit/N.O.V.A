package com.arpit.nova

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
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
            appendLine("🔒 NOVA has no internet permission. Nothing leaves this phone through NOVA.")
        }
        notes.text = "Notes:\n" + File(filesDir, "notes.txt").takeIf { it.exists() }?.readText().orEmpty()
    }
}
