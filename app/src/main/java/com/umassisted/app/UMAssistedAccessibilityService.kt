package com.umassisted.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
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
class UMAssistedAccessibilityService : AccessibilityService() {

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
        @Volatile var instance: UMAssistedAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        // Anti-recursion / anti-overlap (service-health): bumped at the start of every
        // top-level explicit command (sweep, advance, exit, scroll). Any in-flight
        // handler.postDelayed chain captures the generation it started with and checks
        // it before each step, so starting a *new* explicit command cleanly invalidates
        // stale steps from a *previous* one instead of letting two sequences interleave
        // their gesture dispatches. Reinforces REQ-A5 (no unattended/overlapping loops).
        @Volatile var actionGeneration: Int = 0

        // === Overlay appearance (REQ-A17: small, icon-only, no OCR-able words) ===
        // Emoji rather than line glyphs: they carry state in colour/shape at a glance
        // without any words, which keeps the panel readable at OS-button size and
        // contributes nothing OCR-able if a capture path ever composites us in.
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val GLYPH_ARMED = "🟢"     // something is armed
        private const val GLYPH_IDLE = "⚪"      // nothing armed
        private const val GLYPH_SWEEP = "🧹"     // sweep + list auto-scroll
        private const val GLYPH_VOICE = "🎤"     // voice listening
        private const val GLYPH_READ = "👁"      // read screen (OCR only, no input)
        private const val GLYPH_RUN = "▶"       // run ONE sweep pass (dispatches input)
        private const val GLYPH_RUN_BLOCKED = "🚫"  // run unavailable: sweep not armed
        private const val GLYPH_READ_NOCHOICE = "✅"  // last read: safe to advance
        private const val GLYPH_READ_CHOICE = "❓"    // last read: has a real choice
        private const val GLYPH_READ_EMPTY = "❌"     // last read: nothing recognised
        private const val GLYPH_READ_PENDING = "⏳"   // read in flight
        // Internal state tokens for the read cell (kept separate from the glyphs so
        // the display can change without touching the state machine).
        private const val READ_PENDING = "pending"
        private const val READ_NOCHOICE = "nochoice"
        private const val READ_CHOICE = "choice"
        private const val READ_EMPTY = "empty"
        // Docked flush to the OS button — the two are one control cluster, so any gap
        // just reads as two competing widgets and wastes occlusion budget (REQ-A17).
        private const val DOCK_GAP_PX = 0
        private const val CELL_GAP_PX = 2
        private const val DEFAULT_CELL_DP = 48f     // only used if the OS button is absent
        private const val TEXT_SIZE_RATIO = 0.42f
        private const val AUTO_COLLAPSE_MS = 5_000L
        private const val CELL_IDLE_COLOR = 0xCC1B1B1B.toInt()
        private const val CELL_ON_COLOR = 0xCC0F766E.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null

    // Overlay kill switches (REQ-A7 / REQ-A10 / REQ-V9)
    private var overlayView: View? = null
    private var overlayHandle: TextView? = null
    private var overlaySweepCell: TextView? = null
    private var overlayVoiceCell: TextView? = null
    private var overlayReadCell: TextView? = null
    private var overlayRunCell: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayExpanded = false
    private var lastOsButtonBounds: Rect? = null

    // Result of the last overlay "Read screen" tap. Held in state rather than
    // written straight to the TextView because refreshOverlay() runs on every
    // accessibility event while in-game and would otherwise clobber it.
    @Volatile private var lastReadSummary: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
        instance = this
        startForegroundNotification()

        // Establish initial foreground state here rather than waiting for an event.
        // Accessibility events report *changes*; they are not a substitute for reading
        // current state at startup. If the service starts while Umamusume is already
        // foreground and the screen is static (a modal, a paused career, an idle
        // menu), no window-state-change event is guaranteed to arrive, so isInUma
        // would stay false and the always-visible kill switches (REQ-A7) would never
        // appear — the user would have to switch apps and back to get them, with no
        // indication why. This matters most on an OS-initiated rebind, which happens
        // outside the user's control and mid-session (observed repeatedly during the
        // FOREGROUND_SERVICE crash loop this build fixed).
        //
        // Posted with a short delay because rootInActiveWindow isn't reliably
        // populated at the instant onServiceConnected runs.
        handler.postDelayed({ updateForegroundState() }, 300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        updateForegroundState()

        // Alpha: we do not auto-react on every event.
        // Real work is driven by explicit user commands (sweep, voice, or manual capture).
        // We can add lightweight heuristics later (e.g. detect training view changes).
    }

