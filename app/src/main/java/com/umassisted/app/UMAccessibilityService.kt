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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

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

        // === Alpha tuning constants (easy to adjust during dev) ===
        const val SWEEP_DWELL_MS = 1350L          // human-comprehension hover time per facility
        const val SWEEP_INTER_HOVER_PAUSE_MS = 220L
        const val LIST_SCROLL_DURATION_MS = 520L  // human-scale vertical drag for race lists etc. (REQ-A16)

        // Simple in-memory state (alpha)
        @Volatile var sweepEnabled = false
        @Volatile var voiceEnabled = false
        @Volatile var isInUma = false

        // When true, after a successful captureAndAnalyzeScreen that looks like "no choice",
        // the next explicit "smart advance" or auto-trigger can perform the advance.
        // Still requires an explicit user-initiated capture or command.
        @Volatile var autoAdvanceOnNoChoice = false

        // Weak holder so the Activity can request actions on the live service instance.
        @Volatile var instance: UMAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
        instance = this
        startForegroundNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg != TARGET_PACKAGE) {
            if (isInUma) {
                // Leaving the target app — clear transient capture state (hygiene).
                isInUma = false
                lastOcrText = ""
                lastWasNoChoice = false
                lastMatchReason = ""
            }
            return
        }
        isInUma = true
        lastForegroundPackage = pkg

        // Alpha: we do not auto-react on every event.
        // Real work is driven by explicit user commands (sweep, voice, or manual capture).
        // We can add lightweight heuristics later (e.g. detect training view changes).
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
    // Screenshot + OCR (REQ-M3 / REQ-M4) + basic no-choice detection (REQ-F4)
    // ============================================================

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ocrExecutor = Executors.newSingleThreadExecutor()

    @Volatile var lastOcrText: String = ""
    @Volatile var lastWasNoChoice: Boolean = false
    @Volatile var lastMatchReason: String = ""

    // Very crude alpha "decision replay" store (REQ-A4 skeleton).
    // In a real alpha we would persist this. For now it's in-memory + optional SharedPrefs.
    private val decisionHistory = mutableMapOf<String, String>() // rough signature -> chosen text

    /**
     * Produce a stable(ish) short signature for a screen's OCR for decision replay.
     * Alpha heuristic: normalize case, collapse whitespace, take a bounded prefix.
     * This is intentionally simple; later we can incorporate scenario + turn hints.
     */
    fun signatureFor(ocrText: String): String {
        if (ocrText.isBlank()) return ""
        val norm = ocrText.lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(220)
        return norm
    }

    fun recordDecision(signature: String, chosenText: String) {
        if (signature.isBlank() || chosenText.isBlank()) return
        val key = signatureFor(signature).take(160)
        decisionHistory[key] = chosenText
        Log.i(TAG, "Recorded decision: sig=${key.take(50)} -> $chosenText")
        // Best-effort persist for alpha testing across restarts
        try {
            val prefs = getSharedPreferences("umassisted_decisions", MODE_PRIVATE)
            prefs.edit().putString(key, chosenText).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist decision", t)
        }
    }

    fun getLastDecision(signature: String): String? {
        val key = signatureFor(signature).take(160)
        return decisionHistory[key] ?: run {
            val prefs = getSharedPreferences("umassisted_decisions", MODE_PRIVATE)
            prefs.getString(key, null)
        }
    }

    /**
     * If we have a previously recorded choice for a similar screen, try to tap it.
     * This is a very rough alpha version of REQ-A4.
     *
     * Enhancement: if the stored decision is generic ("ADVANCE"), we fall back to
     * the last CorpusMatcher reason token (e.g. "next", "close") when available.
     * This makes recorded "user advanced" entries more effective.
     */
    fun tryReplayLastDecision(ocrText: String): Boolean {
        val sig = ocrText
        var previous = getLastDecision(sig)

        if (previous.isNullOrBlank()) return false

        // If we stored a generic marker, try to promote it using the last match reason.
        if (previous.equals("ADVANCE", ignoreCase = true) || previous.length > 80) {
            val token = extractActionToken(lastMatchReason)
            if (token != null) {
                Log.i(TAG, "Promoting generic recorded decision using match token: $token")
                previous = token
            }
        }

        Log.i(TAG, "Attempting to replay previous choice: $previous")

        val root = rootInActiveWindow ?: return false
        val targetNodes = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo) {
            val txt = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            if (txt.contains(previous!!, ignoreCase = true)) {
                if (node.isClickable || node.isFocusable) targetNodes.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)

        val node = targetNodes.firstOrNull() ?: return false
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 160)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        Log.i(TAG, "Replay tap dispatched=$ok for previous choice")
        return ok
    }

    private fun extractActionToken(reason: String): String? {
        if (reason.isBlank()) return null
        val lower = reason.lowercase()
        val candidates = listOf(
            "close", "next", "ok", "confirm", "race", "enter", "continue",
            "skip", "results", "done", "finish", "replay", "watch",
            "give up", "save & exit"
        )
        for (c in candidates) {
            if (lower.contains(c)) return c
        }
        return null
    }

    /**
     * Capture the current screen and run ML Kit OCR.
     * After OCR we also run the lightweight no-choice heuristic.
     *
     * Must only be called from explicit user action.
     */
    fun captureAndAnalyzeScreen(onResult: ((recognizedText: String, isNoChoice: Boolean) -> Unit)? = null) {
        if (!isInUma) {
            Log.w(TAG, "captureAndAnalyzeScreen: not in target package")
            lastOcrText = ""
            lastWasNoChoice = false
            lastMatchReason = ""
            onResult?.invoke("", false)
            return
        }

        Log.i(TAG, "Taking screenshot for OCR...")

        takeScreenshot(0, ocrExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bitmap: android.graphics.Bitmap = try {
                    result.javaClass.getMethod("getBitmap").invoke(result) as android.graphics.Bitmap
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not extract bitmap from ScreenshotResult via reflection", t)
                    android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888)
                }
                val image = InputImage.fromBitmap(bitmap, 0)

                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val fullText = visionText.text
                        lastOcrText = fullText

                        val match = CorpusMatcher.match(fullText)
                        lastWasNoChoice = match.isNoChoice
                        lastMatchReason = match.reason

                        Log.i(TAG, "=== OCR RESULT (noChoice=${lastWasNoChoice}, reason=${match.reason}) ===")
                        Log.i(TAG, fullText.take(1800))
                        Log.i(TAG, "=== END OCR ===")

                        CorpusMatcher.logMatch(fullText)
                        onResult?.invoke(fullText, lastWasNoChoice)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "OCR failed", e)
                        lastOcrText = ""
                        lastWasNoChoice = false
                        onResult?.invoke("", false)
                    }
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "takeScreenshot failed, errorCode=$errorCode")
                lastOcrText = ""
                lastWasNoChoice = false
                lastMatchReason = ""
                onResult?.invoke("", false)
            }
        })
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
     * Hovers each of the five facilities with a human-comprehension dwell.
     * Never taps or confirms training during the sweep.
     *
     * This version first optionally captures the screen (for logging/OCR),
     * then performs the hover sequence.
     *
     * Must be called from an explicit user action only.
     */
    fun performTrainingSweepOnce(captureFirst: Boolean = true) {
        if (!isInUma || !sweepEnabled) {
            Log.w(TAG, "Sweep blocked: not in Uma or disabled")
            return
        }

        Log.i(TAG, "Training sweep starting (explicit user command)")

        val doSweep: () -> Unit = {
            // Pragmatic alpha geometry for 1080x2400 (common in the corpus).
            // Real implementation will compute rects from vision + labeled corpus.
            val screenW = 1080
            val screenH = 2400

            val y = (screenH * 0.82f).toInt()
            val positions = listOf(
                (screenW * 0.12f).toInt() to y,   // Speed
                (screenW * 0.30f).toInt() to y,   // Stamina
                (screenW * 0.50f).toInt() to y,   // Power
                (screenW * 0.68f).toInt() to y,   // Guts
                (screenW * 0.86f).toInt() to y    // Wit
            )

            handler.post {
                if (!sweepEnabled || !isInUma) {
                    Log.i(TAG, "Sweep aborted before start (kill switch or left game)")
                    return@post
                }

                var index = 0
                fun hoverNext() {
                    if (index >= positions.size) {
                        Log.i(TAG, "Training sweep pass complete (5 hovers)")
                        // After the hover pass, if sweep is still armed, do one list scroll
                        // (REQ-A16: list auto-scroll behavior when sweep toggle is on).
                        // Still driven by the initial explicit command; no self-loop.
                        if (sweepEnabled && isInUma) {
                            handler.postDelayed({
                                if (sweepEnabled && isInUma) {
                                    performListScrollOnce(directionDown = true)
                                }
                            }, 220)

                            // Optional post-sweep capture for logging / replay seeding
                            handler.postDelayed({
                                if (sweepEnabled && isInUma) {
                                    captureAndAnalyzeScreen { _, _ -> /* just log */ }
                                }
                            }, 900)
                        }
                        return
                    }
                    if (!sweepEnabled || !isInUma) {
                        Log.i(TAG, "Sweep stopped by user or scope exit")
                        return
                    }

                    val (x, yy) = positions[index]
                    val rect = Rect(x - 70, yy - 45, x + 70, yy + 45)
                    Log.i(TAG, "Sweep hover ${index + 1}/5")

                    val path = Path().apply {
                        moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                    }
                    val stroke = GestureDescription.StrokeDescription(path, 0, SWEEP_DWELL_MS)
                    val gesture = GestureDescription.Builder()
                        .addStroke(stroke)
                        .build()

                    val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription) {
                            val fac = arrayOf("Speed", "Stamina", "Power", "Guts", "Wit")[index]
                            Log.i(TAG, "Sweep hover completed on $fac position")

                            // Post-hover capture (explicit sweep command still in progress).
                            // This populates lastOcrText / lastMatchReason and logs recognizable
                            // facility context for later vision work (no automation side-effect).
                            if (sweepEnabled && isInUma) {
                                handler.postDelayed({
                                    if (sweepEnabled && isInUma) {
                                        captureAndAnalyzeScreen { txt, _ ->
                                            val hit = listOf("Speed", "Stamina", "Power", "Guts", "Wit", "energy", "failure")
                                                .firstOrNull { txt.contains(it, ignoreCase = true) }
                                            if (hit != null) Log.i(TAG, "Post-hover OCR hint for $fac: saw '$hit'")
                                        }
                                    }
                                }, 180)
                            }

                            index++
                            handler.postDelayed({ hoverNext() }, SWEEP_INTER_HOVER_PAUSE_MS)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription) {
                            Log.w(TAG, "Gesture cancelled during sweep")
                        }
                    }, null)

                    if (!dispatched) {
                        Log.w(TAG, "Failed to dispatch hover gesture")
                        index++
                        handler.postDelayed({ hoverNext() }, 300)
                    }
                }

                hoverNext()
            }
            Unit
        }

        if (captureFirst) {
            captureAndAnalyzeScreen { _, _ ->
                // Give the OCR a moment, then sweep
                handler.postDelayed(doSweep, 400)
                Unit   // ensure the callback lambda returns Unit
            }
        } else {
            doSweep()
        }
    }

    /**
     * Perform a single no-choice advance (REQ-F2 / REQ-F4).
     *
     * Alpha behavior (REQ-A4 priority):
     * 1. If a specific recorded decision exists for the current (normalized) screen signature,
     *    try to replay it first (this is the user's prior choice on a similar screen).
     * 2. Otherwise fall back to walking the node tree for common advance/dismiss labels
     *    (aligned with CorpusMatcher positive rules).
     * 3. Last-resort conservative center tap.
     *
     * Must only be called from explicit user command or after a confirmed no-choice match.
     */
    fun performNoChoiceAdvance() {
        if (!isInUma) {
            Log.w(TAG, "No-choice advance blocked: not in Uma")
            return
        }

        // REQ-A4: prefer any previously recorded user decision for this screen.
        val sig = signatureFor(lastOcrText)
        if (sig.isNotBlank()) {
            val prior = getLastDecision(sig)
            if (!prior.isNullOrBlank()) {
                Log.i(TAG, "No-choice advance: found recorded decision '$prior' for current sig; attempting replay")
                if (tryReplayLastDecision(lastOcrText)) {
                    return
                }
                Log.i(TAG, "Recorded decision replay did not find a matching node; falling back to label search")
            }
        }

        Log.i(TAG, "No-choice advance requested (lastMatchReason=${lastMatchReason})")

        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "No active window root")
            return
        }

        // Keep in sync with CorpusMatcher positive (no-choice) rules.
        // These are the safe-to-advance generic UI strings observed across the corpus.
        val advanceLabels = listOf(
            "close", "next", "ok", "confirm", "race", "enter", "continue",
            "skip", "results", "watch", "replay", "done", "finish"
        )

        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun collect(node: AccessibilityNodeInfo) {
            val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            val lower = text.lowercase()
            if (advanceLabels.any { lower.contains(it) }) {
                if (node.isClickable || node.isFocusable) {
                    candidates.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { collect(it) }
            }
        }

        collect(root)

        if (candidates.isEmpty()) {
            Log.i(TAG, "No obvious advance button found via nodes. Falling back to center tap (risky).")
            // Very conservative fallback: tap roughly where "Close" or "Next" often is (bottom center).
            // In real code we would not do blind taps.
            val screenW = 1080
            val screenH = 2400
            val path = Path().apply {
                moveTo(screenW * 0.5f, screenH * 0.88f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 120)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            return
        }

        // Prefer a "primary" action when multiple candidates exist.
        // Order reflects common corpus patterns for safe advance.
        val priority = listOf("next", "confirm", "race", "enter", "continue", "ok", "done", "finish", "skip", "results", "replay", "watch", "close")
        val target = candidates.minByOrNull { c ->
            val t = ((c.text?.toString() ?: "") + " " + (c.contentDescription?.toString() ?: "")).lowercase()
            val idx = priority.indexOfFirst { t.contains(it) }
            if (idx >= 0) idx else 999
        } ?: candidates.first()

        val rect = Rect()
        target.getBoundsInScreen(rect)

        Log.i(TAG, "Tapping advance candidate at ${rect.centerX()},${rect.centerY()}")

        val path = Path().apply {
            moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 180)
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.i(TAG, "No-choice advance tap completed")
                }
            },
            null
        )

        if (!ok) {
            Log.w(TAG, "Failed to dispatch advance gesture")
        }
    }

    /**
     * Minimal list auto-scroll (REQ-A16 alpha).
     * Performs one human-scale vertical drag. Intended for race lists and similar scrollers.
     * Only acts when sweepEnabled and inside Uma. Must be called from explicit user action.
     */
    fun performListScrollOnce(directionDown: Boolean = true) {
        if (!isInUma || !sweepEnabled) {
            Log.w(TAG, "List scroll blocked: not in Uma or sweep disabled")
            return
        }
        Log.i(TAG, "List scroll (explicit) directionDown=$directionDown")

        val screenW = 1080
        val screenH = 2400
        val startX = (screenW * 0.55f)
        val startY = if (directionDown) (screenH * 0.62f) else (screenH * 0.38f)
        val endY   = if (directionDown) (screenH * 0.32f) else (screenH * 0.68f)

        handler.post {
            if (!sweepEnabled || !isInUma) return@post
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(startX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, LIST_SCROLL_DURATION_MS)
            val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            Log.i(TAG, "List scroll dispatched=$ok")
        }
    }

    /**
     * Clean exit from career (Save & Exit or Give Up).
     * Only allowed when user explicitly commands it (REQ-A5).
     *
     * Alpha behavior:
     * - If a prior decision was recorded for a similar screen via recordDecision,
     *   we honor "SAVE" / "GIVE" preference when present (case-insensitive contains).
     * - Otherwise the caller-provided default (preferSaveAndExit) is used.
     * - Flow: hamburger → menu item → confirm (human-scale pauses).
     * Crude geometry; real version will be driven by vision + corpus.
     */
    fun performCareerExit(preferSaveAndExit: Boolean = false) {
        if (!isInUma) {
            Log.w(TAG, "Career exit blocked: not in Uma")
            return
        }

        // Prefer a previously recorded user preference for this screen when available (REQ-A4).
        val recorded = getLastDecision(lastOcrText)
        val useSave = if (recorded != null) {
            val r = recorded.uppercase()
            when {
                r.contains("SAVE") -> true
                r.contains("GIVE") -> false
                else -> preferSaveAndExit
            }
        } else preferSaveAndExit

        Log.i(TAG, "Career exit requested (explicit) preferSaveAndExit=$useSave (recorded=${recorded ?: "none"})")

        val screenW = 1080
        val screenH = 2400
        val gameTop = 132  // observed from uixml

        // Approximate locations derived from corpus menu screenshots (snap12 + give_up set).
        // Hamburger is typically near top-left of the content area.
        val hamburgerX = (screenW * 0.06f).toInt()
        val hamburgerY = gameTop + (screenH * 0.03f).toInt()

        // Menu is a modal list. "Give Up" tends to be low in the list; "Save & Exit" above it.
        // Conservative taps near the lower half of the modal.
        val menuItemX = (screenW * 0.5f).toInt()
        val giveUpY = (screenH * 0.72f).toInt()
        val saveExitY = (screenH * 0.66f).toInt()

        // Confirmation modal affirmative is usually the right/primary action (or lower center).
        val confirmX = (screenW * 0.72f).toInt()
        val confirmY = (screenH * 0.78f).toInt()

        val targetY = if (preferSaveAndExit) saveExitY else giveUpY
        val label = if (preferSaveAndExit) "Save & Exit" else "Give Up"

        handler.post {
            if (!isInUma) return@post

            // Step 1: open hamburger
            Log.i(TAG, "Exit flow: tap hamburger")
            tap(hamburgerX.toFloat(), hamburgerY.toFloat(), 120)

            // Step 2: after modal animates, tap the menu item
            handler.postDelayed({
                if (!isInUma) return@postDelayed
                Log.i(TAG, "Exit flow: tap $label")
                tap(menuItemX.toFloat(), targetY.toFloat(), 140)

                // Step 3: confirmation
                handler.postDelayed({
                    if (!isInUma) return@postDelayed
                    Log.i(TAG, "Exit flow: tap confirm")
                    tap(confirmX.toFloat(), confirmY.toFloat(), 160)

                    // Optional post-confirm capture for logging
                    handler.postDelayed({
                        if (isInUma) captureAndAnalyzeScreen { _, _ -> }
                    }, 800)
                }, 900)
            }, 650)
        }
    }

    private fun tap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        if (!ok) Log.w(TAG, "tap dispatch failed at ($x,$y)")
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
