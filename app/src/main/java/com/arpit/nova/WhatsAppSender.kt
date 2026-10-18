package com.arpit.nova

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Taps WhatsApp's Send button, only within a few seconds of you saying "haan" to a message. */
class WhatsAppSender : AccessibilityService() {
    companion object {
        private const val WINDOW_MS = 15_000L
        @Volatile private var armedUntil = 0L
        @Volatile private var onSent: (() -> Unit)? = null

        fun arm(sent: () -> Unit) {
            onSent = sent
            armedUntil = System.currentTimeMillis() + WINDOW_MS
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val send = rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull() ?: return
        if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            armedUntil = 0
            onSent?.invoke()
            onSent = null
        }
    }

    override fun onInterrupt() {}
}