    /**
     * Single source of truth for "is Umamusume the foreground app", driven both by
     * incoming accessibility events and by service connect.
     *
     * REQ-SF3: isInUma must reflect whether Umamusume is genuinely the *foreground*
     * app right now, not merely the source package of some incoming event. The
     * packageNames filter in accessibility_service_config.xml only restricts which
     * package events are delivered FROM — a backgrounded Uma process can still emit
     * window-content-changed events (cached activity, notification, etc.) while a
     * different app is actually on screen. rootInActiveWindow reflects the window
     * the system currently considers active, which is what "foreground" means here.
     */
    private fun updateForegroundState() {
        val activePkg = rootInActiveWindow?.packageName?.toString()
        val nowInUma = activePkg == TARGET_PACKAGE

        if (!nowInUma) {
            if (isInUma) {
                // Leaving the target app — clear transient capture state (hygiene).
                isInUma = false
                lastOcrText = ""
                lastWasNoChoice = false
                lastMatchReason = ""
                lastReadSummary = null
                hideOverlay()
            }
            return
        }
        isInUma = true
        lastForegroundPackage = activePkg

        // Always-visible kill switches (REQ-A7/A10/V9): show as soon as Uma becomes
        // foreground, not gated behind opening MainActivity — MainActivity is a
        // separate backgrounded app the whole time Uma is on screen.
        //
        // Keyed off whether the overlay actually exists, not off an inferred
        // wasInUma->isInUma transition. isInUma lives in the companion object, so it
        // is static and outlives the service instance: after the OS accessibility
        // button disabled and re-enabled us, the fresh instance started with
        // overlayView == null but a stale isInUma == true, took the refresh branch,
        // and refreshOverlay() returned immediately on the null view — so toggling
        // the service back on never brought the overlay back.
        if (overlayView == null) showOverlay() else refreshOverlay()
    }

