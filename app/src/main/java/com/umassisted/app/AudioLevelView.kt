package com.umassisted.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.widget.TextView

/**
 * REQ-A31: the overlay's collapsed handle, live — a diagnostic instrument,
 * not a decoration. Purpose is to make two things obvious to the user at a
 * glance: (1) is the mic actually armed right now (vs. sitting in a restart
 * gap), and (2) did my last utterance get heard-and-matched, heard-and-
 * rejected, or not heard at all. Subclasses TextView (not View) deliberately:
 * the existing handle code sets .text/.setTextColor/.setBackgroundColor/
 * .setTextSize on it already (REQ-A29's armed/idle/pending glyph state), and
 * this must drop into that unchanged — everything here draws underneath the
 * existing glyph.
 *
 * Three layers, back to front:
 * 1. Volume (best-effort, from raw PCM — REQ-A31 second layer): a dim
 *    mirrored waveform, amplitude only. Purely informational texture, not
 *    the primary read. (A parallel "tone"/zero-crossing-rate channel was
 *    built and then deliberately cut: it didn't answer any diagnostic
 *    question a user actually has — not a reliable speech-vs-noise signal,
 *    not calibrated against anything real — and showing an unvalidated
 *    number risks looking meaningful when it isn't. Volume stayed because
 *    "is there loud enough signal right now" is a real question.)
 * 2. RMS trace (the original layer): green while an STT session is actively
 *    listening, dim gray during a restart gap — the "am I even being heard
 *    right now" signal.
 * 3. Command-result charms: a small marker dropped at the moment a voice
 *    command resolves — green for accepted, red for rejected (heard, but
 *    matched nothing) — so "I said something and nothing happened" is
 *    visually distinguishable from "the mic never heard me" (a gray gap,
 *    layer 2) at a glance, without reading logs.
 *
 * REQ-A31 (windowing): every layer covers a configurable *time* window
 * ([windowMs]) of most recent samples — what's shown is bounded by duration,
 * not by a sample count, and pans in real time regardless of how often the
 * underlying callbacks actually fire (untrustworthy — see below).
 *
 * Performance is the whole point of this design (REQ-A31's hard ceiling):
 * - Backing stores are fixed-capacity arrays — Kotlin/Android has no
 *   allocation-free growable ring buffer — sized generously enough that
 *   write-wrap can never overtake still-in-window data at MAX_WINDOW_MS,
 *   even at an assumed sample rate far above what's actually observed.
 *   Capacity is a structural safety margin, not the retention policy: what's
 *   drawn is decided purely by each sample's timestamp against [windowMs].
 * - invalidate() throttled to REDRAW_INTERVAL_MS for the high-frequency
 *   layers; charm markers invalidate immediately since they're rare.
 * - One Paint per stroke/marker kind, created once, never per-frame.
 * - onDraw walks backward from the newest sample only until it falls outside
 *   the window — cost tracks window content, not buffer capacity.
 */
class AudioLevelView(context: Context) : TextView(context) {

    companion object {
        const val DEFAULT_WINDOW_MS = 10_000L
        const val MIN_WINDOW_MS = 1_000L
        const val MAX_WINDOW_MS = 60_000L
        // Backing-store capacity: MAX_WINDOW_MS at an assumed ceiling rate of
        // 30 samples/sec — onRmsChanged/onBufferReceived have been observed
        // firing far below this — so write-wrap can never reach still-in-
        // window data even at the largest configurable window.
        private val CAPACITY = ((MAX_WINDOW_MS / 1000L) * 30L).toInt()
        private const val CHARM_CAPACITY = 32 // commands are rare relative to samples
        private const val REDRAW_INTERVAL_MS = 66L // ~15fps ceiling for the sample layers
        // Background layer's own window — deliberately much shorter than the
        // primary [windowMs], so it reads as a classic audio-editor waveform
        // (a fast-moving amplitude view of "right now") rather than panning
        // at the same slow rate as the long-window RMS trace behind it.
        private const val BACKGROUND_WINDOW_MS = 1_500L
        // onRmsChanged's typical range is roughly -2..10 on this recognizer;
        // clamped and normalized to 0..1 for drawing, not treated as calibrated dB.
        private const val RMS_FLOOR = -2f
        private const val RMS_CEIL = 10f
    }

    /** Most-recent-time span every layer covers. Configurable; clamped to sane bounds. */
    var windowMs: Long = DEFAULT_WINDOW_MS
        set(value) {
            field = value.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
            invalidate()
        }

