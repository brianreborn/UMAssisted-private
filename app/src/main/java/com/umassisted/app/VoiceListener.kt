package com.umassisted.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

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
 *   dictation-style free-form speech is.
 * - onUtterance receives every EXTRA_MAX_RESULTS alternate, not just the
 *   top one, so the caller can match against a small closed vocabulary
 *   (FacilityVocabulary) across all of them — if the top guess is wrong but
 *   the correct word shows up in alternate #2, that still counts.
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
    private val handler: Handler,
    private val onUtterances: (List<String>) -> Unit
) {
    companion object {
        private const val TAG = "VoiceListener"
        private const val MIN_RESTART_DELAY_MS = 600L
        private const val HARD_ERROR_BACKOFF_MS = 2000L
    }

    @Volatile private var armed = false
    private var recognizer: SpeechRecognizer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedSystemStreamVolume: Int? = null

    private val restartRunnable = Runnable { startSession() }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) onUtterances(matches)
            restartSoon(hardError = false)
        }

        override fun onError(error: Int) {
            val hard = error == SpeechRecognizer.ERROR_AUDIO ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            Log.w(TAG, "Recognition error=$error hard=$hard")
            restartSoon(hardError = hard)
        }

        override fun onEndOfSpeech() { /* onResults or onError follows; restart happens there */ }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun start() {
        if (armed) return
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
        recognizer?.let {
            try { it.stopListening() } catch (t: Throwable) { /* already gone */ }
            try { it.destroy() } catch (t: Throwable) { /* already gone */ }
        }
        recognizer = null
        restoreSystemStreamChime()
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
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not mute STREAM_SYSTEM for recognition chime", t)
        }
    }

    private fun restoreSystemStreamChime() {
        val saved = savedSystemStreamVolume ?: return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, saved, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not restore STREAM_SYSTEM volume", t)
        } finally {
            savedSystemStreamVolume = null
        }
    }

    private fun startSession() {
        val r = recognizer ?: return
        if (!armed) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Short command-style bias, not dictation — closer to a single facility name.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // The platform's own silence timeout — not our restart delay — is what was
            // ending each session almost immediately in a quiet room. These extend it so
            // the mic stays open and idle rather than closing/reopening on every brief
            // silence; "always-listening" (REQ-V5) should hold the mic open when it can,
            // not treat ordinary silence as a reason to cycle it. User-configurable
            // (UserSettings.getVoiceSilenceTimeoutMs) since some on-device engines
            // ignore this and hold to their own fixed floor regardless of the value.
            val silenceTimeoutMs = UserSettings.getVoiceSilenceTimeoutMs()
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
        }

        try {
            r.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start recognition session", t)
            restartSoon(hardError = true)
        }
    }

    private fun restartSoon(hardError: Boolean) {
        if (!armed) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, if (hardError) HARD_ERROR_BACKOFF_MS else MIN_RESTART_DELAY_MS)
    }
}
