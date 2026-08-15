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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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
        const val SWEEP_RELEASE_MS = 180L      // slide clear of the row before lifting
        const val SWEEP_RELEASE_FRAC = 0.12f   // how far clear, as a fraction of window height
        const val VOICE_ACTION_GRACE_MS = 700L // tap/cancel can abort before the action fires
        // Gap between the arm-tap and confirm-tap for REQ-V22's "$facility
        // Training" one-shot — long enough for the game's own preview popup to
        // render before the commit tap fires, short enough to still feel instant.
        const val FACILITY_TRAINING_CONFIRM_GAP_MS = 350L
        // Settle time between a macro step's dispatched tap and the next OCR capture,
        // long enough for the game's own transition animation to finish so the capture
        // doesn't land mid-transition and miss the next step's screen.
        const val MACRO_STEP_SETTLE_MS = 900L
        // Cap on OCR input's longer side, in px. 1200 is a 50% scale on this
        // device's 2400px-tall capture — matches tools/capture_screen.sh's
        // `ffmpeg -vf scale=iw/2:ih/2` in the public UMAssisted repo, the same
        // 50% factor already established for the debug "snap" corpus captures.
        // Kept identical deliberately: both pipelines shrink a raw screenshot for
        // review, one by a human and one by ML Kit, and there's no reason for the
        // two decisions to drift apart.
        const val OCR_MAX_DIMENSION_PX = 1200
        // Retry backoff for a macro tick that found no matching screen: an explicit
        // schedule rather than a formula, front-loaded very fast since most
        // "nothing matched" outcomes are a capture landing mid-transition and
        // resolve within a beat or two.
        val MACRO_RETRY_DELAYS_MS = longArrayOf(10L, 50L, 100L, 250L, 500L, 1000L, 2000L)

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
        private const val GLYPH_READ = "🔍"      // read screen (OCR only, no input)
        private const val GLYPH_PHRASES = "📋"    // REQ-V20: valid commands / current screen panel
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
        // REQ-A31: the recognizer takes several seconds after being armed before
        // onRmsChanged starts actually firing (confirmed on-device) — orange
        // distinguishes "armed, still warming up" from "armed, mic confirmed live".
        private const val CELL_WARMING_COLOR = 0xCCB45309.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null

    // REQ-A22/REQ-V12: voice facility selection (arm on first utterance, confirm on
    // the repeat). Pure decision logic lives in VoiceFacilitySelection; this service
    // just supplies the gesture/timeout side effects.
    private var voiceListener: VoiceListener? = null
    private val voiceFacilitySelection = VoiceFacilitySelection { UserSettings.getVoiceConfirmWindowMs() }
    private val voiceResumeRunnable = Runnable { resumeSweepAfterVoiceTimeout() }
    // REQ-A23/A24: any recognized facility name or the dedicated "continue" phrase
    // restarts the sweep — a continuation signal, not touch-screen input. Voice is the
    // first channel; REQ-A24 defines this as modality-agnostic, so a future non-voice
    // channel (haptic/switch) would feed the same field, not a parallel mechanism.
    @Volatile private var lastVoiceHeartbeatAtMs: Long = 0L
    private var lastDebouncedVoiceKey: String = ""

    // REQ-A21: while a macro is paused at a Decision step in RECORDING_DEFAULTS mode,
    // the user's own next tap in the game is what gets recorded as the default for
    // that decision key — this does not resume the macro (REQ-A5: no auto-continuation
    // beyond what a single command authorized). Cleared as soon as consumed, or when
    // superseded by a new command (generation mismatch).
    private var macroDecisionWaitKey: String? = null
    private var macroDecisionWaitGen: Int = -1
    private var macroDecisionWaitRecords: Boolean = false
    private var macroDecisionWaitMacroName: String = ""
    private var lastDebouncedVoiceAtMs: Long = 0L
    private var pendingVoiceMatch: VoiceCorpus.Match? = null
    private val pendingVoiceRunnable = Runnable { flushPendingVoiceAction() }
    private var voiceTapCatcher: View? = null
    private var suppressTapCancelUntilMs: Long = 0L

    // Overlay kill switches (REQ-A7 / REQ-A10 / REQ-V9)
    private var overlayView: View? = null
    private var overlayHandle: AudioLevelView? = null
    /** REQ-A31: true once onRmsChanged has fired at least once for the current
     * voice-armed session — the recognizer takes a few seconds to warm up. */
    @Volatile private var voiceWarmedUp = false
    /** REQ-A27: whether the currently-running macro was invoked with "quickly". */
    @Volatile private var currentMacroQuick = false
    private var overlaySweepCell: TextView? = null
    private var overlayVoiceCell: TextView? = null
    private var overlayReadCell: TextView? = null
    private var overlayRunCell: TextView? = null
    /** REQ-V20: toggle cell for the valid-commands/current-screen panel. */
    private var overlayPhraseCell: TextView? = null
    /** REQ-V20: the panel itself — wide multi-line text, not a fixed square cell. */
    private var overlayPhrasePanel: TextView? = null
    private var phrasePanelExpanded = false
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

        // Immediate check if root window is already available; fallback post only if pending
        if (rootInActiveWindow != null) {
            updateForegroundState()
        } else {
            handler.postDelayed({ updateForegroundState() }, 100)
        }

        // voiceEnabled lives in the companion object and outlives this instance —
        // same class of staleness updateForegroundState's own comment documents for
        // isInUma/the overlay. A fresh instance starting with voiceEnabled==true but
        // voiceListener==null must re-arm it, or the UI keeps showing voice as on
        // while nothing is actually listening.
        if (voiceEnabled) setVoiceEnabled(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        updateForegroundState()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
        ) {
            maybeCancelVoiceFromUserTap("a11y-tap")
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            maybeRecordMacroDecisionFromTap(event)
        }
    }

    /** REQ-A21: capture what the user tapped at a paused macro Decision step. */
    private fun maybeRecordMacroDecisionFromTap(event: AccessibilityEvent) {
        val key = macroDecisionWaitKey ?: return
        val gen = macroDecisionWaitGen
        macroDecisionWaitKey = null
        if (!canContinue(gen)) return
        val src = event.source
        val text = (src?.text?.toString() ?: event.text?.joinToString(" ") ?: "").trim()
        if (text.isBlank() || AutoRunMacros.isForbiddenTapTarget(text)) return
        if (macroDecisionWaitRecords) {
            recordDecision("macro.decision.$key", text)
            Log.i(TAG, "macro $macroDecisionWaitMacroName: recorded default for \"$key\" = \"$text\" from user tap")
            VoiceDebugLog.log("macro $macroDecisionWaitMacroName: recorded default \"$key\" = \"$text\"")
        }
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
                // An armed-but-unconfirmed voice selection must not survive a trip
                // out of the game — otherwise saying the same facility name again
                // after returning resolves as Confirm (a real tap) instead of the
                // fresh Arm the user actually intended.
                voiceFacilitySelection.clear()
                handler.removeCallbacks(pendingVoiceRunnable)
                pendingVoiceMatch = null
                hideVoiceTapCatcher()
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
        // Real teardown, unlike onInterrupt: release the mic/recognizer and restore
        // any muted stream now, or both leak until something else happens to fix them.
        voiceListener?.stop()
        voiceListener = null
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
        voiceListener?.stop()
        voiceListener = null
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

    // Tap-by-OCR-text state (REQ-M11): the game exposes no AccessibilityNodeInfo
    // content at all (single opaque Unity SurfaceView, confirmed empty node tree),
    // so a matched piece of text can only ever be tapped via the bounding box ML
    // Kit itself reports, converted back from the (downscaled) OCR bitmap's pixel
    // space into real screen coordinates using the window bounds and scale factor
    // captured at the same moment as the OCR request.
    @Volatile private var lastOcrVisionText: Text? = null
    @Volatile private var lastOcrCaptureWinBounds: Rect? = null
    @Volatile private var lastOcrScaleFactor: Float = 1f

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

        // REQ-M11: was an AccessibilityNodeInfo tree search — always found nothing,
        // since the game exposes no node content. findAndTapText (OCR bounding
        // boxes) is the only mechanism that can actually locate text on screen.
        val ok = findAndTapText(actionGeneration, previous!!, "decision replay tap")
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
        // Injected gestures must not count as a user tap-to-cancel.
        suppressTapCancelUntilMs = System.currentTimeMillis() + 500L
        val catcherWasUp = voiceTapCatcher != null
        if (catcherWasUp) hideVoiceTapCatcher()
        val ok = dispatchGesture(build(), callback, null)
        if (!ok) Log.w(TAG, "dispatchGesture returned false for $what")
        if (catcherWasUp && hasCancellableVoiceRequest()) showVoiceTapCatcher()
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
        val requestedAtMs = android.os.SystemClock.elapsedRealtime()
        // Captured now, not after the screenshot returns: this is what
        // findAndTapText will use to convert an OCR bounding box back to a real
        // screen point, so it must describe the window at the moment the pixels
        // being OCR'd were actually captured.
        val winBoundsAtRequest = gameWindowBounds()

        val callback = buildScreenshotCallback(onResult, requestedAtMs, winBoundsAtRequest)

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
        onResult: ((recognizedText: String, isNoChoice: Boolean) -> Unit)?,
        requestedAtMs: Long,
        winBoundsAtRequest: Rect?
    ): TakeScreenshotCallback {
        return object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val capturedAtMs = android.os.SystemClock.elapsedRealtime()
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

                val bitmapReadyAtMs = android.os.SystemClock.elapsedRealtime()

                // Downscale before OCR: recognize() cost tracks pixel count. This DOES
                // require a coordinate translation now (REQ-M11) — findAndTapText
                // taps by the OCR bounding box ML Kit returns, since the game exposes
                // no AccessibilityNodeInfo content to tap by. lastOcrScaleFactor below
                // is exactly what undoes this scale when converting a box back to a
                // real screen point.
                val longSide = maxOf(bitmap.width, bitmap.height)
                val appliedScale = if (longSide > OCR_MAX_DIMENSION_PX) {
                    OCR_MAX_DIMENSION_PX.toFloat() / longSide
                } else {
                    1f
                }
                val ocrBitmap = if (appliedScale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * appliedScale).toInt().coerceAtLeast(1),
                        (bitmap.height * appliedScale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bitmap
                }
                val scaledAtMs = android.os.SystemClock.elapsedRealtime()
                val image = InputImage.fromBitmap(ocrBitmap, 0)

                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val ocrDoneAtMs = android.os.SystemClock.elapsedRealtime()
                        val fullText = visionText.text
                        lastOcrText = fullText
                        lastOcrVisionText = visionText
                        lastOcrCaptureWinBounds = winBoundsAtRequest
                        lastOcrScaleFactor = appliedScale

                        val match = CorpusMatcher.match(fullText)
                        lastWasNoChoice = match.isNoChoice
                        lastMatchReason = match.reason

                        Log.i(TAG, "=== OCR RESULT (noChoice=${lastWasNoChoice}, reason=${match.reason}) ===")
                        if (BuildConfig.DEBUG) {
                            Log.i(TAG, fullText.take(1800))
                        }
                        Log.i(TAG, "=== END OCR ===")
                        Log.i(
                            TAG,
                            "OCR timing: capture=${capturedAtMs - requestedAtMs}ms " +
                                "bitmap=${bitmapReadyAtMs - capturedAtMs}ms " +
                                "scale=${scaledAtMs - bitmapReadyAtMs}ms (${bitmap.width}x${bitmap.height}->${ocrBitmap.width}x${ocrBitmap.height}) " +
                                "recognize=${ocrDoneAtMs - scaledAtMs}ms " +
                                "total=${ocrDoneAtMs - requestedAtMs}ms"
                        )

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
        VoiceDebugLog.log(if (enabled) "=== VOICE ARMED ===" else "=== VOICE DISARMED ===")
        if (enabled) {
            voiceWarmedUp = false
            val listener = voiceListener
                ?: VoiceListener(this, ::onVoiceUtterances, ::isUnambiguousVoiceMatch) { rms, active ->
                    if (!voiceWarmedUp) {
                        voiceWarmedUp = true
                        refreshOverlay()
                    }
                    overlayHandle?.addSample(rms, active)
                }.also { voiceListener = it }
            listener.start()
        } else {
            voiceListener?.stop()
            handler.removeCallbacks(voiceResumeRunnable)
            voiceFacilitySelection.clear()
            handler.removeCallbacks(pendingVoiceRunnable)
            pendingVoiceMatch = null
            hideVoiceTapCatcher()
            // A heartbeat recorded before voice was disabled must not go on
            // justifying a sweep restart-on-signal after the fact.
            lastVoiceHeartbeatAtMs = 0L
            overlayHandle?.clearTrace()
        }
        refreshOverlay()
    }

    /**
     * REQ-V19 / OQ-43: the cancel/correct vocabulary now exists (VoiceCorpus),
     * so this resolves partials against the real command corpus instead of
     * staying permanently inert — without that, every command sat out the
     * full 60s silence timeout before finalizing (observed on-device).
     *
     * A fragment that momentarily resolves (e.g. "speed" mid-utterance, before
     * the user finishes "speed and stamina") is not filtered out here — it's
     * filtered by VoiceListener's real-time settle window (EARLY_STOP_SETTLE_MS),
     * which only stops the session once the *same* match has held for ~900ms
     * of wall-clock time, spanning several partial updates. This predicate just
     * says whether the current partial is a real command, not noise.
     */
    private fun isUnambiguousVoiceMatch(candidates: List<String>): Boolean {
        val match = VoiceCorpus.resolveDetailed(candidates).match
        return match !is VoiceCorpus.Match.None && match !is VoiceCorpus.Match.Ambiguous
    }

    /** Lets the settings UI apply a chime-mute toggle immediately to an already-armed session. */
    fun applyVoiceChimeMuteLive() {
        voiceListener?.applyChimeMuteSettingLive()
    }

    /**
     * Debug-only: feed a phrase through the same path as STT so the
     * implemented corpus can be tested without the recognizer.
     */
    fun debugInjectUtterance(text: String) {
        if (!BuildConfig.DEBUG) return
        VoiceDebugLog.log("DEBUG inject: $text")
        onVoiceUtterances(listOf(text))
    }

    private fun onVoiceUtterances(candidates: List<String>): Boolean {
        Log.i(TAG, "Voice recognized candidates: $candidates")
        VoiceDebugLog.log("utterances: $candidates")
        val evidence = VoiceCorpus.resolveDetailed(candidates)
        val resolved = evidence.match
        VoiceDebugLog.log("resolved: $resolved")
        VoiceDebugLog.log(evidence.actedOnLine())
        Log.i(TAG, evidence.actedOnLine())

        // REQ-A31: every path through this function ends with a charm marker
        // on the overlay trace — accepted (green) for anything genuinely
        // acted on, rejected (red) for heard-but-unmatched or heard-but-
        // ignored-by-state. Makes "I said something and nothing happened"
        // visually distinct from "the mic never heard me" (a gray gap on the
        // RMS layer, which this function never runs for at all).
        val acted = onVoiceUtterancesInner(candidates, resolved)
        overlayHandle?.markCommandResult(acted)
        return acted
    }

    private fun onVoiceUtterancesInner(candidates: List<String>, resolved: VoiceCorpus.Match): Boolean {
        if (!voiceEnabled || !isInUma) {
            VoiceDebugLog.log("ignored (voiceEnabled=$voiceEnabled isInUma=$isInUma resolved=$resolved)")
            return false
        }

        return when (resolved) {
            is VoiceCorpus.Match.None -> {
                VoiceDebugLog.log("no match")
                false
            }
            is VoiceCorpus.Match.Ambiguous -> {
                VoiceDebugLog.log("ambiguous — ignored (say one facility, or the same one twice)")
                false
            }
            is VoiceCorpus.Match.Cancel -> {
                cancelPendingVoiceRequest("voice:${resolved.phrase}")
                true
            }
            is VoiceCorpus.Match.StopListening -> {
                VoiceDebugLog.log("stop-listening command: \"${resolved.phrase}\"")
                // Posted, not called synchronously — this callback is running
                // from inside VoiceListener's own recognition-result handling;
                // tearing the recognizer down mid-callback is exactly the kind
                // of reentrancy that's caused real bugs elsewhere this session.
                handler.post { setVoiceEnabled(false) }
                true
            }
            is VoiceCorpus.Match.Confirm -> {
                if (voiceFacilitySelection.currentlyArmed() == null) {
                    VoiceDebugLog.log("confirm ignored — nothing armed")
                    return false
                }
                scheduleVoiceAction(resolved)
                true
            }
            is VoiceCorpus.Match.Macro,
            is VoiceCorpus.Match.Heartbeat,
            is VoiceCorpus.Match.Facility,
            is VoiceCorpus.Match.FacilityTraining,
            is VoiceCorpus.Match.HubButton -> {
                scheduleVoiceAction(resolved)
                true
            }
        }
    }

    private fun scheduleVoiceAction(match: VoiceCorpus.Match) {
        handler.removeCallbacks(pendingVoiceRunnable)
        // REQ-V4: the tap-to-cancel grace window exists to let a user retract a
        // *consequential* action before it fires. HubButton (REQ-V23) never
        // commits anything to the career — it only navigates to a screen that
        // is already OCR-confirmed to be showing that exact label — so it gets
        // no grace window and fires immediately instead of waiting it out.
        if (match is VoiceCorpus.Match.HubButton) {
            pendingVoiceMatch = null
            VoiceDebugLog.log("no grace window (inconsequential navigation): $match")
            executeResolvedVoiceAction(match)
            return
        }
        pendingVoiceMatch = match
        VoiceDebugLog.log("pending ${VOICE_ACTION_GRACE_MS}ms: $match (say cancel/oops/escape/abort or tap to abort)")
        showVoiceTapCatcher()
        handler.postDelayed(pendingVoiceRunnable, VOICE_ACTION_GRACE_MS)
    }

    private fun flushPendingVoiceAction() {
        val match = pendingVoiceMatch ?: return
        pendingVoiceMatch = null
        executeResolvedVoiceAction(match)
        if (voiceFacilitySelection.currentlyArmed() != null) {
            showVoiceTapCatcher()
        } else {
            hideVoiceTapCatcher()
        }
    }

    private fun executeResolvedVoiceAction(resolved: VoiceCorpus.Match) {
        when (resolved) {
            is VoiceCorpus.Match.Macro -> {
                if (debounceVoice("macro:${resolved.command}")) return
                VoiceDebugLog.log("macro command detected: ${resolved.command}${if (resolved.quick) " (quickly)" else ""}")
                executeMacroCommand(resolved.command, resolved.quick)
            }
            is VoiceCorpus.Match.Heartbeat -> {
                if (debounceVoice("heartbeat")) return
                lastVoiceHeartbeatAtMs = System.currentTimeMillis()
                VoiceDebugLog.log("heartbeat/continuation signal detected")
                if (!sweepEnabled) {
                    VoiceDebugLog.log("continuation ignored (sweepEnabled=false)")
                    return
                }
                if (voiceFacilitySelection.currentlyArmed() != null) {
                    VoiceDebugLog.log("continuation signal: resuming paused sweep")
                    handler.removeCallbacks(voiceResumeRunnable)
                    resumeSweepAfterVoiceTimeout()
                } else {
                    VoiceDebugLog.log("continuation signal: starting training sweep pass")
                    performTrainingSweepOnce(captureFirst = false)
                }
            }
            is VoiceCorpus.Match.Facility -> {
                lastVoiceHeartbeatAtMs = System.currentTimeMillis()
                VoiceDebugLog.log("matched facility: ${resolved.name} (x${resolved.repeats})")
                repeat(resolved.repeats.coerceIn(1, 2)) {
                    dispatchFacilityUtterance(resolved.index)
                }
            }
            is VoiceCorpus.Match.Confirm -> {
                val armed = voiceFacilitySelection.currentlyArmed()
                if (armed == null) {
                    VoiceDebugLog.log("confirm ignored — nothing armed")
                    return
                }
                VoiceDebugLog.log("confirm word — committing ${FacilityVocabulary.facilityNames[armed]}")
                dispatchFacilityUtterance(armed)
            }
            is VoiceCorpus.Match.FacilityTraining -> {
                lastVoiceHeartbeatAtMs = System.currentTimeMillis()
                VoiceDebugLog.log("matched \"${resolved.name} training\" — direct jump")
                dispatchFacilityTrainingUtterance(resolved.index)
            }
            is VoiceCorpus.Match.HubButton -> {
                lastVoiceHeartbeatAtMs = System.currentTimeMillis()
                VoiceDebugLog.log("hub button command: \"${resolved.label}\"")
                dispatchHubButtonUtterance(resolved.label)
            }
            else -> { /* None / Ambiguous / Cancel never scheduled */ }
        }
    }

    private fun hasCancellableVoiceRequest(): Boolean =
        pendingVoiceMatch != null || voiceFacilitySelection.currentlyArmed() != null

    private fun maybeCancelVoiceFromUserTap(reason: String) {
        if (!hasCancellableVoiceRequest()) return
        if (System.currentTimeMillis() < suppressTapCancelUntilMs) return
        cancelPendingVoiceRequest(reason)
    }

    /** REQ-V19: retract an armed or not-yet-dispatched voice action. */
    private fun cancelPendingVoiceRequest(reason: String) {
        if (!hasCancellableVoiceRequest()) {
            VoiceDebugLog.log("CANCEL ($reason): nothing pending")
            return
        }
        val wasArmed = voiceFacilitySelection.currentlyArmed()
        handler.removeCallbacks(pendingVoiceRunnable)
        pendingVoiceMatch = null
        handler.removeCallbacks(voiceResumeRunnable)
        voiceFacilitySelection.clear()
        hideVoiceTapCatcher()
        actionGeneration++
        VoiceDebugLog.log("CANCELLED ($reason) armedWas=$wasArmed")
        Log.i(TAG, "Voice request cancelled ($reason)")
        if (wasArmed != null && sweepEnabled && isInUma) {
            VoiceDebugLog.log("cancel: resuming sweep after un-arm")
            performTrainingSweepOnce(captureFirst = false)
        }
    }

    private fun showVoiceTapCatcher() {
        if (voiceTapCatcher != null) return
        try {
            val catcher = View(this).apply {
                setBackgroundColor(0x01FFFFFF)
                isClickable = true
                isFocusable = false
                setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_DOWN) {
                        maybeCancelVoiceFromUserTap("tap")
                        true
                    } else {
                        true
                    }
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            getSystemService(WindowManager::class.java).addView(catcher, params)
            voiceTapCatcher = catcher
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to show voice tap-catcher", t)
        }
    }

    private fun hideVoiceTapCatcher() {
        val catcher = voiceTapCatcher ?: return
        try {
            getSystemService(WindowManager::class.java).removeView(catcher)
        } catch (_: Throwable) { }
        voiceTapCatcher = null
    }

    private fun debounceVoice(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (key == lastDebouncedVoiceKey && now - lastDebouncedVoiceAtMs < 1500L) {
            VoiceDebugLog.log("debounced repeat: $key")
            return true
        }
        lastDebouncedVoiceKey = key
        lastDebouncedVoiceAtMs = now
        return false
    }

    /**
     * REQ-V22: "$facility Training" — one-shot jump into a facility's training
     * sub-screen. Reuses the existing arm-tap/confirm-tap pair (pauseSweepAt +
     * confirmFacilitySelection) back-to-back with a short gap, rather than
     * waiting on a second utterance — this command is the single-utterance
     * equivalent of speaking a facility name twice, not a new gesture pattern.
     * Bypasses VoiceFacilitySelection entirely (clearing any unrelated armed
     * state first) since this is direct and immediate, not arm-then-wait.
     */
    private fun dispatchFacilityTrainingUtterance(facilityIndex: Int) {
        if (!isInUma) return
        voiceFacilitySelection.clear()
        pauseSweepAt(facilityIndex)
        handler.postDelayed({ confirmFacilitySelection(facilityIndex) }, FACILITY_TRAINING_CONFIRM_GAP_MS)
    }

    /**
     * REQ-V23 (narrow case): capture the current screen and tap [label] via OCR
     * bounding box (findAndTapText) if it's actually visible right now — no
     * fixed coordinate, no assumption about which screen the user is on. If the
     * label isn't found (wrong screen, or OCR missed it), this just logs and
     * does nothing rather than guessing a fallback tap.
     */
    private fun dispatchHubButtonUtterance(label: String) {
        if (!isInUma) return
        val myGen = ++actionGeneration
        captureAndAnalyzeScreen { _, _ ->
            if (!canContinue(myGen)) return@captureAndAnalyzeScreen
            val ok = findAndTapText(myGen, label, "voice hub button ($label)")
            VoiceDebugLog.log(if (ok) "hub button \"$label\" tapped" else "hub button \"$label\" not found on screen")
        }
    }

    private fun dispatchFacilityUtterance(facilityIndex: Int) {

        val now = System.currentTimeMillis()
        when (val action = voiceFacilitySelection.onFacilityUtterance(facilityIndex, now)) {
            is VoiceFacilitySelection.Action.Arm -> {
                VoiceDebugLog.log("ARM: ${FacilityVocabulary.facilityNames[action.facilityIndex]}")
                pauseSweepAt(action.facilityIndex)
            }
            is VoiceFacilitySelection.Action.ReArm -> {
                VoiceDebugLog.log("RE-ARM: ${FacilityVocabulary.facilityNames[action.facilityIndex]}")
                pauseSweepAt(action.facilityIndex)
            }
            is VoiceFacilitySelection.Action.Confirm -> {
                VoiceDebugLog.log("CONFIRM: ${FacilityVocabulary.facilityNames[action.facilityIndex]}")
                confirmFacilitySelection(action.facilityIndex)
            }
        }
    }

    /**
     * REQ-A22/V12 "arm" step: interrupt whatever the sweep is doing (bumping the
     * generation halts the in-flight chained gesture at its next segment check)
     * and hold on the named facility — rewinding to it if the sweep had already
     * moved past it. If not confirmed within the confirm window, the sweep
     * resumes rather than sitting frozen (see resumeSweepAfterVoiceTimeout).
     *
     * REQ-M11: taps by OCR text (findAndTapText), not fixed window-fraction
     * coordinates. Observed on-device: the old fixed fraction was calibrated
     * against the wrong screen entirely (the hub's Infirmary/Recreation/Races
     * row, not this — the actual training facility-selection sub-screen, which
     * a fixed guess had never actually been captured against). Text-anchored
     * lookup is the same fix already proven for REQ-V22/V23; a fresh capture
     * is required immediately before the tap since findAndTapText reads
     * whatever OCR pass most recently populated lastOcrVisionText.
     */
    private fun pauseSweepAt(facilityIndex: Int) {
        if (!isInUma) return
        val myGen = ++actionGeneration
        val label = FacilityVocabulary.facilityNames[facilityIndex]
        Log.i(TAG, "Voice: pausing sweep on $label")
        captureAndAnalyzeScreen { _, _ ->
            if (!canContinue(myGen)) {
                VoiceDebugLog.log("pause aborted: superseded or left the game mid-capture")
                voiceFacilitySelection.clear()
                return@captureAndAnalyzeScreen
            }
            val ok = findAndTapText(myGen, label, "voice pause")
            if (ok) {
                VoiceDebugLog.log("pause: tapped \"$label\" to arm it")
                handler.removeCallbacks(voiceResumeRunnable)
                handler.postDelayed(voiceResumeRunnable, UserSettings.getVoiceConfirmWindowMs())
            } else {
                // Geometry/text unavailable right now (e.g. mid screen-transition,
                // or not actually on the facility-selection screen) — the caller
                // already transitioned VoiceFacilitySelection to armed before this
                // ran, but with no hold dispatched and no resume timer scheduled
                // that would strand the state machine. Clear it instead so the
                // next utterance starts a clean arm rather than an unintended
                // confirm.
                Log.w(TAG, "Voice pause aborted: \"$label\" not found on screen")
                VoiceDebugLog.log("pause aborted: \"$label\" not found on screen")
                voiceFacilitySelection.clear()
            }
        }
    }

    /**
     * REQ-A22/V12 "confirm" step. REQ-A9 keeps the sweep itself preview-only
     * (hover, never tap) — committing a facility is a distinct, deliberate tap,
     * separate from the hover gesture that armed it (REQ-A2's hover-safety
     * discipline: press and tap stay mechanically distinct).
     *
     * REQ-M11: same OCR-text-lookup migration as pauseSweepAt above.
     */
    private fun confirmFacilitySelection(facilityIndex: Int) {
        if (!isInUma) return
        handler.removeCallbacks(voiceResumeRunnable)
        val myGen = ++actionGeneration
        val label = FacilityVocabulary.facilityNames[facilityIndex]
        Log.i(TAG, "Voice: confirming $label")
        captureAndAnalyzeScreen { _, _ ->
            if (!canContinue(myGen)) {
                VoiceDebugLog.log("confirm aborted: superseded or left the game mid-capture")
                return@captureAndAnalyzeScreen
            }
            val ok = findAndTapText(myGen, label, "voice confirm")
            VoiceDebugLog.log(if (ok) "confirm: tapped \"$label\"" else "confirm: \"$label\" not found on screen")
        }
    }

    /** REQ-A22: an expired arm resumes sweeping rather than leaving the screen paused. */
    private fun resumeSweepAfterVoiceTimeout() {
        voiceFacilitySelection.clear()
        if (isInUma && sweepEnabled) {
            Log.i(TAG, "Voice: confirm window expired, resuming sweep")
            VoiceDebugLog.log("confirm window expired — resuming sweep")
            performTrainingSweepOnce(captureFirst = false)
        }
    }

    /** REQ-A19/A20/A21/A26: Macro command execution. */
    private fun executeMacroCommand(cmd: MacroCommand, quick: Boolean = false) {
        when (cmd) {
            MacroCommand.START_AUTO_RUN -> executeMacro(AutoRunMacros.startCareer, MacroMode.STEP_ONLY)
            MacroCommand.START_AUTO_RUN_DEFAULTS -> executeMacro(AutoRunMacros.startCareer, MacroMode.DEFAULTS)
            MacroCommand.START_AUTO_RUN_RECORDING -> executeMacro(AutoRunMacros.startCareer, MacroMode.RECORDING_DEFAULTS)
            MacroCommand.FINISH_AUTO_RUN -> executeMacro(AutoRunMacros.finishCareer, MacroMode.STEP_ONLY, quick)
            MacroCommand.SUPER_SKIP -> performSuperSkip()
            MacroCommand.START_SWEEP -> {
                if (!sweepEnabled) setSweepEnabled(true)
                performTrainingSweepOnce()
            }
            MacroCommand.TOGGLE_SWEEP -> setSweepEnabled(!sweepEnabled)
        }
    }

    /** REQ-A26: Super skip cycles Skip button to max speed level. */
    private fun performSuperSkip() {
        if (!isInUma) return
        val myGen = ++actionGeneration
        val win = gameWindowBounds() ?: return
        val skipX = (win.left + win.width() * 0.88f)
        val skipY = (win.top + win.height() * 0.90f)
        Log.i(TAG, "Super skip: advancing Skip control to max speed at ($skipX, $skipY)")
        VoiceDebugLog.log("super skip: advancing Skip control to max speed")

        fun tapSkip(remainingTaps: Int) {
            if (!isInUma || actionGeneration != myGen || remainingTaps <= 0) return
            val path = Path().apply { moveTo(skipX, skipY) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (remainingTaps > 1) {
                        handler.postDelayed({ tapSkip(remainingTaps - 1) }, 400L)
                    }
                }
            }, null)
        }
        tapSkip(2) // 2 taps cycles Off -> ▶ -> ▶▶
    }

    private fun executeMacro(macro: MacroDefinition, mode: MacroMode, quick: Boolean = false) {
        if (!isInUma) return
        val myGen = ++actionGeneration
        Log.i(TAG, "Executing macro ${macro.name} in mode $mode quick=$quick")
        VoiceDebugLog.log("macro start: ${macro.name} mode=$mode${if (quick) " (quickly)" else ""}")
        // Set once per run, read by the single step (CompleteCareerCheckpoint)
        // that currently needs it — an instance field rather than threading a
        // new parameter through every macroTick/retryOrGiveUp recursive call
        // site, safe because only one macro runs at a time (generation-guarded).
        currentMacroQuick = quick
        macroTick(myGen, macro, mode, android.os.SystemClock.elapsedRealtime(), 0, 0, 0L)
    }

    /**
     * One step of a running macro (REQ-A19/A20/A21): capture the current screen,
     * find the first matching MacroStep, act on it, and — for steps that advance
     * the screen — schedule the next tick. Every re-entry re-checks [canContinue]
     * (REQ-SF7): a superseding command or leaving the game stops the chain dead,
     * it never just keeps ticking against whatever is now on screen.
     *
     * [retryCount] covers transient "nothing matched yet" outcomes — a capture that
     * lands mid-transition (e.g. the instant a loading screen's progress bar hits
     * 100% but the next screen hasn't composited yet) is not the same as a genuinely
     * unrecognised screen, and retrying briefly avoids stopping a macro run one
     * frame early. It resets to 0 on every successful step; real stops (Terminal,
     * NEEDS_USER, ABORTED, EXHAUSTED) are unaffected by it. [retryAnchorMs] is the
     * elapsedRealtime of the *first* failure in the current retry streak — each
     * retry's delay is computed against that fixed anchor (MACRO_RETRY_DELAYS_MS
     * entries are offsets from the streak's start, not from each other), so a
     * slow capture on retry 2 doesn't push every later retry back by the same
     * amount.
     */
    private fun macroTick(
        gen: Int,
        macro: MacroDefinition,
        mode: MacroMode,
        startedAtMs: Long,
        stepCount: Int,
        retryCount: Int,
        retryAnchorMs: Long
    ) {
        if (!canContinue(gen)) {
            Log.i(TAG, "macro ${macro.name}: ABORTED (superseded or left the game)")
            VoiceDebugLog.log("macro ${macro.name}: ABORTED")
            return
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAtMs
        if (elapsed > macro.maxDurationMs || stepCount >= macro.maxSteps) {
            Log.w(TAG, "macro ${macro.name}: EXHAUSTED (steps=$stepCount elapsed=${elapsed}ms)")
            VoiceDebugLog.log("macro ${macro.name}: EXHAUSTED")
            return
        }
        captureAndAnalyzeScreen { text, _ ->
            if (!canContinue(gen)) {
                VoiceDebugLog.log("macro ${macro.name}: ABORTED mid-capture")
                return@captureAndAnalyzeScreen
            }
            val step = macro.steps.firstOrNull { it.matches(text) }
            if (step == null) {
                retryOrGiveUp(gen, macro, mode, startedAtMs, stepCount, retryCount, retryAnchorMs, "UNRECOGNISED_SCREEN — no step matched")
                return@captureAndAnalyzeScreen
            }
            VoiceDebugLog.log("macro ${macro.name}: step \"${step.name}\" matched")
            when (val action = step.action) {
                is MacroAction.Terminal -> {
                    Log.i(TAG, "macro ${macro.name}: COMPLETED")
                    VoiceDebugLog.log("macro ${macro.name}: COMPLETED")
                }
                is MacroAction.Wait -> {
                    // No tap, no dispatch — just recognized as "still loading."
                    // Consumes a stepCount (bounded by maxSteps) so a screen that
                    // never leaves the loading state still can't stall forever, but
                    // does not touch the UNRECOGNISED_SCREEN retry budget.
                    handler.postDelayed(
                        { macroTick(gen, macro, mode, startedAtMs, stepCount + 1, 0, 0L) },
                        MACRO_STEP_SETTLE_MS
                    )
                }
                is MacroAction.TapText -> {
                    if (findAndTapText(gen, action.text, "macro:${step.name}")) {
                        handler.postDelayed(
                            { macroTick(gen, macro, mode, startedAtMs, stepCount + 1, 0, 0L) },
                            MACRO_STEP_SETTLE_MS
                        )
                    } else {
                        retryOrGiveUp(
                            gen, macro, mode, startedAtMs, stepCount, retryCount, retryAnchorMs,
                            "tap target \"${action.text}\" not found for step \"${step.name}\""
                        )
                    }
                }
                is MacroAction.TapAnyText -> {
                    // findAndTapText both searches and taps; firstOrNull stops
                    // at the first candidate actually found, so at most one
                    // tap is dispatched even though multiple texts are tried.
                    val found = action.candidates.any { candidate ->
                        findAndTapText(gen, candidate, "macro:${step.name} ($candidate)")
                    }
                    if (found) {
                        handler.postDelayed(
                            { macroTick(gen, macro, mode, startedAtMs, stepCount + 1, 0, 0L) },
                            MACRO_STEP_SETTLE_MS
                        )
                    } else {
                        retryOrGiveUp(
                            gen, macro, mode, startedAtMs, stepCount, retryCount, retryAnchorMs,
                            "none of ${action.candidates} found for step \"${step.name}\""
                        )
                    }
                }
                is MacroAction.CompleteCareerCheckpoint -> {
                    val skillPts = Regex("skill pts\\D*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (skillPts > 0 && !currentMacroQuick) {
                        Log.i(TAG, "macro ${macro.name}: stopping at Complete Career — $skillPts unspent skill points")
                        VoiceDebugLog.log(
                            "macro ${macro.name}: $skillPts unspent skill points — stopping for you " +
                                "(say \"quickly\" to skip and finish anyway)"
                        )
                        // Falls through to the user — REQ-A27, not a failure, no retry.
                    } else if (findAndTapText(gen, "Complete Career", "macro:${step.name}")) {
                        handler.postDelayed(
                            { macroTick(gen, macro, mode, startedAtMs, stepCount + 1, 0, 0L) },
                            MACRO_STEP_SETTLE_MS
                        )
                    } else {
                        retryOrGiveUp(
                            gen, macro, mode, startedAtMs, stepCount, retryCount, retryAnchorMs,
                            "tap target \"Complete Career\" not found for step \"${step.name}\""
                        )
                    }
                }
                is MacroAction.TapWindowFraction -> {
                    val win = gameWindowBounds()
                    if (win == null) {
                        retryOrGiveUp(gen, macro, mode, startedAtMs, stepCount, retryCount, retryAnchorMs, "no game window bounds")
                        return@captureAndAnalyzeScreen
                    }
                    val x = win.left + win.width() * action.fx
                    val y = win.top + win.height() * action.fy
                    val ok = dispatchGuarded(
                        gen = gen,
                        points = listOf(x to y),
                        what = "macro:${step.name}",
                        build = {
                            val path = Path().apply { moveTo(x, y) }
                            GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
                                .build()
                        }
                    )
                    if (ok) {
                        handler.postDelayed(
                            { macroTick(gen, macro, mode, startedAtMs, stepCount + 1, 0, 0L) },
                            MACRO_STEP_SETTLE_MS
                        )
                    } else {
                        retryOrGiveUp(gen, macro, mode, startedAtMs, stepCount, retryCount, retryAnchorMs, "window-fraction tap blocked")
                    }
                }
                is MacroAction.Decision -> {
                    val key = action.key
                    val stored = if (mode.replaysDefaults) getLastDecision("macro.decision.$key") else null
                    if (stored != null) {
                        VoiceDebugLog.log("macro ${macro.name}: decision \"$key\" replaying stored default \"$stored\"")
                        if (findAndTapText(gen, stored, "macro:${step.name} (replay $key)")) {
                            if (mode.recordsDefaults) recordDecision("macro.decision.$key", stored)
                            handler.postDelayed(
                                { macroTick(gen, macro, mode, startedAtMs, stepCount + 1, 0, 0L) },
                                MACRO_STEP_SETTLE_MS
                            )
                        } else {
                            Log.w(TAG, "macro ${macro.name}: stored default \"$stored\" not on screen for \"$key\"")
                            VoiceDebugLog.log("macro ${macro.name}: NEEDS_USER — stored default not on screen for \"$key\"")
                        }
                    } else {
                        Log.i(TAG, "macro ${macro.name}: NEEDS_USER at decision \"$key\"")
                        VoiceDebugLog.log("macro ${macro.name}: NEEDS_USER — decision \"$key\" (say or tap your choice)")
                        if (mode.recordsDefaults) {
                            macroDecisionWaitKey = key
                            macroDecisionWaitGen = gen
                            macroDecisionWaitRecords = true
                            macroDecisionWaitMacroName = macro.name
                        }
                    }
                }
            }
        }
    }

    /**
     * Transient-failure retry against a fixed schedule (MACRO_RETRY_DELAYS_MS),
     * fast to start since screen transitions usually resolve within a beat or two,
     * and capped so a genuinely stuck macro still gives up in well under its
     * maxDurationMs ceiling. Delays are offsets from [retryAnchorMs] — the moment
     * the *first* failure in this streak was detected — not from each other, so a
     * slow capture on one retry doesn't push every subsequent retry's absolute
     * timing back by the same amount.
     */
    private fun retryOrGiveUp(
        gen: Int,
        macro: MacroDefinition,
        mode: MacroMode,
        startedAtMs: Long,
        stepCount: Int,
        retryCount: Int,
        retryAnchorMs: Long,
        reason: String
    ) {
        if (retryCount >= MACRO_RETRY_DELAYS_MS.size) {
            Log.w(TAG, "macro ${macro.name}: giving up after $retryCount retries — $reason")
            VoiceDebugLog.log("macro ${macro.name}: UNRECOGNISED_SCREEN (after $retryCount retries) — falling through to user")
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val anchor = if (retryCount == 0) now else retryAnchorMs
        val delay = (anchor + MACRO_RETRY_DELAYS_MS[retryCount] - now).coerceAtLeast(0L)
        VoiceDebugLog.log("macro ${macro.name}: retry ${retryCount + 1}/${MACRO_RETRY_DELAYS_MS.size} in ${delay}ms — $reason")
        handler.postDelayed(
            { macroTick(gen, macro, mode, startedAtMs, stepCount, retryCount + 1, anchor) },
            delay
        )
    }

    /**
     * Find text matching [text] in the most recent OCR pass and tap its bounding
     * box's center, gated by the same REQ-SF7 checks as every other dispatch.
     * Refuses to tap anything on AutoRunMacros.NEVER_TAP even if a fuzzy match
     * landed there — a macro must never be the reason "Delete Data" gets tapped
     * instead of "Resume" one row above it.
     *
     * REQ-M11: this used to search the AccessibilityNodeInfo tree — confirmed
     * on-device that Umamusume renders as a single opaque Unity SurfaceView with
     * no accessibility content inside it at all, so that search always found
     * nothing and every TapText step silently failed. The only source of "where
     * is this text on screen" is OCR's own bounding boxes, converted from the
     * (possibly downscaled) OCR bitmap's pixel space back to real screen
     * coordinates via the window bounds + scale factor captured at the same
     * moment as the OCR request that found this text.
     */
    private fun findAndTapText(gen: Int, text: String, what: String): Boolean {
        if (AutoRunMacros.isForbiddenTapTarget(text)) {
            Log.w(TAG, "Refusing macro tap on forbidden target: $text")
            return false
        }
        val visionText = lastOcrVisionText ?: return false
        val winBounds = lastOcrCaptureWinBounds ?: return false
        val scale = lastOcrScaleFactor

        // Exact line match first (a button label is normally its own whole
        // line), THEN substring-in-line, THEN whole-block substring. Observed
        // on-device: substring-only matching hit "training" incidentally inside
        // an unrelated hint sentence ("...keep on top of her training.") before
        // ever reaching the actual "Training" button label, because that
        // sentence's OCR block came first in reading order — tapping the wrong
        // thing while still reporting success. Preferring an exact line match
        // avoids exactly this: a real button label rarely shares its line with
        // other text, while incidental word-in-a-sentence hits do.
        var box: Rect? = null

        // Pass 1: exact line match.
        exact@ for (block in visionText.textBlocks) {
            for (line in block.lines) {
                if (line.text.trim().equals(text, ignoreCase = true) &&
                    !AutoRunMacros.isForbiddenTapTarget(line.text)
                ) {
                    box = line.boundingBox
                    break@exact
                }
            }
        }

        // Pass 2: substring-in-line, then whole-block substring.
        if (box == null) {
            substring@ for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    if (line.text.contains(text, ignoreCase = true) &&
                        !AutoRunMacros.isForbiddenTapTarget(line.text)
                    ) {
                        box = line.boundingBox
                        break@substring
                    }
                }
            }
        }
        if (box == null) {
            for (block in visionText.textBlocks) {
                if (block.text.contains(text, ignoreCase = true) && !AutoRunMacros.isForbiddenTapTarget(block.text)) {
                    box = block.boundingBox
                    break
                }
            }
        }
        val found = box ?: return false

        val cx = winBounds.left + found.exactCenterX() / scale
        val cy = winBounds.top + found.exactCenterY() / scale
        return dispatchGuarded(
            gen = gen,
            points = listOf(cx to cy),
            what = what,
            build = {
                val path = Path().apply { moveTo(cx, cy) }
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 160))
                    .build()
            }
        )
    }

    /**
     * Facility row geometry, relative to the GAME WINDOW never the display
     * (REQ-PL5). Fractions derived from the 1080x2400 fullscreen corpus.
     * Shared by the sweep gesture and by voice pause/confirm so both agree on
     * exactly where each facility is.
     */
    private fun facilityWindowPositions(): Pair<Rect, List<Pair<Int, Int>>>? {
        val win = gameWindowBounds()
        if (win == null || win.isEmpty) return null
        fun wx(fx: Float) = (win.left + win.width() * fx).toInt()
        fun wy(fy: Float) = (win.top + win.height() * fy).toInt()
        val y = wy(0.82f)
        val positions = listOf(
            wx(0.12f) to y, wx(0.30f) to y, wx(0.50f) to y, wx(0.68f) to y, wx(0.86f) to y
        )
        return win to positions
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
            val (win, positions) = facilityWindowPositions() ?: run {
                Log.w(TAG, "Sweep aborted: game window bounds unavailable")
                return@doSweep
            }
            Log.i(TAG, "Sweep geometry from game window $win")

            handler.post {
                if (!sweepCanContinue(myGen)) {
                    Log.i(TAG, "Sweep aborted before start (kill switch, left game, or superseded)")
                    return@post
                }

                val labels = arrayOf("Speed", "Stamina", "Power", "Guts", "Wit")

                // REQ-A22 extension: start facility + direction are user-configurable —
                // no reason a pass must always start at Speed heading right. The rest
                // are visited in a cyclic scan from the start index, wrapping across the
                // row so every facility is still covered exactly once per direction.
                val startIndex = UserSettings.getSweepStartFacilityIndex()
                val dirStep = if (UserSettings.getSweepStartDirectionRight()) 1 else -1
                val visitOrder = (0 until positions.size).map { (startIndex + it * dirStep).mod(positions.size) }
                // Physically, facilities sit in one fixed left-to-right row — a gap
                // between two consecutively-visited facilities must pass over whichever
                // physical facilities sit between them, even when the cyclic visit order
                // treats it as one direct hop (the wrap-around case, e.g. start=Power
                // heading right visits [Power,Guts,Wit,Speed,Stamina] — the Wit->Speed
                // gap physically crosses Guts and Power again). Expanding those into real
                // waypoints means every facility still gets its own local sinusoidal
                // deceleration hump when passed over, instead of getting swept across at
                // the wrap gap's single, undifferentiated peak speed — otherwise REQ-A22's
                // "lingers near each facility" pacing intent silently doesn't hold for
                // whichever facilities are only ever touched by the wrap.
                val orderedPositions = buildList {
                    add(positions[visitOrder[0]])
                    for (i in 0 until visitOrder.size - 1) {
                        val from = visitOrder[i]
                        val to = visitOrder[i + 1]
                        val step = if (to > from) 1 else -1
                        var idx = from
                        while (idx != to) {
                            idx += step
                            add(positions[idx])
                        }
                    }
                }
                Log.i(TAG, "Sweep visit order: ${visitOrder.map { labels[it] }}")

                val (x0, y0) = orderedPositions[0]
                val (xLast, yLast) = orderedPositions.last()
                // Slide clear of the row before lifting so press and release land on different
                // areas outside any facility button bounds, preventing accidental clicks (REQ-SF1).
                val releaseY = (yLast - (win.height() * 0.12f).toInt()).coerceAtLeast(win.top + 10)

                // Every point is bounds-checked before anything is dispatched (REQ-SF7).
                // Checks both possible release x-positions (x0 and xLast), not just
                // xLast: in DECELERATING_PASSES, passWaypoints reverses every pass, so
                // the actual dispatched release point lands at orderedPositions[0] for
                // even pass counts and orderedPositions.last() for odd ones — checking
                // only xLast silently skipped the even-count case.
                val allPoints = orderedPositions.map { (px, py) -> px.toFloat() to py.toFloat() } +
                    listOf(xLast.toFloat() to releaseY.toFloat(), x0.toFloat() to releaseY.toFloat())
                val bounds = gameWindowBounds()
                if (bounds == null || allPoints.any { !bounds.contains(it.first.toInt(), it.second.toInt()) }) {
                    Log.w(TAG, "Sweep aborted: a planned point falls outside game window $bounds")
                    return@post
                }

                data class Segment(val x: Float, val y: Float, val durationMs: Long)

                // REQ-A22: the sweep period paces visual comfort, not a comprehension
                // deadline (selection resolves by facility identity, never by what the
                // sweep happens to be highlighting) — so speed is free to vary rather
                // than moving at one uniform rate. Steps are spaced EVENLY ON SCREEN;
                // for SINUSOIDAL/DECELERATING_PASSES it's the per-step *duration* that
                // varies, driven by a speed profile v(u) = sin(pi*u) over each gap's
                // spatial fraction u in (0,1) — slow near a facility (lingering), fastest
                // in the open space between. Gap time is allocated proportional to that
                // gap's screen distance so speed stays consistent across uneven gaps
                // (the wrap-around wraps across a wider span than the others).
                val mode = UserSettings.getSweepPacingMode()
                val subStepsPerGap = 24
                // GestureDescription.getMaxGestureDuration() is ~60000ms; DECELERATING_PASSES's
                // periodMs = basePeriodMs * slowdown^p compounds fast enough that UI-reachable
                // slider combinations (period near 20000ms, slowdown near 3.0x, pass count 5-6)
                // can push a single segment's duration into the hundreds of thousands of ms,
                // which StrokeDescription's constructor rejects with IllegalArgumentException.
                // Every segment duration is capped well under that ceiling, regardless of pass.
                val maxSegmentDurationMs = 55_000L

                fun buildPassSegments(waypoints: List<Pair<Int, Int>>, periodMs: Long): List<Segment> {
                    val gaps = waypoints.size - 1
                    val gapDistances = (0 until gaps).map { g ->
                        val (fx, fy) = waypoints[g]
                        val (tx, ty) = waypoints[g + 1]
                        kotlin.math.hypot((tx - fx).toDouble(), (ty - fy).toDouble())
                    }
                    val totalDistance = gapDistances.sum().coerceAtLeast(1.0)

                    val segs = mutableListOf<Segment>()
                    for (g in 0 until gaps) {
                        val (fx, fy) = waypoints[g]
                        val (tx, ty) = waypoints[g + 1]
                        val gapDurationMs = periodMs * (gapDistances[g] / totalDistance)

                        if (mode == UserSettings.SweepPacingMode.LINEAR) {
                            segs += Segment(tx.toFloat(), ty.toFloat(), gapDurationMs.toLong().coerceIn(1L, maxSegmentDurationMs))
                            continue
                        }

                        // Time each equal-width spatial bin spends is inversely proportional
                        // to speed at its midpoint: slow bins (near u=0/1) get more time.
                        val weights = (1..subStepsPerGap).map { k ->
                            val uMid = (k - 0.5f) / subStepsPerGap
                            1f / kotlin.math.sin(Math.PI.toFloat() * uMid)
                        }
                        val weightSum = weights.sum()
                        for (s in 1..subStepsPerGap) {
                            val u = s.toFloat() / subStepsPerGap  // equal spatial step
                            val px = fx + (tx - fx) * u
                            val py = fy + (ty - fy) * u
                            val stepDurationMs = (gapDurationMs * weights[s - 1] / weightSum).toLong().coerceIn(1L, maxSegmentDurationMs)
                            segs += Segment(px, py, stepDurationMs)
                        }
                    }
                    return segs
                }

                val basePeriodMs = UserSettings.getSweepPeriodMs()
                val passCount = if (mode == UserSettings.SweepPacingMode.DECELERATING_PASSES) {
                    UserSettings.getSweepPassCount()
                } else 1
                val slowdown = UserSettings.getSweepPassSlowdownFactor()

                // DECELERATING_PASSES chains every pass into ONE continuous, never-
                // lifting touch — a real swipe doesn't teleport or restart between
                // passes — bouncing back and forth across the row while each successive
                // pass's period grows by `slowdown`. That reads as one motion
                // continuously losing energy, like a rolling wheel slowing down, rather
                // than a series of discrete stop-and-restart passes. Only the very last
                // segment of the very last pass lifts the finger.
                val allSegments = mutableListOf<Segment>()
                var passWaypoints = orderedPositions
                var lastPoint = passWaypoints.last()
                for (p in 0 until passCount) {
                    val periodMs = (basePeriodMs * Math.pow(slowdown.toDouble(), p.toDouble())).toLong()
                    allSegments += buildPassSegments(passWaypoints, periodMs)
                    lastPoint = passWaypoints.last()
                    passWaypoints = passWaypoints.reversed()
                }
                // Slide clear above the facility row before lifting — a quick, uniform
                // release motion, not part of the eased "reading" pacing above.
                allSegments += Segment(lastPoint.first.toFloat(), releaseY.toFloat(), SWEEP_RELEASE_MS)

                Log.i(TAG, "Dispatching $mode sweep drag: ${orderedPositions.size} facilities, " +
                    "$passCount pass(es), ${allSegments.size} segments, base period=${basePeriodMs}ms")

                fun dispatchSegment(index: Int, prevStroke: GestureDescription.StrokeDescription?) {
                    if (!sweepCanContinue(myGen)) {
                        Log.i(TAG, "Sweep aborted mid-sequence (kill switch, left game, or superseded)")
                        return
                    }
                    val seg = allSegments[index]
                    val willContinue = index < allSegments.size - 1
                    val segPath = Path()
                    val stroke = if (prevStroke == null) {
                        segPath.moveTo(x0.toFloat(), y0.toFloat())
                        segPath.lineTo(seg.x, seg.y)
                        GestureDescription.StrokeDescription(segPath, 0L, seg.durationMs, willContinue)
                    } else {
                        segPath.moveTo(
                            if (index == 0) x0.toFloat() else allSegments[index - 1].x,
                            if (index == 0) y0.toFloat() else allSegments[index - 1].y
                        )
                        segPath.lineTo(seg.x, seg.y)
                        prevStroke.continueStroke(segPath, 0L, seg.durationMs, willContinue)
                    }

                    val ok = dispatchGesture(
                        GestureDescription.Builder().addStroke(stroke).build(),
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription) {
                                if (willContinue) {
                                    dispatchSegment(index + 1, stroke)
                                } else {
                                    Log.i(TAG, "Sweep drag COMPLETED — finger released clear of facility buttons")
                                    if (sweepCanContinue(myGen)) {
                                        handler.postDelayed({
                                            if (sweepCanContinue(myGen)) captureAndAnalyzeScreen { _, _ -> }
                                        }, 350)
                                    }
                                    // REQ-A23: the sweep restarts for another pass only when a fresh
                                    // continuation signal (voice, not touch-screen input) has arrived within
                                    // the window — silence stops it, so this stays a chain of explicit
                                    // signals, not a plain self-loop (REQ-A5).
                                    if (sweepCanContinue(myGen) && UserSettings.getSweepRestartOnSignalEnabled()) {
                                        val sinceSignal = System.currentTimeMillis() - lastVoiceHeartbeatAtMs
                                        if (sinceSignal <= UserSettings.getSweepHeartbeatWindowMs()) {
                                            handler.postDelayed({
                                                if (sweepCanContinue(myGen)) performTrainingSweepOnce(captureFirst = false)
                                            }, 350)
                                        } else {
                                            Log.i(TAG, "Sweep restart-on-signal: no continuation signal within window, stopping")
                                        }
                                    }
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription) {
                                Log.w(TAG, "Sweep drag CANCELLED by OS at segment $index")
                            }
                        },
                        null
                    )
                    if (!ok) {
                        Log.w(TAG, "Sweep drag failed to dispatch at segment $index")
                    }
                }

                dispatchSegment(0, null)
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

        // REQ-M11: was an AccessibilityNodeInfo tree search — always found nothing,
        // since the game exposes no node content, so this path silently degraded to
        // the blind center-tap fallback on every single invocation. findAndTapText
        // (OCR bounding boxes) is the only mechanism that can actually locate these
        // labels on screen. Order reflects common corpus patterns for safe advance;
        // first label whose text is actually found and tapped wins.
        val priority = listOf("next", "confirm", "race", "enter", "continue", "ok", "done", "finish", "skip", "results", "replay", "watch", "close")
        for (label in priority) {
            if (findAndTapText(myGen, label, "no-choice advance ($label)")) {
                Log.i(TAG, "No-choice advance: tapped \"$label\"")
                return
            }
        }

        Log.i(TAG, "No obvious advance button found via OCR. Falling back to center tap (risky).")
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
                        .addStroke(GestureDescription.StrokeDescription(path, 0, UserSettings.getListScrollPeriodMs()))
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
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Log.i(TAG, "Overlay cell clicked: text=$glyph")
                onTap()
            }
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
            // REQ-A31: AudioLevelView draws a live RMS trace under the same
            // glyph/background this cell has always shown — same click/sizing
            // contract as makeCell(), not a separate control.
            val handle = AudioLevelView(this).apply {
                text = GLYPH_IDLE
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(CELL_IDLE_COLOR)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Log.i(TAG, "Overlay cell clicked: text=$text")
                    toggleOverlayExpanded()
                }
            }
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

            // REQ-V20: valid-commands/current-screen panel. Toggle cell sizes like
            // every other cell (fixed square, applyOverlayGeometry); the panel it
            // reveals is a separate wide multi-line view, not squeezed into that
            // same square, so it's actually readable.
            val phraseCell = makeCell(GLYPH_PHRASES) {
                phrasePanelExpanded = !phrasePanelExpanded
                refreshOverlay()
            }
            val phrasePanel = TextView(this).apply {
                gravity = Gravity.START
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(CELL_IDLE_COLOR)
                setPadding(12, 8, 12, 8)
                textSize = 11f
                visibility = View.GONE
            }

            root.addView(handle)
            root.addView(sweepCell)
            root.addView(voiceCell)
            root.addView(readCell)
            root.addView(runCell)
            root.addView(phraseCell)
            root.addView(phrasePanel)

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
            overlayPhraseCell = phraseCell
            overlayPhrasePanel = phrasePanel
            overlayParams = params

            applyOverlayGeometry()
            wm.addView(root, params)
            refreshOverlay()
            Log.i(TAG, "Overlay shown at x=${params.x}, y=${params.y}")
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

        for (v in listOfNotNull(overlayHandle, overlaySweepCell, overlayVoiceCell, overlayReadCell, overlayRunCell, overlayPhraseCell)) {
            val lp = LinearLayout.LayoutParams(cell, cell)
            lp.topMargin = CELL_GAP_PX
            v.layoutParams = lp
            // COMPLEX_UNIT_PX: the cell size is already in pixels, and the default
            // textSize setter interprets sp, which over-scales by the display density.
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cell * TEXT_SIZE_RATIO)
        }
        // REQ-V20: the phrase panel is deliberately NOT squeezed into the fixed
        // square cell size above — it needs real width to be readable.
        overlayPhrasePanel?.layoutParams = LinearLayout.LayoutParams(
            (220 * resources.displayMetrics.density).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = CELL_GAP_PX }

        if (osButton != null) {
            // Dock under the button, aligned to its edge, so the two read as one
            // cluster instead of two competing corners.
            params.x = osButton.left
            params.y = osButton.bottom + DOCK_GAP_PX
            lastOsButtonBounds = Rect(osButton)
            Log.i(TAG, "Overlay docked beneath OS button: x=${params.x}, y=${params.y}, cell=$cell, osButton=$osButton")
        } else {
            // Shortcut not enabled (no floating button) — fall back to a top-right rest
            // position that still avoids the status bar.
            val dm = resources.displayMetrics
            params.x = dm.widthPixels - cell - DOCK_GAP_PX
            params.y = (dm.heightPixels * 0.10f).toInt()
            lastOsButtonBounds = null
            Log.i(TAG, "Overlay fallback position: x=${params.x}, y=${params.y}, cell=$cell")
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
        hideVoiceTapCatcher()
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
        overlayPhraseCell = null
        overlayPhrasePanel = null
        phrasePanelExpanded = false
        overlayParams = null
        overlayExpanded = false
        lastOsButtonBounds = null
    }

    /**
     * REQ-V20: what the corpus would currently accept, gated by live state — not
     * a flat dump of the whole dictionary. Also carries the raw OCR text of the
     * last capture (there is no real screen classifier yet, OQ-49), labeled
     * honestly as raw text rather than implying a classified screen name.
     */
    private fun computeValidCommandsSnapshot(): String {
        val screenExcerpt = lastOcrText.take(80).replace("\n", " / ").ifBlank { "(no capture yet)" }
        val sb = StringBuilder("Screen (raw OCR): $screenExcerpt\n\n")
        if (!voiceEnabled) {
            sb.append("Voice is OFF")
            return sb.toString()
        }
        if (!isInUma) {
            sb.append("Valid: (not in Umamusume)")
            return sb.toString()
        }
        // Man-page style: [x] optional, x|y alternatives, x* repeatable.
        sb.append("Valid now:\n")
        val armed = voiceFacilitySelection.currentlyArmed()
        if (armed != null) {
            sb.append("- ${FacilityVocabulary.facilityNames[armed]} (repeat to confirm)\n")
            sb.append("- cancel|oops|escape|abort\n")
        } else {
            sb.append("- speed|stamina|power|guts|wit\n")
            sb.append("- {speed|stamina|power|guts|wit} training\n")
            sb.append("- training|facilities\n")
        }
        if (sweepEnabled) sb.append("- resume|continue\n")
        sb.append("- [auto] sweep\n")
        sb.append("- start|resume|continue {auto run|career}\n")
        sb.append("- [quickly] finish|complete {auto run|career} [quickly]\n")
        sb.append("- stop listening|mute|voice off\n")
        return sb.toString()
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

        // REQ-A31: voice armed but the recognizer hasn't produced its first
        // onRmsChanged sample yet reads as orange rather than green — applies
        // to both the per-cell voice indicator and (see below) the collapsed
        // handle, so the two views of voice state never disagree.
        val voiceWarming = voiceEnabled && !voiceWarmedUp
        overlaySweepCell?.setBackgroundColor(if (sweepEnabled) CELL_ON_COLOR else CELL_IDLE_COLOR)
        overlayVoiceCell?.setBackgroundColor(
            when {
                !voiceEnabled -> CELL_IDLE_COLOR
                voiceWarming -> CELL_WARMING_COLOR
                else -> CELL_ON_COLOR
            }
        )
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
        overlayPhraseCell?.visibility = childVisibility
        // REQ-V20: the panel needs both the cluster open AND its own toggle on.
        // Text only overwritten here (not cleared elsewhere), so it stays sticky
        // on whatever it last showed rather than flashing blank between screens.
        overlayPhrasePanel?.visibility =
            if (overlayExpanded && phrasePanelExpanded) View.VISIBLE else View.GONE
        if (overlayExpanded && phrasePanelExpanded) {
            overlayPhrasePanel?.text = computeValidCommandsSnapshot()
        }
        // Run is only meaningful once sweep is armed; show that rather than failing
        // silently when tapped.
        overlayRunCell?.text = if (sweepEnabled) GLYPH_RUN else GLYPH_RUN_BLOCKED
        overlayRunCell?.setBackgroundColor(if (sweepEnabled) CELL_ON_COLOR else CELL_IDLE_COLOR)

        // The handle itself doubles as the at-a-glance status indicator so the
        // collapsed state still communicates whether anything is armed.
        val armed = sweepEnabled || voiceEnabled
        overlayHandle?.text = if (armed) GLYPH_ARMED else GLYPH_IDLE
        overlayHandle?.setBackgroundColor(
            when {
                !armed -> CELL_IDLE_COLOR
                // Sweep being armed is itself "definitely armed" (sweep needs
                // no warmup), so only show the aggregate handle as warming
                // when voice is the sole reason anything is armed at all.
                voiceWarming && !sweepEnabled -> CELL_WARMING_COLOR
                else -> CELL_ON_COLOR
            }
        )
    }

}
