package com.umassisted.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * UMAssisted 1.0 Alpha AccessibilityService
 *
 * Scope: Aoharu Hai (Unity Cup) career loop only.
 * Hard requirements observed:
 * - REQ-A5: Never self-loop. Every action requires fresh explicit user command.
 * - REQ-A6: Never faster than best-case human.
 * - REQ-S1: No INTERNET.
 * - REQ-SF1/3: Only act on com.cygames.umamusume when it is foreground.
 * - Kill switches (overlay toggles) always take effect immediately.
 */
class UMAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "UMAssisted"
        const val TARGET_PACKAGE = "com.cygames.umamusume"

        // Simple in-memory state (alpha)
        @Volatile var sweepEnabled = false
        @Volatile var voiceEnabled = false
        @Volatile var isInUma = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
        startForegroundNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != TARGET_PACKAGE) {
            isInUma = false
            return
        }
        isInUma = true
        lastForegroundPackage = event.packageName?.toString()

        // TODO (alpha): screen understanding + corpus match will go here.
        // For now we only react to explicit user commands via overlay or voice.
    }

    override fun onInterrupt() {
        // Required override. Stop any pending actions.
        handler.removeCallbacksAndMessages(null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Service unbinding")
        return super.onUnbind(intent)
    }

    // ============================================================
    // Public API for the overlay / voice layer (called from MainActivity or future voice)
    // ============================================================

    fun setSweepEnabled(enabled: Boolean) {
        sweepEnabled = enabled
        Log.i(TAG, "Sweep ${if (enabled) "ENABLED" else "DISABLED"}")
        if (enabled && isInUma) {
            // Do NOT auto-start a loop. Per REQ-A5 a fresh command is required.
            // The sweep is a *mode* that the next explicit "start sweep" command will use.
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        voiceEnabled = enabled
        Log.i(TAG, "Voice listening ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    /**
     * Execute one training auto-sweep pass (REQ-A9 / REQ-F5 alpha-critical).
     * Hovers each of the five facilities (Speed, Stamina, Power, Guts, Wit)
     * with a human-comprehension dwell. Never taps/commits during the sweep.
     *
     * This must only be called from an explicit user action (button or voice).
     */
    fun performTrainingSweepOnce() {
        if (!isInUma || !sweepEnabled) {
            Log.w(TAG, "Sweep blocked: not in Uma or disabled")
            return
        }

        // The real implementation will use takeScreenshot() + ML Kit + corpus match
        // to locate the facility row bounds.
        //
        // For the absolute minimal alpha skeleton we define the high-level contract:
        // 1. Locate the five facility tiles (from bottom bar or current training view).
        // 2. For each, move pointer over the tile, hold ~1.5s (configurable), release.
        // 3. Do not dispatch any tap that would start training.
        // 4. Stop immediately if sweep toggle is turned off mid-pass.

        Log.i(TAG, "Training sweep requested (stub)")

        // Placeholder: in real code we would compute the 5 rects dynamically
        // from the screenshot + OCR results against the labeled corpus.
        // For now we just demonstrate the safety pattern.
        handler.post {
            if (!sweepEnabled || !isInUma) return@post
            Log.i(TAG, "Sweep pass would hover Speed → Stamina → Power → Guts → Wit (dwell 1500ms each)")
            // TODO: implement real hover gestures using dispatchGesture
        }
    }

    /**
     * Perform a single no-choice advance (REQ-F2 / REQ-F4).
     * Only called when the current matched screen is human-labeled "no-choice".
     */
    fun performNoChoiceAdvance() {
        if (!isInUma) return
        Log.i(TAG, "No-choice advance (stub)")
        // In real code:
        // - Find the "Next", "Close", "OK", or result dismissal affordance via corpus match
        // - Dispatch a single human-timed tap
        // - Stop. Do not chain automatically.
    }

    /**
     * Clean exit from career (Save & Exit or Give Up).
     * Only allowed when user explicitly commands it.
     */
    fun performCareerExit() {
        if (!isInUma) return
        Log.i(TAG, "Career exit requested (stub)")
        // Locate the menu → Give Up / Save & Exit flow and execute the user's last recorded choice
        // (or fall through to manual if none recorded).
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun startForegroundNotification() {
        val channel = NotificationChannelCompat.Builder(
            "umassisted_service",
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName("UMAssisted Service")
            .build()

        NotificationManagerCompat.from(this).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, "umassisted_service")
            .setContentTitle("UMAssisted Alpha")
            .setContentText("Active for Aoharu Hai / Unity Cup")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    // Gesture helper (will be expanded)
    private fun hover(rect: Rect, dwellMs: Long) {
        val path = Path().apply {
            moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dwellMs))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