    // Backs both layers — layer 1 (background waveform) is just this same
    // data, redrawn over BACKGROUND_WINDOW_MS instead of the long windowMs.
    // No separate signal needed: both read from onRmsChanged.
    private val rmsTimestamps = LongArray(CAPACITY)
    private val rmsSamples = FloatArray(CAPACITY)
    private val rmsActiveFlags = BooleanArray(CAPACITY)
    private var rmsWriteIndex = 0
    private var rmsFilled = 0

    // --- Layer 3: command-result charms ---
    private val charmTimestamps = LongArray(CHARM_CAPACITY)
    private val charmAccepted = BooleanArray(CHARM_CAPACITY)
    private var charmWriteIndex = 0
    private var charmFilled = 0

    private var lastInvalidateAtMs = 0L

    // Round caps/joins: with as few as 2-4 points per burst (onRmsChanged's
    // real firing rate), sharp square joins between segments read as jagged
    // angles rather than a waveform silhouette — rounding softens that for
    // free, no extra geometry or per-frame cost.
    private val activeTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4ADE80.toInt() // green — matches CELL_ON_COLOR family
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val inactiveTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x555A5A5A // dim gray — visually distinct "not listening" gap
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val volumePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF // faint white — background texture, not the primary read
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val charmAcceptedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4ADE80.toInt() // same green as an active/listening trace
        style = Paint.Style.FILL
    }
    private val charmRejectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE3494B.toInt() // red — heard, matched nothing
        style = Paint.Style.FILL
    }
    private val charmRadiusPx = 3f

    /**
     * Called from the main thread only (VoiceListener's RecognitionListener
     * callbacks already run on the Looper this overlay was created on).
     * [active] marks whether this sample was captured during a live STT
     * session (between onReadyForSpeech and onEndOfSpeech) — drawn in a
     * distinct color so a flat/gap stretch of the trace during a restart
     * window doesn't read as "silence while listening."
     */
    fun addSample(rms: Float, active: Boolean) {
        val norm = ((rms - RMS_FLOOR) / (RMS_CEIL - RMS_FLOOR)).coerceIn(0f, 1f)
        val now = SystemClock.uptimeMillis()
        rmsTimestamps[rmsWriteIndex] = now
        rmsSamples[rmsWriteIndex] = norm
        rmsActiveFlags[rmsWriteIndex] = active
        rmsWriteIndex = (rmsWriteIndex + 1) % CAPACITY
        if (rmsFilled < CAPACITY) rmsFilled++
        throttledInvalidate(now)
    }

    /**
     * REQ-A31 (charms): drop a marker at the moment a voice command resolves.
     * [accepted] true for any real match dispatched, false for heard-but-
     * unmatched (None/Ambiguous) — never called at all for "nothing heard,"
     * which is exactly what leaves the RMS layer showing a gray gap instead
     * of a charm, making the two failure modes visually distinct per REQ-A31.
     * Invalidates immediately, bypassing the sample-layer throttle — this is
     * a rare, meaningful event, not a firehose.
     */
    fun markCommandResult(accepted: Boolean) {
        charmTimestamps[charmWriteIndex] = SystemClock.uptimeMillis()
        charmAccepted[charmWriteIndex] = accepted
        charmWriteIndex = (charmWriteIndex + 1) % CHARM_CAPACITY
        if (charmFilled < CHARM_CAPACITY) charmFilled++
        invalidate()
    }

    private fun throttledInvalidate(now: Long) {
        if (now - lastInvalidateAtMs >= REDRAW_INTERVAL_MS) {
            lastInvalidateAtMs = now
            invalidate()
        }
    }

    /** Clears every layer back to flat/empty — called when voice is disarmed. */
    fun clearTrace() {
        rmsFilled = 0
        rmsWriteIndex = 0
        charmFilled = 0
        charmWriteIndex = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val cutoff = now - windowMs
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f

        // Layer 1 (background) disabled for now — built on the premise that a
        // separate short-window "what's happening right now" layer was worth
        // the complexity, but that premise didn't hold once it was clear no
        // extra audio capture was actually needed for the primary (layer 2)
        // trace either — both read from the same onRmsChanged data, so the
        // background layer never carried information the primary trace
        // didn't already show. Left in place (drawWaveformLayer below,
        // commented) rather than deleted, in case a real reason to revive it
        // shows up.
        // drawWaveformLayer(canvas, w, midY)

        // Layer 2 (primary): RMS, active/inactive colored per REQ-A31.
        if (rmsFilled > 1) {
            var prevX = -1f
            var prevY = midY
            var prevActive = false
            for (i in 0 until rmsFilled) {
                val idx = (rmsWriteIndex - 1 - i + CAPACITY * 2) % CAPACITY
                val ts = rmsTimestamps[idx]
                if (ts < cutoff) break
                val x = w * (ts - cutoff).toFloat() / windowMs
                val y = midY - rmsSamples[idx] * midY
                if (prevX >= 0f) {
                    canvas.drawLine(x, y, prevX, prevY, if (prevActive) activeTracePaint else inactiveTracePaint)
                }
                prevX = x
                prevY = y
                prevActive = rmsActiveFlags[idx]
            }
        }

        // Layer 3 (foreground): command-result charms. Bottom edge, clear of
        // the glyph (which sits centered/top per TextView gravity and is
        // drawn last, over everything) — previously hardcoded to ~4px from
        // the *top* regardless of view height, unlike every other layer
        // here, which almost certainly put it directly under the glyph.
        val charmY = h - charmRadiusPx - 1f
        for (i in 0 until charmFilled) {
            val idx = (charmWriteIndex - 1 - i + CHARM_CAPACITY * 2) % CHARM_CAPACITY
            val ts = charmTimestamps[idx]
            if (ts < cutoff) break
            val x = w * (ts - cutoff).toFloat() / windowMs
            canvas.drawCircle(x, charmY, charmRadiusPx, if (charmAccepted[idx]) charmAcceptedPaint else charmRejectedPaint)
        }

        // Glyph drawn last, on top of everything.
        super.onDraw(canvas)
    }

    /* Disabled — see the call site's comment in onDraw above.
    /**
     * Connected trace, amplitude only (same line technique as the primary
     * trace, not mirrored — mirroring a line this noisy doubles the visual
     * clutter without adding information), over the short, fixed
     * BACKGROUND_WINDOW_MS — distinct from the long-window trace drawn on
     * top of it by window length and color, not by rendering style. Same
     * underlying rms* data as the primary trace, just windowed differently —
     * no separate signal.
     *
     * Deliberately NOT disconnected vertical bars (a "classic audio-editor
     * waveform" look) — observed on-device: onRmsChanged is a coarse,
     * infrequent amplitude envelope (roughly 5-20 updates/sec, each capable
     * of swinging wildly from the last), not dense continuous PCM. A real
     * editor waveform's smooth silhouette comes from thousands of samples/
     * sec; rendering this sparse, noisy signal as isolated bars had no
     * neighbor-to-neighbor relationship to read, and looked like scattered
     * noise rather than a shape. Connecting the samples (same technique as
     * the primary trace) smooths the read even though the underlying values
     * jump around just as much — the primary trace was never confusing for
     * exactly this reason.
     *
     * X-axis stretches to the actual span of in-window samples, not a fixed
     * assumed BACKGROUND_WINDOW_MS-wide span — onRmsChanged fires sparsely
     * and in bursts, so most of the time the samples actually present don't
     * cover the full 1.5s window; scaling against the fixed window left the
     * line clustered in whatever narrow slice of width corresponded to when
     * they happened, with the rest of the pane blank (observed on-device:
     * the layer looked "worthless," not scaling to fill the display). Two
     * passes over the same fixed-size array, no allocation: first finds how
     * many samples fall in the window and their oldest timestamp, second
     * draws them stretched across [0, w].
     */
    private fun drawWaveformLayer(canvas: Canvas, w: Float, midY: Float) {
        if (rmsFilled <= 1) return
        val now = SystemClock.uptimeMillis()
        val cutoff = now - BACKGROUND_WINDOW_MS
        val newestIdx = (rmsWriteIndex - 1 + CAPACITY) % CAPACITY
        val newestTs = rmsTimestamps[newestIdx]
        if (newestTs < cutoff) return // nothing recent enough to show
        var oldestTs = newestTs
        var count = 0
        for (i in 0 until rmsFilled) {
            val idx = (rmsWriteIndex - 1 - i + CAPACITY * 2) % CAPACITY
            val ts = rmsTimestamps[idx]
            if (ts < cutoff) break
            oldestTs = ts
            count++
        }
        if (count <= 1) return
        val span = (newestTs - oldestTs).coerceAtLeast(1L).toFloat()
        var prevX = -1f
        var prevY = midY
        for (i in 0 until count) {
            val idx = (rmsWriteIndex - 1 - i + CAPACITY * 2) % CAPACITY
            val ts = rmsTimestamps[idx]
            val x = w * (ts - oldestTs).toFloat() / span
            val amplitude = rmsSamples[idx] * midY
            val yTop = midY - amplitude
            if (prevX >= 0f) canvas.drawLine(x, yTop, prevX, prevY, volumePaint)
            prevX = x
            prevY = yTop
        }
    }
    */
}
