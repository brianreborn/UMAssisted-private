package com.umassisted.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.ArrayList
import java.util.LinkedHashSet

/**
 * Wraps Android's on-device SpeechRecognizer (REQ-V2/REQ-S1: no network STT)
 * as a standing listener (REQ-V5's "always-listening" model). A single
 * recognition session always ends — after a result, no-match, or timeout —
 * so "always-listening" only holds if every one of those endings restarts a
 * fresh session while armed (REQ-V5's restart-on-timeout obligation).
 *
 * Two failure modes this guards against, both learned the hard way:
 * - **Recreating the native recognizer on every restart** (destroy + a fresh
 *   `createSpeechRecognizer`) reopens the mic's audio-focus/binder session
 *   each time, which is audibly a rapid on/off click if restarts are
 *   frequent (e.g. ERROR_NO_MATCH firing quickly in a quiet room). One
 *   recognizer instance is created in start() and reused for every session;
 *   only stop() destroys it.
 * - **Zero-delay restarts** on the "happy path" (a result, or the ordinary
 *   ERROR_NO_MATCH/ERROR_SPEECH_TIMEOUT) can still tight-loop even with a
 *   reused instance if the environment keeps producing instant no-speech
 *   errors. Every restart — happy path or hard error — goes through a
 *   minimum delay; hard errors (mic busy, client error) get a longer one
 *   (REQ-A5: no spinning retry loop).
 *
 * Tuning notes (public on-device API only — there is no wordlist/grammar
 * priming extra for the standard SpeechRecognizer/RecognizerIntent path;
 * that's a Cloud Speech API feature, not available offline):
 * - LANGUAGE_MODEL_WEB_SEARCH is used instead of FREE_FORM: it's tuned for
 *   short command-style utterances, closer to a single facility name than
 *   dictation-style free-form speech is. Empirically on this Pixel,
 *   FREE_FORM + EXTRA_LANGUAGE (Locale.toLanguageTag) made SODA report
 *   onBeginningOfSpeech every ~600ms then return zero-length hypotheses
 *   (partial:[], ERROR_NO_MATCH) — the extras that last actually
 *   transcribed (commit 967f040 / REQ-V18) are WEB_SEARCH and no language
 *   extra, leaving the engine on the device default.
 * - Do not send EXTRA_LANGUAGE / EXTRA_LANGUAGE_PREFERENCE. The on-device
 *   engine already binds a language pack from the system locale; forcing
 *   a BCP-47 tag has been observed to produce empty finals.
 * - onUtterance receives every EXTRA_MAX_RESULTS alternate, not just the
 *   top one, so the caller can match against a small closed vocabulary
 *   (FacilityVocabulary) across all of them — if the top guess is wrong but
 *   the correct word shows up in alternate #2, that still counts.
 *
 * Partial-results early stop: if the caller's isUnambiguousMatch predicate
 * says a partial transcript already resolves cleanly (e.g. a clean facility
 * name), the session is stopped right then via stopListening() rather than
 * waiting out the rest of the configured silence timeout — a confident user
 * shouldn't sit through dead air the app doesn't need.
 *
 * Chime suppression: the audible start/end tone on every restart is not
 * played by SpeechRecognizer itself — it's the OEM recognition service
 * (visible in logcat as e.g. GoogleTTSRecognitionService) playing its own
 * UX cue on the system sound-effects stream (STREAM_SYSTEM), separate from
 * whatever stream game audio/music uses (typically STREAM_MUSIC). The
 * EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS extras above are honored
 * inconsistently across on-device engines (confirmed silently ignored on
 * at least one device, session still ending on its own ~5s internal
 * timeout) — so restarts at that cadence are a platform floor we cannot
 * reliably raise. Muting STREAM_SYSTEM for the armed duration is what
 * actually silences the chime spam without touching game audio.
 */