    override fun onInterrupt() {
        // Required override. Stop any pending actions.
        handler.removeCallbacksAndMessages(null)
        actionGeneration++ // invalidate any in-flight command chain
        hideOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Service unbinding")
        handler.removeCallbacksAndMessages(null)
        actionGeneration++
        hideOverlay()
        resetTransientState()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        handler.removeCallbacksAndMessages(null)
        actionGeneration++
        hideOverlay()
        resetTransientState()
        if (instance === this) instance = null
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

        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val ok = dispatchGuarded(
            gen = actionGeneration,
            points = listOf(cx to cy),
            what = "decision replay tap",
            build = {
                val path = Path().apply { moveTo(cx, cy) }
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 160))
                    .build()
            }
        )
        Log.i(TAG, "Replay tap dispatched=$ok for previous choice")
        return ok
    }

    // ============================================================
    // Geometry + guarded dispatch (REQ-SF7, REQ-PL5)
    // ============================================================

    /**
     * The game's own window bounds. All gesture geometry must be derived from this,
     * never from display metrics: the game does not necessarily own the whole screen
     * (split-screen, freeform, insets, letterboxing). On this device the game window
     * was reported as Rect(86,303-993,2208) on a 1080x2400 display while the alpha's
     * sweep maths assumed a full 1080x2400 — i.e. every hover was already landing in
     * the wrong place whenever the window was inset. See REQ-PL5.
     */
    private fun gameWindowBounds(): Rect? {
        return try {
            windows.asSequence()
                .filter { it.root?.packageName == TARGET_PACKAGE }
                .map { w -> Rect().also { w.getBoundsInScreen(it) } }
                .filter { !it.isEmpty }
                .maxByOrNull { it.width().toLong() * it.height().toLong() }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read game window bounds", t)
            null
        }
    }

    /**
     * Dispatch a gesture only if it is still safe to do so, re-checked at this instant
     * rather than when the command started (REQ-SF7).
     *
     * Verifies: the target app is genuinely foreground, the command has not been
     * superseded, and every point of the gesture lies inside the game's own window —
     * a foreground app does not necessarily own every pixel, so "foreground" alone is
     * not enough to promise the tap cannot land somewhere else.
     */
    private fun dispatchGuarded(
        gen: Int,
        points: List<Pair<Float, Float>>,
        build: () -> GestureDescription,
        callback: GestureResultCallback? = null,
        what: String = "gesture"
    ): Boolean {
        if (!canContinue(gen)) {
            Log.i(TAG, "Blocked $what: superseded or no longer in target app")
            return false
        }
        val activePkg = rootInActiveWindow?.packageName?.toString()
        if (activePkg != TARGET_PACKAGE) {
            Log.w(TAG, "Blocked $what: foreground is $activePkg, not $TARGET_PACKAGE")
            return false
        }
        val bounds = gameWindowBounds()
        if (bounds == null) {
            Log.w(TAG, "Blocked $what: game window bounds unavailable")
            return false
        }
        for ((x, y) in points) {
            if (!bounds.contains(x.toInt(), y.toInt())) {
                Log.w(TAG, "Blocked $what: point ($x,$y) outside game window $bounds")
                return false
            }
        }
        val ok = dispatchGesture(build(), callback, null)
        if (!ok) Log.w(TAG, "dispatchGesture returned false for $what")
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

        val callback = buildScreenshotCallback(onResult)

        // Capture the game's window rather than the whole composited display, so our
        // own overlay is structurally excluded — it sits in a separate window above
        // Uma, and a display-level capture composites it in. That is not theoretical:
        // a display capture OCR'd "UMAssisted — active / Sweep / Voice / Read screen"
        // as if it were game text, which would poison corpus matching (REQ-M6) and any
        // future TTS readout (REQ-T1). This also drops the status/nav bars.
        //
        // takeScreenshotOfWindow is API 34+; minSdk is 30, so older devices fall back
        // to the display capture and will include the overlay. Acceptable for now
        // (alpha targets a single Android 14+ device) but it is a real fidelity gap
        // on 11–13 — worth hiding the overlay around the capture if that ever matters.
        val windowId = rootInActiveWindow?.windowId ?: -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && windowId != -1) {
            takeScreenshotOfWindow(windowId, ocrExecutor, callback)
        } else {
            Log.w(TAG, "Falling back to display capture (API ${Build.VERSION.SDK_INT}, windowId=$windowId) — overlay text may be OCR'd")
            takeScreenshot(0, ocrExecutor, callback)
        }
    }

    private fun buildScreenshotCallback(
        onResult: ((recognizedText: String, isNoChoice: Boolean) -> Unit)?
    ): TakeScreenshotCallback {
        return object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                // ScreenshotResult has no getBitmap() — the real API exposes a
                // HardwareBuffer + ColorSpace, wrapped into a Bitmap. The buffer must be
                // closed once wrapped (Bitmap keeps its own reference to the data), and
                // ML Kit / most software bitmap ops reject Config.HARDWARE, so we copy
                // down to a software ARGB_8888 bitmap immediately.
                val hardwareBuffer = result.hardwareBuffer
                val bitmap: android.graphics.Bitmap? = try {
                    android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                        ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not build Bitmap from screenshot HardwareBuffer", t)
                    null
                } finally {
                    hardwareBuffer.close()
                }

                if (bitmap == null) {
                    Log.e(TAG, "Screenshot bitmap unavailable; aborting OCR")
                    lastOcrText = ""
                    lastWasNoChoice = false
                    lastMatchReason = ""
                    onResult?.invoke("", false)
                    return
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
        }
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
        refreshOverlay()
    }

    fun setVoiceEnabled(enabled: Boolean) {
        voiceEnabled = enabled
        Log.i(TAG, "Voice listening ${if (enabled) "ENABLED" else "DISABLED"}")
        refreshOverlay()
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

        // Bump + capture the generation so a *new* explicit command (another sweep,
        // an exit, etc.) issued while this chain is still running invalidates every
        // remaining queued step below, instead of letting two sequences interleave.
        val myGen = ++actionGeneration

        val doSweep: () -> Unit = doSweep@{
            // Geometry is relative to the GAME WINDOW, never the display (REQ-PL5).
            // The facility row fractions below were derived from the 1080x2400
            // fullscreen corpus, so they are expressed as fractions of the window and
            // then mapped onto wherever that window actually is.
            val win = gameWindowBounds()
            if (win == null || win.isEmpty) {
                Log.w(TAG, "Sweep aborted: game window bounds unavailable")
                return@doSweep
            }
            Log.i(TAG, "Sweep geometry from game window $win")

            fun wx(fx: Float) = (win.left + win.width() * fx).toInt()
            fun wy(fy: Float) = (win.top + win.height() * fy).toInt()

            val y = wy(0.82f)
            val positions = listOf(
                wx(0.12f) to y,   // Speed
                wx(0.30f) to y,   // Stamina
                wx(0.50f) to y,   // Power
                wx(0.68f) to y,   // Guts
                wx(0.86f) to y    // Wit
            )

            handler.post {
                if (!sweepCanContinue(myGen)) {
                    Log.i(TAG, "Sweep aborted before start (kill switch, left game, or superseded)")
                    return@post
                }

                var index = 0
                fun hoverNext() {
                    if (index >= positions.size) {
                        Log.i(TAG, "Training sweep pass complete (5 hovers)")
                        // After the hover pass, if sweep is still armed, do one list scroll
                        // (REQ-A16: list auto-scroll behavior when sweep toggle is on).
                        // Still driven by the initial explicit command; no self-loop.
                        if (sweepCanContinue(myGen)) {
                            handler.postDelayed({
                                if (sweepCanContinue(myGen)) {
                                    performListScrollOnce(directionDown = true)
                                }
                            }, 220)

                            // Optional post-sweep capture for logging / replay seeding
                            handler.postDelayed({
                                if (sweepCanContinue(myGen)) {
                                    captureAndAnalyzeScreen { _, _ -> /* just log */ }
                                }
                            }, 900)
                        }
                        return
                    }
                    if (!sweepCanContinue(myGen)) {
                        Log.i(TAG, "Sweep stopped by user, scope exit, or superseded command")
                        return
                    }

                    val (x, yy) = positions[index]
                    val rect = Rect(x - 70, yy - 45, x + 70, yy + 45)
                    Log.i(TAG, "Sweep hover ${index + 1}/5")

                    val cx = rect.centerX().toFloat()
                    val cy = rect.centerY().toFloat()

                    val dispatched = dispatchGuarded(
                        gen = myGen,
                        points = listOf(cx to cy),
                        what = "sweep hover ${index + 1}/5",
                        build = {
                            val path = Path().apply { moveTo(cx, cy) }
                            GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(path, 0, SWEEP_DWELL_MS))
                                .build()
                        },
                        callback = object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription) {
                            val fac = arrayOf("Speed", "Stamina", "Power", "Guts", "Wit")[index]
                            Log.i(TAG, "Sweep hover completed on $fac position")

                            // Post-hover capture (explicit sweep command still in progress).
                            // This populates lastOcrText / lastMatchReason and logs recognizable
                            // facility context for later vision work (no automation side-effect).
                            if (sweepCanContinue(myGen)) {
                                handler.postDelayed({
                                    if (sweepCanContinue(myGen)) {
                                        captureAndAnalyzeScreen { txt, _ ->
                                            val hit = listOf("Speed", "Stamina", "Power", "Guts", "Wit", "energy", "failure")
                                                .firstOrNull { txt.contains(it, ignoreCase = true) }
                                            if (hit != null) Log.i(TAG, "Post-hover OCR hint for $fac: saw '$hit'")
                                        }
                                    }
                                }, 180)
                            }

                            index++
                            // Live sweepEnabled/isInUma are re-checked at the top of the next
                            // hoverNext() call itself; only the overlap guard is needed here.
                            if (myGen == actionGeneration) handler.postDelayed({ hoverNext() }, SWEEP_INTER_HOVER_PAUSE_MS)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription) {
                            Log.w(TAG, "Gesture cancelled during sweep")
                        }
                        }
                    )

                    if (!dispatched) {
                        Log.w(TAG, "Failed to dispatch hover gesture")
                        index++
                        if (myGen == actionGeneration) handler.postDelayed({ hoverNext() }, 300)
                    }
                }

                hoverNext()
            }
            Unit
        }

        if (captureFirst) {
            captureAndAnalyzeScreen { _, _ ->
                // Give the OCR a moment, then sweep (doSweep's own handler.post
                // re-checks sweepCanContinue live, so only the overlap guard matters here).
                if (myGen == actionGeneration) handler.postDelayed(doSweep, 400)
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

        // Explicit advance supersedes any in-flight sweep/exit chain (service-health:
        // anti-overlap). See actionGeneration doc on the companion object.
        val myGen = ++actionGeneration

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
            // Very conservative fallback: tap roughly where "Close" or "Next" often is
            // (bottom centre of the GAME WINDOW, not the display — REQ-PL5).
            val win = gameWindowBounds()
            if (win == null || win.isEmpty) {
                Log.w(TAG, "Advance fallback aborted: game window bounds unavailable")
                return
            }
            val fx = win.left + win.width() * 0.5f
            val fy = win.top + win.height() * 0.88f
            tap(fx, fy, 120, myGen, "advance fallback (blind)")
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

        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        dispatchGuarded(
            gen = myGen,
            points = listOf(cx to cy),
            what = "no-choice advance",
            build = {
                val path = Path().apply { moveTo(cx, cy) }
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 180))
                    .build()
            },
            callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.i(TAG, "No-choice advance tap completed")
                }
            }
        )
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

        val myGen = ++actionGeneration

        handler.post {
            if (!sweepEnabled || !isInUma) return@post
            // Window-relative, not display-relative (REQ-PL5).
            val win = gameWindowBounds()
            if (win == null || win.isEmpty) {
                Log.w(TAG, "List scroll aborted: game window bounds unavailable")
                return@post
            }
            val startX = win.left + win.width() * 0.55f
            val startY = win.top + win.height() * (if (directionDown) 0.62f else 0.38f)
            val endY = win.top + win.height() * (if (directionDown) 0.32f else 0.68f)

            dispatchGuarded(
                gen = myGen,
                points = listOf(startX to startY, startX to endY),
                what = "list scroll",
                build = {
                    val path = Path().apply {
                        moveTo(startX, startY)
                        lineTo(startX, endY)
                    }
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, LIST_SCROLL_DURATION_MS))
                        .build()
                }
            )
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

        // Bump generation: an explicit exit supersedes any in-flight sweep/advance chain.
        val myGen = ++actionGeneration

        val win = gameWindowBounds()
        if (win == null || win.isEmpty) {
            Log.w(TAG, "Career exit aborted: game window bounds unavailable")
            return
        }
        // Window-relative (REQ-PL5). The fractions came from the fullscreen corpus.
        val screenW = win.width()
        val screenH = win.height()
        val gameTop = win.top

        // Approximate locations derived from corpus menu screenshots (snap12 + give_up set).
        // Hamburger is typically near top-left of the content area.
        val hamburgerX = win.left + (screenW * 0.06f).toInt()
        val hamburgerY = gameTop + (screenH * 0.03f).toInt()

        // Menu is a modal list. "Give Up" tends to be low in the list; "Save & Exit" above it.
        // Conservative taps near the lower half of the modal.
        val menuItemX = win.left + (screenW * 0.5f).toInt()
        val giveUpY = win.top + (screenH * 0.72f).toInt()
        val saveExitY = win.top + (screenH * 0.66f).toInt()

        // Confirmation modal affirmative is usually the right/primary action (or lower center).
        val confirmX = win.left + (screenW * 0.72f).toInt()
        val confirmY = win.top + (screenH * 0.78f).toInt()

        val targetY = if (preferSaveAndExit) saveExitY else giveUpY
        val label = if (preferSaveAndExit) "Save & Exit" else "Give Up"

        handler.post {
            if (!canContinue(myGen)) return@post

            // Step 1: open hamburger
            Log.i(TAG, "Exit flow: tap hamburger")
            tap(hamburgerX.toFloat(), hamburgerY.toFloat(), 120, myGen, "exit: hamburger")

            // Step 2: after modal animates, tap the menu item
            handler.postDelayed({
                if (!canContinue(myGen)) return@postDelayed
                Log.i(TAG, "Exit flow: tap $label")
                tap(menuItemX.toFloat(), targetY.toFloat(), 140, myGen, "exit: $label")

                // Step 3: confirmation
                handler.postDelayed({
                    if (!canContinue(myGen)) return@postDelayed
                    Log.i(TAG, "Exit flow: tap confirm")
                    tap(confirmX.toFloat(), confirmY.toFloat(), 160, myGen, "exit: confirm")

                    // Optional post-confirm capture for logging
                    handler.postDelayed({
                        if (canContinue(myGen)) captureAndAnalyzeScreen { _, _ -> }
                    }, 800)
                }, 900)
            }, 650)
        }
    }

    /**
     * True while still safe to continue a chain started under generation [gen]:
     * still in the target app, and no newer explicit command has superseded it.
     * Single source of truth so postDelayed steps don't each spell out both
     * conditions inline — call this at every async boundary in a command chain.
     */
    private fun canContinue(gen: Int): Boolean = isInUma && gen == actionGeneration

    /** [canContinue] plus the sweep kill switch, for the sweep/list-scroll chain. */
    private fun sweepCanContinue(gen: Int): Boolean = sweepEnabled && canContinue(gen)

    /**
     * Single tap, gated by the same REQ-SF7 checks as every other dispatch: the
     * target app must still be foreground and the point must lie inside its window.
     */
    private fun tap(x: Float, y: Float, durationMs: Long, gen: Int, what: String = "tap") {
        dispatchGuarded(
            gen = gen,
            points = listOf(x to y),
            what = what,
            build = {
                val path = Path().apply { moveTo(x, y) }
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
            }
        )
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

    // ============================================================
    // Overlay kill switches (REQ-A7 / REQ-A10 / REQ-V9)
    //
    // TYPE_ACCESSIBILITY_OVERLAY, not TYPE_APPLICATION_OVERLAY: an AccessibilityService
    // can add this window type directly, no SYSTEM_ALERT_WINDOW permission or user
    // "draw over other apps" grant needed. Shown only while isInUma is true; removed
    // the instant the user leaves Uma or the service is interrupted/unbound/destroyed,
    // so it never lingers over another app (REQ-SF1).
    // ============================================================

    /**
     * Probe: what windows can we actually see, and can we locate the OS
     * accessibility floating button ("FloatingMenu") so the overlay can size and
     * position itself against it? Its size and position are user-customizable, so
     * reading it at runtime beats hardcoding or depending on @hide settings keys.
     */
    private fun findAccessibilityButtonBounds(): Rect? {
        return try {
            windows.asSequence()
                .mapNotNull { w ->
                    val b = Rect()
                    w.getBoundsInScreen(b)
                    if (w.root?.packageName == SYSTEM_UI_PACKAGE) b else null
                }
                // The floating a11y button is a small, roughly square systemui window.
                // The other systemui windows on screen are the status bar, nav bar and
                // screen-decor overlay, which are all full-width strips — so squareness
                // plus a size ceiling separates them without hardcoding any geometry.
                .firstOrNull { b ->
                    val w = b.width()
                    val h = b.height()
                    w in 40..400 && h in 40..400 && w.toFloat() / h.toFloat() in 0.6f..1.6f
                }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read window list to locate the a11y button", t)
            null
        }
    }

    /** One square control in the overlay, sized to match the OS button. */
    private fun makeCell(glyph: String, onTap: () -> Unit): TextView {
        return TextView(this).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(CELL_IDLE_COLOR)
            setOnClickListener { onTap() }
        }
    }

    private fun showOverlay() {
        if (overlayView != null) {
            refreshOverlay()
            return
        }
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            // Collapsed handle. Tapping expands; tapping again collapses.
            val handle = makeCell(GLYPH_IDLE) { toggleOverlayExpanded() }
            val sweepCell = makeCell(GLYPH_SWEEP) { setSweepEnabled(!sweepEnabled); armAutoCollapse() }
            val voiceCell = makeCell(GLYPH_VOICE) { setVoiceEnabled(!voiceEnabled); armAutoCollapse() }
            val readCell = makeCell(GLYPH_READ) {
                // Read-only: screenshot + OCR, no gesture dispatch (REQ-DEV1/2/3).
                lastReadSummary = READ_PENDING
                refreshOverlay()
                armAutoCollapse()
                captureAndAnalyzeScreen { text, noChoice ->
                    lastReadSummary = when {
                        text.isBlank() -> READ_EMPTY
                        noChoice -> READ_NOCHOICE
                        else -> READ_CHOICE
                    }
                    handler.post { refreshOverlay() }
                }
            }

            // Runs ONE sweep pass. Deliberately separate from the sweep toggle: the
            // toggle only *arms* the assist (REQ-A10), and REQ-A5 requires a fresh
            // explicit user command for each pass — arming must never start anything.
            // This is also the only in-game way to start a sweep at all: MainActivity
            // cannot do it, because foregrounding MainActivity makes Uma non-foreground
            // and the sweep is then correctly refused.
            val runCell = makeCell(GLYPH_RUN) {
                armAutoCollapse()
                if (!sweepEnabled) {
                    Log.i(TAG, "Run ignored: sweep not armed")
                } else {
                    performTrainingSweepOnce()
                }
            }

            root.addView(handle)
            root.addView(sweepCell)
            root.addView(voiceCell)
            root.addView(readCell)
            root.addView(runCell)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // LAYOUT_IN_SCREEN/NO_LIMITS so x/y are true screen coordinates. Without
                // them the offsets are measured from the inset content frame, which put
                // us a status-bar's height below the OS button instead of flush to it.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            overlayView = root
            overlayHandle = handle
            overlaySweepCell = sweepCell
            overlayVoiceCell = voiceCell
            overlayReadCell = readCell
            overlayRunCell = runCell
            overlayParams = params

            applyOverlayGeometry()
            wm.addView(root, params)
            refreshOverlay()
            Log.i(TAG, "Overlay shown")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to show overlay", t)
            clearOverlayRefs()
        }
    }

    /**
     * Size every cell to the OS accessibility button and dock the column directly
     * beneath it. The user can move and resize that button (Settings > Accessibility
     * > shortcut), so both the collapsed handle and the expanded column are derived
     * from its live bounds rather than fixed dp — REQ-A17's "smallest footprint that
     * is still hittable" is defined by whatever the user already chose as a
     * comfortable target size, not by a number we picked.
     */
    private fun applyOverlayGeometry() {
        val params = overlayParams ?: return
        val osButton = findAccessibilityButtonBounds()
        val cell = osButton?.width() ?: defaultCellPx()

        for (v in listOfNotNull(overlayHandle, overlaySweepCell, overlayVoiceCell, overlayReadCell, overlayRunCell)) {
            val lp = LinearLayout.LayoutParams(cell, cell)
            lp.topMargin = CELL_GAP_PX
            v.layoutParams = lp
            // COMPLEX_UNIT_PX: the cell size is already in pixels, and the default
            // textSize setter interprets sp, which over-scales by the display density.
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cell * TEXT_SIZE_RATIO)
        }

        if (osButton != null) {
            // Dock under the button, aligned to its edge, so the two read as one
            // cluster instead of two competing corners.
            params.x = osButton.left
            params.y = osButton.bottom + DOCK_GAP_PX
            lastOsButtonBounds = Rect(osButton)
        } else {
            // Shortcut not enabled (no floating button) — fall back to a top-right rest
            // position that still avoids the status bar.
            val dm = resources.displayMetrics
            params.x = dm.widthPixels - cell - DOCK_GAP_PX
            params.y = (dm.heightPixels * 0.10f).toInt()
            lastOsButtonBounds = null
        }
    }

    private fun defaultCellPx(): Int =
        (DEFAULT_CELL_DP * resources.displayMetrics.density).toInt()

    private fun toggleOverlayExpanded() {
        overlayExpanded = !overlayExpanded
        refreshOverlay()
        if (overlayExpanded) armAutoCollapse() else handler.removeCallbacks(collapseRunnable)
    }

    private val collapseRunnable = Runnable {
        if (overlayExpanded) {
            overlayExpanded = false
            refreshOverlay()
        }
    }

    private fun armAutoCollapse() {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        handler.removeCallbacks(collapseRunnable)
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
            Log.i(TAG, "Overlay hidden")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to remove overlay view", t)
        }
        clearOverlayRefs()
    }

    /**
     * Static state in the companion object outlives this instance (the process can
     * survive an accessibility-service unbind/rebind), so reset the pieces that
     * describe "where we are right now" on teardown. Otherwise the next instance
     * inherits a stale view of the world.
     */
    private fun resetTransientState() {
        isInUma = false
        lastOcrText = ""
        lastWasNoChoice = false
        lastMatchReason = ""
        lastReadSummary = null
    }

    private fun clearOverlayRefs() {
        overlayView = null
        overlayHandle = null
        overlaySweepCell = null
        overlayVoiceCell = null
        overlayReadCell = null
        overlayRunCell = null
        overlayParams = null
        overlayExpanded = false
        lastOsButtonBounds = null
    }

    /**
     * Keep the overlay in sync with live state, regardless of whether a change came
     * from the overlay, MainActivity, or the user moving/resizing the OS button.
     * Safe to call when the overlay isn't shown (no-op).
     */
    fun refreshOverlay() {
        val root = overlayView ?: return

        // Re-derive geometry: the OS button is user-movable and user-resizable, so its
        // bounds can change at any time without notifying us.
        val current = findAccessibilityButtonBounds()
        if (current != lastOsButtonBounds) {
            applyOverlayGeometry()
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(root, overlayParams)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to reposition overlay", t)
            }
        }

        overlaySweepCell?.setBackgroundColor(if (sweepEnabled) CELL_ON_COLOR else CELL_IDLE_COLOR)
        overlayVoiceCell?.setBackgroundColor(if (voiceEnabled) CELL_ON_COLOR else CELL_IDLE_COLOR)
        // The read cell reports the last read's verdict rather than a generic icon —
        // ✅ safe to advance, ❓ a real choice is on screen, ❌ nothing recognised.
        overlayReadCell?.text = when (lastReadSummary) {
            READ_NOCHOICE -> GLYPH_READ_NOCHOICE
            READ_CHOICE -> GLYPH_READ_CHOICE
            READ_EMPTY -> GLYPH_READ_EMPTY
            READ_PENDING -> GLYPH_READ_PENDING
            else -> GLYPH_READ
        }

        val childVisibility = if (overlayExpanded) View.VISIBLE else View.GONE
        overlaySweepCell?.visibility = childVisibility
        overlayVoiceCell?.visibility = childVisibility
        overlayReadCell?.visibility = childVisibility
        overlayRunCell?.visibility = childVisibility
        // Run is only meaningful once sweep is armed; show that rather than failing
        // silently when tapped.
        overlayRunCell?.text = if (sweepEnabled) GLYPH_RUN else GLYPH_RUN_BLOCKED
        overlayRunCell?.setBackgroundColor(if (sweepEnabled) CELL_ON_COLOR else CELL_IDLE_COLOR)

        // The handle itself doubles as the at-a-glance status indicator so the
        // collapsed state still communicates whether anything is armed.
        val armed = sweepEnabled || voiceEnabled
        overlayHandle?.text = if (armed) GLYPH_ARMED else GLYPH_IDLE
        overlayHandle?.setBackgroundColor(if (armed) CELL_ON_COLOR else CELL_IDLE_COLOR)
    }

}
