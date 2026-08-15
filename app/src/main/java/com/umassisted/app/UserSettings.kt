package com.umassisted.app

import android.content.Context
import android.content.SharedPreferences

/**
 * User-configurable settings for the assist (REQ-A7/REQ-A10).
 *
 * All settings default to sensible values that work on a wide range of
 * motor abilities and reading speeds; the user can lengthen anything
 * that feels rushed. Defaults are tuned to be the _minimum_ comfortable
 * in the motivating case (a user with significant motor limitations
 * trying to play an idle-RPG); a user with typical motor ability can
 * keep defaults or increase them further.
 */
object UserSettings {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("umassisted_settings", Context.MODE_PRIVATE)
    }

    /**
     * Whether to mute STREAM_SYSTEM (the OEM recognition service's own start/
     * end chime, confirmed separate from game audio) for as long as voice is
     * armed. Default OFF: muting a whole system stream is a side effect a
     * user should opt into deliberately, not something imposed on them —
     * some devices/setups may want the audible cue (e.g. as a landmark for
     * knowing the mic is live) more than they want it silenced.
     */
    fun getVoiceMuteChimeEnabled(): Boolean =
        prefs.getBoolean("voice_mute_chime_enabled", true)

    fun setVoiceMuteChimeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("voice_mute_chime_enabled", enabled).apply()
    }

    /**
     * How long the recognizer should wait in silence before treating an
     * utterance as finished, in milliseconds — passed as
     * EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS /
     * EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS. User-
     * configurable rather than fixed: some on-device engines ignore these
     * extras and fall back to their own fixed floor (confirmed empirically
     * — one engine held to a ~5-6s internal timeout regardless of the value
     * sent), so raising it is not guaranteed to change anything on every
     * device, but it costs nothing to expose and helps on engines that do
     * honor it. Default 8000ms, a bit above that observed floor. Bounds:
     * [2000, 60000] ms — floor is long enough to finish a short phrase;
     * ceiling avoids an effectively-frozen mic session.
     */
    fun getVoiceSilenceTimeoutMs(): Long {
        val stored = prefs.getLong("voice_silence_timeout_ms", -1L)
        return if (stored > 0) stored else 8000L
    }

    fun setVoiceSilenceTimeoutMs(ms: Long) {
        val bounded = ms.coerceIn(2000L, 60000L)
        prefs.edit().putLong("voice_silence_timeout_ms", bounded).apply()
    }

    /**
     * Minimum sustained speech duration (in milliseconds) required before the
     * recognizer starts utterance evaluation — passed as
     * EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS. User-configurable: prevents
     * mic startup transients or low ambient noise floors from immediately
     * triggering onBeginningOfSpeech. Bounds: [0, 2000] ms. Default 300ms.
     */
    fun getVoiceMinSpeechLengthMs(): Long {
        val stored = prefs.getLong("voice_min_speech_length_ms", -1L)
        return if (stored >= 0) stored else 300L
    }

    fun setVoiceMinSpeechLengthMs(ms: Long) {
        val bounded = ms.coerceIn(0L, 2000L)
        prefs.edit().putLong("voice_min_speech_length_ms", bounded).apply()
    }

    /**
     * Full sweep period, in milliseconds (REQ-A9/REQ-A6, replaces the old
     * discrete dwell+slide pair). Selection is by voice, matched against
     * the facility's *name* rather than "whatever the sweep happens to be
     * highlighting right now" — so there is no moment a user must catch a
     * facility before it's gone. Speaking a name after the sweep has moved
     * past it still resolves correctly. That removes the WCAG-style timed-
     * content constraint the old dwell floor was grounded in: dwell was
     * never actually gating comprehension, so it doesn't need a
     * conservative floor.
     *
     * Position across the facilities is driven by a single sinusoid rather
     * than discrete highlight/pause/slide states: it naturally slows near
     * each facility (velocity approaches zero at the turning points) and
     * speeds up between them, which reads as "lingering" without a fixed
     * timed dwell window to defend. One knob — the period — replaces dwell,
     * slide, and duration.
     *
     * Default 8000ms (~2s per facility gap across the 5-facility pass) is a
     * comfortable, easily-tracked pace for the motivating user — closer to
     * the old dwell-heavy design's overall feel (~16s/pass) than a rushed
     * default would be, while still much shorter since there's no per-
     * facility pause to pay for. A user can speed it up for a quick glance
     * or slow it down further for more visual settling time. Bounds:
     * [500, 20000] ms. Floor is motor/visual-tracking comfort, not
     * comprehension — there is no minimum needed to "finish reading in
     * time."
     */
    fun getSweepPeriodMs(): Long {
        val stored = prefs.getLong("sweep_period_ms", -1L)
        return if (stored > 0) stored else 2500L
    }

    fun setSweepPeriodMs(ms: Long) {
        val bounded = ms.coerceIn(500L, 20000L)
        prefs.edit().putLong("sweep_period_ms", bounded).apply()
    }

    /**
     * List auto-scroll period (REQ-A16), in milliseconds. Same sinusoidal
     * treatment as the sweep: continuous easing rather than a per-item
     * dwell, and no comprehension-deadline floor since voice selection is
     * name-based and not tied to scroll position. Default matches the
     * sweep period for a unified feel.
     */
    fun getListScrollPeriodMs(): Long {
        val stored = prefs.getLong("list_scroll_period_ms", -1L)
        return if (stored > 0) stored else getSweepPeriodMs()
    }

    fun setListScrollPeriodMs(ms: Long) {
        val bounded = ms.coerceIn(500L, 20000L)
        prefs.edit().putLong("list_scroll_period_ms", bounded).apply()
    }

    /**
     * How the sweep's speed varies across the pass, per REQ-A22 (visual
     * comfort pacing, not a comprehension deadline — so there is room to
     * pick a feel rather than defend one "correct" curve).
     *
     * - LINEAR: constant speed across every facility, closest to a plain
     *   human swipe with no lingering.
     * - SINUSOIDAL: speed follows sin(pi*u) per gap — slows near
     *   each facility, fastest in the open space between them.
     * - DECELERATING_PASSES (default): multiple sinusoidal passes back-to-back
     *   (REQ-A5-safe: a fixed, bounded count, not a loop), each slower
     *   than the last — a quick overview pass first, then progressively
     *   more time to look on each subsequent pass.
     */
    enum class SweepPacingMode { LINEAR, SINUSOIDAL, DECELERATING_PASSES }

    fun getSweepPacingMode(): SweepPacingMode {
        val stored = prefs.getString("sweep_pacing_mode", null)
        return stored?.let { runCatching { SweepPacingMode.valueOf(it) }.getOrNull() }
            ?: SweepPacingMode.DECELERATING_PASSES
    }

    fun setSweepPacingMode(mode: SweepPacingMode) {
        prefs.edit().putString("sweep_pacing_mode", mode.name).apply()
    }

    /**
     * Number of passes for DECELERATING_PASSES mode. Bounded and fixed per
     * invocation (REQ-A5 — a defined terminal state, not a loop). Default 3:
     * a quick look, then two progressively slower confirmations.
     * Bounds: [1, 6]. 1 behaves like a single SINUSOIDAL pass.
     */
    fun getSweepPassCount(): Int {
        val stored = prefs.getInt("sweep_pass_count", -1)
        return if (stored > 0) stored else 3
    }

    fun setSweepPassCount(count: Int) {
        val bounded = count.coerceIn(1, 6)
        prefs.edit().putInt("sweep_pass_count", bounded).apply()
    }

    /**
     * How much slower each successive pass is than the one before it, in
     * DECELERATING_PASSES mode (pass period is multiplied by this factor
     * per pass). Default 1.5 (each pass 50% slower). Bounds: [1.0, 3.0] —
     * 1.0 degenerates to equal-speed repeated passes; above 3.0 the later
     * passes become impractically slow for a bounded 6-pass ceiling.
     */
    fun getSweepPassSlowdownFactor(): Float {
        val stored = prefs.getFloat("sweep_pass_slowdown", -1f)
        return if (stored > 0f) stored else 1.5f
    }

    fun setSweepPassSlowdownFactor(factor: Float) {
        val bounded = factor.coerceIn(1.0f, 3.0f)
        prefs.edit().putFloat("sweep_pass_slowdown", bounded).apply()
    }

    /**
     * REQ-A23: duration is a separate axis from period (getSweepPeriodMs —
     * a single pass's velocity, untouched by this). When enabled, the sweep
     * restarts for another pass whenever it detects a continuation signal
     * from the user (REQ-A24 — voice today, not touch-screen input) rather than
     * stopping after one pass — but only while that signal keeps arriving;
     * not a plain self-loop (REQ-A5): silence for one window stops it.
     * Off by default; bounded pass counts remain the default duration model.
     */
    fun getSweepRestartOnSignalEnabled(): Boolean =
        prefs.getBoolean("sweep_restart_on_signal_enabled", false)

    fun setSweepRestartOnSignalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("sweep_restart_on_signal_enabled", enabled).apply()
    }

    /**
     * Rolling window (ms) within which a REQ-A24 continuation signal must
     * arrive to restart the sweep for another pass (REQ-A23). Follows
     * REQ-V12's confirmation-window pattern/default. Bounds: [2000, 15000] ms.
     */
    fun getSweepHeartbeatWindowMs(): Long {
        val stored = prefs.getLong("sweep_heartbeat_window_ms", -1L)
        return if (stored > 0) stored else 6000L
    }

    fun setSweepHeartbeatWindowMs(ms: Long) {
        val bounded = ms.coerceIn(2000L, 15000L)
        prefs.edit().putLong("sweep_heartbeat_window_ms", bounded).apply()
    }

    /**
     * Which facility the sweep starts on and which way it first heads
     * (REQ-A22 extension: no reason the pass must always start at Speed
     * and head right). Index is into the fixed physical row order
     * [Speed, Stamina, Power, Guts, Wit]. The remaining facilities are
     * visited in a cyclic scan from the start index in the chosen
     * direction, wrapping across the row rather than skipping any.
     * Bounds: [0, 4].
     */
    fun getSweepStartFacilityIndex(): Int {
        val stored = prefs.getInt("sweep_start_facility_index", -1)
        return if (stored in 0..4) stored else 0
    }

    fun setSweepStartFacilityIndex(index: Int) {
        val bounded = index.coerceIn(0, 4)
        prefs.edit().putInt("sweep_start_facility_index", bounded).apply()
    }

    /** true = start heading toward Wit (right); false = start heading toward Speed (left). */
    fun getSweepStartDirectionRight(): Boolean =
        prefs.getBoolean("sweep_start_direction_right", true)

    fun setSweepStartDirectionRight(right: Boolean) {
        prefs.edit().putBoolean("sweep_start_direction_right", right).apply()
    }

    /**
     * REQ-V12's double-utterance confirmation window, in milliseconds.
     * Shared by every voice selection that arms-then-confirms, including
     * REQ-A22's sweep-pause case: how long a paused/armed selection waits
     * for the confirming second utterance before it expires. On expiry the
     * sweep resumes (REQ-A22) rather than sitting frozen. Default ~5s per
     * REQ-V12's resolved default. Bounds: [1000, 15000] ms.
     */
    fun getVoiceConfirmWindowMs(): Long {
        val stored = prefs.getLong("voice_confirm_window_ms", -1L)
        return if (stored > 0) stored else 8000L
    }

    fun setVoiceConfirmWindowMs(ms: Long) {
        val bounded = ms.coerceIn(1000L, 15000L)
        prefs.edit().putLong("voice_confirm_window_ms", bounded).apply()
    }

    /**
     * Whether to auto-record defaults on the next "start auto run recording defaults"
     * invocation (REQ-A21). Separate from the persistent "auto-record enabled" setting
     * (a 2.0 feature); this is purely the one-shot flag, toggled by the user or cleared
     * after a run completes.
     */
    fun getRecordingDefaultsArmed(): Boolean =
        prefs.getBoolean("recording_defaults_armed", false)

    fun setRecordingDefaultsArmed(armed: Boolean) {
        prefs.edit().putBoolean("recording_defaults_armed", armed).apply()
    }

    /** Stored decision defaults for REQ-A19/A21 macros. */
    fun getStoredDefault(key: String): String? =
        prefs.getString("default_$key", null)

    fun setStoredDefault(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove("default_$key").apply()
        } else {
            prefs.edit().putString("default_$key", value).apply()
        }
    }
}