class VoiceListener(
    private val context: Context,
    /** Return true if a command was actually dispatched (not ignored / no-match). */
    private val onUtterances: (List<String>) -> Boolean,
    /**
     * Called with each partial-results update; return true once the partial
     * transcript already unambiguously resolves (e.g. a clean facility-name
     * match). A true result stops the session early instead of waiting out
     * the full silence timeout — the user shouldn't have to sit through
     * dead air after saying something the app already understood.
     */
    private val isUnambiguousMatch: (List<String>) -> Boolean = { false },
    /**
     * REQ-A31: raw RMS level (Android's onRmsChanged units, roughly -2..10 on
     * this recognizer — not calibrated dB) plus whether it was captured
     * during a live session (between onReadyForSpeech and onEndOfSpeech).
     * Called on the main thread, same as every other RecognitionListener
     * callback here — no thread hop needed by the receiver.
     */
    private val onRmsSample: (rms: Float, active: Boolean) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "VoiceListener"
        private const val MIN_RESTART_DELAY_MS = 800L
        private const val HARD_ERROR_BACKOFF_MS = 2500L
        /**
         * REQ-V25: flat, not growing with consecutive empty streaks. This used
         * to be `(2000L shl consecutiveEmptyEnds.coerceAtMost(3))`, capped at
         * 10s — meaning the longer the mic stayed silent, the longer the gap
         * before it listened again. That is exactly backwards: real utterances
         * were observed on-device landing in these self-inflicted gaps and
         * getting zero STT partials at all (not mismatched — never heard),
         * and a user is not less likely to speak next just because they were
         * quiet for the last few cycles. Silence should never be treated as
         * evidence the mic can afford to stay unarmed longer.
         */
        private const val EMPTY_RESTART_DELAY_MS = 2000L
        /** Floor between startListening calls unless a real command just fired. */
        private const val MIN_CYCLE_MS = 8_000L
        private const val MIN_SPEECH_MS = 400L
        /**
         * Real-time settle window before acting on an unambiguous partial match.
         * SODA can re-emit an identical partial within single-digit milliseconds
         * of the previous one (same decode tick) — stopping on that instant cuts
         * the recognizer off mid-decode and yields an empty final result. This
         * must comfortably exceed the ~600ms partial cadence so at least one more
         * real decode tick confirms the match before stopListening() runs.
         */
        private const val EARLY_STOP_SETTLE_MS = 900L

        /**
         * Settle window for a partial that is stable (repeating unchanged) but
         * does NOT match anything in the corpus. Observed on-device: this engine's
         * own natural finalization (no early-stop involved at all) can return a
         * completely empty onResults ~15-20s after the last partial, silently
         * discarding a clearly-heard-but-unmatched utterance ("Training training"
         * logged repeatedly as a stable partial, then `onResults: []`). Acting on
         * a stable-but-unmatched partial directly — same mechanism as the matched
         * fast-path, just slower — means unmatched speech is at least reported
         * (as "no match", so the corpus/debug log shows what was actually said)
         * well before that native finalization has a chance to lose it entirely.
         * Slower than EARLY_STOP_SETTLE_MS on purpose: unmatched speech is more
         * likely still mid-utterance (a multi-word command not yet finished) than
         * matched speech is, so it gets more room to keep evolving first.
         */
        private const val GENERAL_SETTLE_MS = 2_500L
    }

    // Deliberately its OWN Handler, not the owning service's — voice's restart
    // timer must survive housekeeping calls like AccessibilityService.onInterrupt()
    // (a routine, frequent event) canceling the service's own pending gesture
    // callbacks via handler.removeCallbacksAndMessages(null). Sharing a Handler
    // here previously meant a routine onInterrupt() silently and permanently
    // killed voice recognition with no error and no re-arm.
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var armed = false
    private var recognizer: SpeechRecognizer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedSystemStreamVolume: Int? = null
    private var savedNotificationStreamVolume: Int? = null
    @Volatile private var stoppedEarlyThisSession = false
    // REQ-A31: true between onReadyForSpeech and onEndOfSpeech, for tagging
    // RMS samples as "captured during a live session" vs. a restart gap.
    @Volatile private var sessionActiveForRms = false
    @Volatile private var loggedFirstRmsSample = false
    @Volatile private var loggedFirstAudioBufferDiag = false
    @Volatile private var sessionHeardSpeech = false
    private var sessionStartedAtMs = 0L
    private var consecutiveEmptyEnds = 0

    private var pendingEarlyStopMatch: List<String>? = null
    @Volatile private var expectingCancelError = false
    private val earlyStopRunnable = Runnable {
        // Originally called recognizer.stopListening() here to let the session
        // finalize normally through onResults. Observed on-device: on this
        // on-device (SODA) engine, stopListening() does not hand back the
        // buffered partial hypothesis at all — onResults consistently fires
        // with an empty transcript, discarding a command the partial stream had
        // already clearly recognized. So the settled partial is acted on
        // directly here (identical vocabulary resolution to onResults' path),
        // and the session is torn down with cancel() — which unlike
        // stopListening() delivers no further callback for this session — then
        // manually restarted, rather than trusting the engine to finalize.
        val match = pendingEarlyStopMatch
        stoppedEarlyThisSession = true
        pendingEarlyStopMatch = null
        if (match != null) {
            VoiceDebugLog.log("unambiguous partial match settled — acting early: $match")
            val acted = armed && onUtterances(match)
            // cancel() reliably triggers onError(ERROR_CLIENT) as its own teardown
            // callback (observed on-device) — not a real failure, just cancel()
            // announcing itself. Without this flag onError treats it as a hard
            // error and reschedules the restart it's about to fire below with
            // the slower hard-error backoff, discarding the fast restart already
            // decided here.
            expectingCancelError = true
            try {
                recognizer?.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to cancel session after early action", t)
            }
            if (acted) {
                consecutiveEmptyEnds = 0
                restartSoon(hardError = false, afterCommand = true)
            } else {
                restartSoon(hardError = false, afterCommand = false)
            }
        }
    }

    private val restartRunnable = Runnable { startSession() }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val matches = decodeUtterances(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            if (BuildConfig.DEBUG) Log.i(TAG, "STT decoded words: $matches")
            VoiceDebugLog.log("RESULT: $matches")
            // A result already in flight when stop() ran must not still act — armed
            // is checked here, not just inside restartSoon, since delivery of a result
            // is always async relative to when stop() can have been called.
            val acted = armed && matches.isNotEmpty() && onUtterances(matches)
            if (acted) {
                consecutiveEmptyEnds = 0
                restartSoon(hardError = false, afterCommand = true)
            } else {
                consecutiveEmptyEnds++
                restartSoon(hardError = false, afterCommand = false)
            }
        }

        override fun onError(error: Int) {
            if (expectingCancelError && error == SpeechRecognizer.ERROR_CLIENT) {
                expectingCancelError = false
                VoiceDebugLog.log("ERROR_CLIENT from our own cancel() after early action — ignored")
                return
            }
            // ERROR_NO_MATCH (7) and ERROR_SPEECH_TIMEOUT (6) are normal non-error outcomes
            // in continuous listening when no speech is detected. They must not trigger
            // a hard-error blackout — but they also must not reset the empty-streak
            // just because game audio tripped VAD (that was the remaining flap).
            val hard = error == SpeechRecognizer.ERROR_AUDIO ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            Log.w(TAG, "Recognition error=$error hard=$hard")
            VoiceDebugLog.log("ERROR: code=$error hard=$hard")
            if (!hard) consecutiveEmptyEnds++
            restartSoon(hardError = hard, afterCommand = false)
        }

        override fun onEndOfSpeech() {
            // Purely observational — onResults or onError still follows and
            // restart still happens there. Logged as the literal callback name,
            // not an interpretation of what it means (interpreting "speech
            // detected" as more than onBeginningOfSpeech literally reports was
            // itself a source of confusion this session) — this is exactly and
            // only "onEndOfSpeech fired," nothing more asserted.
            sessionActiveForRms = false
            VoiceDebugLog.log("onEndOfSpeech")
        }
        override fun onReadyForSpeech(params: Bundle?) {
            sessionActiveForRms = true
            VoiceDebugLog.log("onReadyForSpeech")
        }
        override fun onBeginningOfSpeech() {
            sessionHeardSpeech = true
            VoiceDebugLog.log("onBeginningOfSpeech")
        }
        override fun onRmsChanged(rmsdB: Float) {
            if (!loggedFirstRmsSample) {
                loggedFirstRmsSample = true
                VoiceDebugLog.log("onRmsChanged firing (first sample=$rmsdB) — confirms engine supports it")
            }
            onRmsSample(rmsdB, sessionActiveForRms)
        }
        override fun onBufferReceived(buffer: ByteArray?) {
            // REQ-A31: a raw-PCM-derived "tone" (zero-crossing-rate) channel
            // was built here and then deliberately cut — it didn't answer any
            // real diagnostic question and risked looking meaningful when it
            // wasn't (see AudioLevelView's class doc). Volume is covered by
            // onRmsSample already; nothing here needs the raw buffer for the
            // visualizer.
            //
            // DIAGNOSTIC (temporary): testing the hypothesis that onRmsChanged
            // only fired earlier because this callback was also doing real
            // work at the time — i.e. the engine's RMS reporting is
            // conditioned on buffer-listener activity, not independent of it.
            // Minimal real processing, logged once, deliberately not wired to
            // anything yet.
            if (buffer != null && !loggedFirstAudioBufferDiag) {
                loggedFirstAudioBufferDiag = true
                VoiceDebugLog.log("onBufferReceived firing (${buffer.size} bytes) — testing RMS-correlation hypothesis")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (stoppedEarlyThisSession) return
            val partial = decodeUtterances(
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            )
            // Empty / blank partials fire on a ~600ms cadence from SODA even with
            // no transcript (often a list containing "") — logging them fills the
            // 300-entry debug ring and hides the events that matter.
            if (partial.isEmpty()) return
            if (BuildConfig.DEBUG) Log.i(TAG, "STT partial decoded words: $partial")
            VoiceDebugLog.log("partial: $partial")
            // SODA can re-emit the identical partial string within single-digit
            // milliseconds (same decode tick), so seeing it once is not evidence
            // it has settled — debounce with a real-time settle window and only
            // act once this same text is still current when the window elapses.
            //
            // Two settle speeds, not one: a partial that matches the corpus
            // settles fast (EARLY_STOP_SETTLE_MS) so recognized commands feel
            // responsive. A partial that does NOT match anything still gets a
            // slower settle (GENERAL_SETTLE_MS) rather than no settle at all —
            // without this, an unmatched utterance falls through entirely to
            // this engine's own natural finalization, which has been observed to
            // return a completely empty onResults ~15-20s later regardless of
            // what was actually said (a real "Training training" partial,
            // repeated stably, followed by `onResults: []`). Acting on a stable
            // partial ourselves — matched or not — means onUtterances always
            // gets a chance to see (and correctly reject, if genuinely unmatched)
            // what was said, instead of it silently vanishing.
            if (partial == pendingEarlyStopMatch) {
                return // already tracking this exact text; let the scheduled settle run
            }
            pendingEarlyStopMatch = partial
            handler.removeCallbacks(earlyStopRunnable)
            val delay = if (isUnambiguousMatch(partial)) EARLY_STOP_SETTLE_MS else GENERAL_SETTLE_MS
            handler.postDelayed(earlyStopRunnable, delay)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun start() {
        if (armed) return

        // A missing RECORD_AUDIO grant is a static condition, not a transient one —
        // starting anyway would just hit ERROR_INSUFFICIENT_PERMISSIONS and spin in
        // the hard-error retry loop forever. Fail once, clearly, instead.
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted — voice cannot start")
            VoiceDebugLog.log("RECORD_AUDIO permission not granted — voice not started")
            return
        }

        armed = true

        // REQ-V2/REQ-S1: createSpeechRecognizer() picks a generic recognizer that may
        // assume network fallback is available; EXTRA_PREFER_OFFLINE was only ever a
        // hint to it, not a guarantee. createOnDeviceSpeechRecognizer() (API 31+)
        // explicitly binds the local engine instead — observed on-device: the generic
        // path recorded real audio (confirmed via dumpsys) but consistently returned a
        // zero-length hypothesis, consistent with a network-assuming recognizer that
        // can't actually reach one (this app has no INTERNET permission).
        val onDeviceAvailable = android.os.Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDeviceAvailable && !SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "No speech recognition available on this device")
            armed = false
            return
        }
        val r = if (onDeviceAvailable) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            Log.w(TAG, "On-device recognizer unavailable, falling back to generic (may not transcribe without network)")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r.apply { setRecognitionListener(listener) }
        if (UserSettings.getVoiceMuteChimeEnabled()) muteSystemStreamChime()
        startSession()
    }

    fun stop() {
        armed = false
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(earlyStopRunnable)
        pendingEarlyStopMatch = null
        expectingCancelError = false
        recognizer?.let {
            try { it.stopListening() } catch (t: Throwable) { /* already gone */ }
            try { it.destroy() } catch (t: Throwable) { /* already gone */ }
        }
        recognizer = null
        restoreSystemStreamChime()
    }

    /**
     * Re-applies the chime-mute setting immediately, for when the user flips it
     * while a session is already armed — previously this only took effect on the
     * next full start()/stop() cycle, so the UI and actual mute state could
     * disagree until voice was toggled off and back on.
     */
    fun applyChimeMuteSettingLive() {
        if (!armed) return
        if (UserSettings.getVoiceMuteChimeEnabled()) muteSystemStreamChime() else restoreSystemStreamChime()
    }

    /**
     * Silences the recognition service's own start/end tone for as long as voice
     * is armed. STREAM_SYSTEM carries that UX cue on the engines observed so far,
     * not game audio (typically STREAM_MUSIC), so this doesn't touch playback.
     * Restored on stop() so muting doesn't outlive the feature being enabled.
     * Opt-in (UserSettings.getVoiceMuteChimeEnabled, default off) — muting a
     * whole system stream is a side effect the user should choose, not one
     * imposed on them; some setups may want the chime as a "mic is live" cue.
     */
    private fun muteSystemStreamChime() {
        try {
            if (savedSystemStreamVolume == null) {
                savedSystemStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
            }
            if (savedNotificationStreamVolume == null) {
                savedNotificationStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            }
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
            // Some OEMs play the recognition cue on notification, not system.
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not mute STREAM_SYSTEM for recognition chime", t)
        }
    }

    private fun restoreSystemStreamChime() {
        try {
            savedSystemStreamVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, it, 0) }
            savedNotificationStreamVolume?.let {
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, it, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not restore chime stream volumes", t)
        } finally {
            savedSystemStreamVolume = null
            savedNotificationStreamVolume = null
        }
    }

    private fun startSession() {
        val r = recognizer ?: return
        if (!armed) return
        stoppedEarlyThisSession = false
        sessionHeardSpeech = false
        pendingEarlyStopMatch = null
        handler.removeCallbacks(earlyStopRunnable)
        sessionStartedAtMs = android.os.SystemClock.elapsedRealtime()

        val intent = buildRecognizerIntent()

        if (consecutiveEmptyEnds == 0) {
            VoiceDebugLog.log("startListening (on-device command-style)")
        }
        try {
            r.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start recognition session", t)
            restartSoon(hardError = true, afterCommand = false)
        }
    }

    private fun restartSoon(hardError: Boolean, afterCommand: Boolean) {
        if (!armed) return
        handler.removeCallbacks(restartRunnable)
        val elapsed = android.os.SystemClock.elapsedRealtime() - sessionStartedAtMs
        val waitForCycle = (MIN_CYCLE_MS - elapsed).coerceAtLeast(0L)
        val emptyBackoff = EMPTY_RESTART_DELAY_MS
        val delay = when {
            hardError -> maxOf(HARD_ERROR_BACKOFF_MS, waitForCycle)
            afterCommand -> MIN_RESTART_DELAY_MS
            else -> maxOf(waitForCycle, emptyBackoff, MIN_RESTART_DELAY_MS)
        }
        VoiceDebugLog.log("restart in ${delay}ms (emptyStreak=$consecutiveEmptyEnds hard=$hardError afterCommand=$afterCommand)")
        handler.postDelayed(restartRunnable, delay)
    }

    /** Shared extras for startListening and checkRecognitionSupport. */
    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Short command-style bias, not dictation — closer to a single facility name.
            // Do not send EXTRA_LANGUAGE*: the on-device engine picks the system
            // locale's language pack; forcing a BCP-47 tag has been observed to
            // yield empty finals (speech detected, 0-hyp, ERROR_NO_MATCH).
            // LANGUAGE_MODEL_WEB_SEARCH = short command/query *style*, not
            // a network call (REQ-V18 / REQ-S1). On-device vs cloud is
            // decided by createOnDeviceSpeechRecognizer() above.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Hold the extras to the last on-device config that actually
            // transcribed on this Pixel (967f040 / REQ-V18): no
            // EXTRA_PARTIAL_RESULTS, no EXTRA_LANGUAGE, 60s silence, 0 min
            // speech. Later extras (FREE_FORM, locale tag, partials, a 2s
            // silence floor) produced the empty-hypothesis / ERROR_NO_MATCH
            // loop. Partial-results early-stop stays gated by REQ-V19 anyway.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_SPEECH_MS)
        }

    /**
     * Drop null/blank alternates and collapse identical hypotheses.
     * SODA routinely returns the same transcript twice when EXTRA_MAX_RESULTS
     * is > 1 (`[Stamina and speed, Stamina and speed]`) — that is one
     * utterance, not two.
     */
    private fun decodeUtterances(raw: ArrayList<String>?): List<String> {
        if (raw == null) return emptyList()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>()
        for (s in raw) {
            val t = s.trim()
            if (t.isEmpty()) continue
            if (seen.add(t.lowercase())) out.add(t)
        }
        return out
    }

}
