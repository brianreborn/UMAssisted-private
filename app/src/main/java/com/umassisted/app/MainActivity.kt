package com.umassisted.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Minimal launcher + control surface for 1.0 alpha.
 * Provides the always-visible kill switches (REQ-A7 / REQ-A10 / V9).
 *
 * Also provides explicit action buttons for alpha testing:
 * - Capture & Analyze (takeScreenshot + ML Kit OCR)
 * - Run Training Sweep (explicit user command, never auto)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var lastOcrText: TextView
    private lateinit var lastMatchReason: TextView
    private lateinit var lastSignature: TextView
    private lateinit var sweepSwitch: SwitchCompat
    private lateinit var voiceSwitch: SwitchCompat
    private lateinit var captureButton: Button
    private lateinit var sweepButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        lastOcrText = findViewById(R.id.lastOcrText)
        lastMatchReason = findViewById(R.id.lastMatchReason)
        lastSignature = findViewById(R.id.lastSignature)
        sweepSwitch = findViewById(R.id.sweepSwitch)
        voiceSwitch = findViewById(R.id.voiceSwitch)
        captureButton = findViewById(R.id.captureButton)
        sweepButton = findViewById(R.id.sweepButton)

        // Initialize from current service state
        sweepSwitch.isChecked = UMAccessibilityService.sweepEnabled
        voiceSwitch.isChecked = UMAccessibilityService.voiceEnabled

        sweepSwitch.setOnCheckedChangeListener { _, isChecked ->
            UMAccessibilityService.sweepEnabled = isChecked
            UMAccessibilityService.instance?.setSweepEnabled(isChecked)
        }

        voiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            UMAccessibilityService.voiceEnabled = isChecked
            UMAccessibilityService.instance?.setVoiceEnabled(isChecked)
        }

        captureButton.setOnClickListener {
            val svc = UMAccessibilityService.instance
            if (svc != null) {
                statusText.text = "Capturing + OCR..."
                svc.captureAndAnalyzeScreen { text, isNoChoice ->
                    runOnUiThread {
                        val preview = text.take(280).replace('\n', ' ')
                        lastOcrText.text = if (isNoChoice) "NO-CHOICE\n$preview" else preview
                        lastMatchReason.text = svc.lastMatchReason
                        val sig = svc.signatureFor(text)
                        lastSignature.text = if (sig.isNotBlank()) "sig: ${sig.take(80)}" else ""
                        statusText.text = if (text.isNotBlank()) {
                            if (isNoChoice) "OCR: looks like no-choice" else "OCR done"
                        } else {
                            "OCR empty / failed"
                        }
                        // Enable Smart Advance if we have a recorded decision for the *normalized* signature
                        // or if CorpusMatcher classified this as no-choice.
                        val hasRecorded = svc.getLastDecision(sig) != null
                        findViewById<Button>(R.id.advanceButton).isEnabled = isNoChoice || hasRecorded
                    }
                }
            } else {
                statusText.text = "Service not running"
            }
        }

        sweepButton.setOnClickListener {
            val svc = UMAccessibilityService.instance
            if (svc != null && UMAccessibilityService.sweepEnabled) {
                statusText.text = "Running training sweep..."
                svc.performTrainingSweepOnce()
                statusText.text = "Sweep command sent"
            } else {
                statusText.text = "Sweep disabled or service not available"
            }
        }

        // Smart advance (REQ-A4 + F2/F4):
        // Prefer a previously recorded specific user decision for this screen (if any).
        // Fall back to generic no-choice advance only when last capture was a no-choice screen
        // and we have no recorded specific choice. Always explicit button press (REQ-A5).
        findViewById<Button>(R.id.advanceButton).setOnClickListener {
            val svc = UMAccessibilityService.instance
            if (svc != null) {
                val sig = svc.signatureFor(svc.lastOcrText)
                val hasRecorded = svc.getLastDecision(sig) != null
                if (hasRecorded) {
                    val replayed = svc.tryReplayLastDecision(svc.lastOcrText)
                    statusText.text = if (replayed) "Replayed previous choice" else "Recorded choice not found on screen"
                } else if (svc.lastWasNoChoice) {
                    statusText.text = "Advancing (no-choice)..."
                    svc.performNoChoiceAdvance()
                    statusText.text = "Advance sent"
                } else {
                    statusText.text = "No previous decision recorded for this screen"
                }
            } else {
                statusText.text = "Service not available"
            }
        }

        // Convenience: capture, then decide.
        // 1) If we have a recorded specific choice for the OCR → replay it (highest priority).
        // 2) Else if CorpusMatcher says no-choice → generic advance.
        // 3) Else fall back to trying a replay (may be empty).
        // Still requires the user to press the button.
        findViewById<Button>(R.id.captureAndAdvanceButton).setOnClickListener {
            val svc = UMAccessibilityService.instance
            if (svc != null) {
                statusText.text = "Capture + decide (replay or no-choice)..."
                svc.captureAndAnalyzeScreen { text, isNoChoice ->
                    runOnUiThread {
                        lastOcrText.text = text.take(280).replace('\n', ' ')
                        lastMatchReason.text = svc.lastMatchReason
                        val sig = svc.signatureFor(text)
                        lastSignature.text = if (sig.isNotBlank()) "sig: ${sig.take(80)}" else ""
                        val hasRecorded = svc.getLastDecision(sig) != null
                        if (hasRecorded) {
                            val replayed = svc.tryReplayLastDecision(text)
                            statusText.text = if (replayed) "Replayed previous choice" else "Recorded choice not found on screen"
                        } else if (isNoChoice) {
                            statusText.text = "No-choice detected — advancing..."
                            svc.performNoChoiceAdvance()
                            statusText.text = "Advance sent"
                        } else {
                            val replayed = svc.tryReplayLastDecision(text)
                            statusText.text = if (replayed) "Replayed previous choice" else "Has choice or unclear — not auto-advancing"
                        }
                    }
                }
            } else {
                statusText.text = "Service not running"
            }
        }

        // Record the current last OCR as "the choice the user just made".
        // This seeds the crude decision replay (REQ-A4 alpha).
        // Always record using the service's normalized signatureFor so that later
        // capture + replay use a compatible key.
        findViewById<Button>(R.id.recordChoiceButton).setOnClickListener {
            val svc = UMAccessibilityService.instance
            if (svc != null && svc.lastOcrText.isNotBlank()) {
                val sig = svc.signatureFor(svc.lastOcrText)
                val chosen = deriveChosenAction(svc)
                svc.recordDecision(sig, chosen)
                statusText.text = "Recorded decision: $chosen"
            } else {
                statusText.text = "No recent OCR to record"
            }
        }

        // Explicit career exit (REQ-F5 alpha scope). Always user-initiated only.
        findViewById<Button>(R.id.exitCareerButton).setOnClickListener {
            val svc = UMAccessibilityService.instance
            if (svc != null) {
                statusText.text = "Exit career command sent..."
                // Default to Give Up path for alpha; a future settings toggle can choose Save & Exit.
                svc.performCareerExit(preferSaveAndExit = false)
                statusText.text = "Exit sequence started (explicit)"
            } else {
                statusText.text = "Service not running"
            }
        }

        // List auto-scroll (REQ-A16). Only when sweep is armed; explicit user command.
        findViewById<Button>(R.id.scrollListButton).setOnClickListener {
            val svc = UMAccessibilityService.instance
            if (svc != null && UMAccessibilityService.sweepEnabled) {
                statusText.text = "Scrolling list..."
                svc.performListScrollOnce(directionDown = true)
                statusText.text = "List scroll sent"
            } else {
                statusText.text = "Sweep not armed or service unavailable"
            }
        }

        // Check if accessibility service is enabled
        if (!isAccessibilityServiceEnabled()) {
            statusText.text = "Accessibility service not enabled. Tap to open settings."
            statusText.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else {
            statusText.text = getString(R.string.status_ready)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    override fun onResume() {
        super.onResume()
        if (isAccessibilityServiceEnabled()) {
            statusText.text = getString(R.string.status_ready)
        }
    }

    /**
     * Derive a more specific "chosen" action string for decision replay from the last capture.
     * Priority:
     *  - If the matcher gave a clear no-choice reason containing a known token, use that token.
     *  - Else try to find a short distinctive phrase from the OCR text (first "meaningful" line-ish chunk).
     *  - Fallback to generic "ADVANCE".
     */
    private fun deriveChosenAction(svc: UMAccessibilityService): String {
        val reason = svc.lastMatchReason.lowercase()
        val tokens = listOf("close", "next", "ok", "confirm", "race", "enter", "continue",
                            "skip", "results", "done", "finish", "replay", "watch", "give up", "save & exit")
        for (t in tokens) {
            if (reason.contains(t)) return t.uppercase()
        }

        // Fallback: try to extract something short and distinctive from the OCR itself.
        val ocr = svc.lastOcrText
        if (ocr.isNotBlank()) {
            // Take the first non-trivial line or a short prefix.
            val firstLine = ocr.lines().firstOrNull { it.trim().length > 3 } ?: ocr
            val short = firstLine.trim().take(60)
            if (short.isNotBlank()) return short
        }
        return "ADVANCE"
    }
}
