package com.umassisted.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.ToggleButton
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
    private lateinit var sweepPeriodLabel: TextView
    private lateinit var sweepPeriodSeekBar: SeekBar
    private lateinit var sweepPeriodEditText: EditText
    private lateinit var sweepPeriodSetButton: Button
    private lateinit var sweepPacingModeSpinner: Spinner
    private lateinit var sweepStartFacilitySpinner: Spinner
    private lateinit var sweepDirectionToggle: ToggleButton
    private lateinit var sweepPassesGroup: View
    private lateinit var sweepPassCountLabel: TextView
    private lateinit var sweepPassCountSeekBar: SeekBar
    private lateinit var sweepPassSlowdownLabel: TextView
    private lateinit var sweepPassSlowdownSeekBar: SeekBar
    private lateinit var sweepRestartOnSignalSwitch: SwitchCompat
    private lateinit var sweepHeartbeatWindowLabel: TextView
    private lateinit var sweepHeartbeatWindowSeekBar: SeekBar

    // Human-meaningful grid the period slider snaps to, rather than every raw
    // millisecond — matches how a person actually thinks about pacing ("about
    // 5 seconds") instead of picking 5023ms by accident.
    private val periodGridMs = listOf(
        1000L, 1500L, 2000L, 3000L, 4000L, 5000L, 6000L, 8000L, 10000L, 12000L, 15000L, 20000L
    )

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
        sweepPeriodLabel = findViewById(R.id.sweepPeriodLabel)
        sweepPeriodSeekBar = findViewById(R.id.sweepPeriodSeekBar)
        sweepPeriodEditText = findViewById(R.id.sweepPeriodEditText)
        sweepPeriodSetButton = findViewById(R.id.sweepPeriodSetButton)
        sweepPacingModeSpinner = findViewById(R.id.sweepPacingModeSpinner)
        sweepStartFacilitySpinner = findViewById(R.id.sweepStartFacilitySpinner)
        sweepDirectionToggle = findViewById(R.id.sweepDirectionToggle)
        sweepPassesGroup = findViewById(R.id.sweepPassesGroup)
        sweepPassCountLabel = findViewById(R.id.sweepPassCountLabel)
        sweepPassCountSeekBar = findViewById(R.id.sweepPassCountSeekBar)
        sweepPassSlowdownLabel = findViewById(R.id.sweepPassSlowdownLabel)
        sweepPassSlowdownSeekBar = findViewById(R.id.sweepPassSlowdownSeekBar)
        sweepRestartOnSignalSwitch = findViewById(R.id.sweepRestartOnSignalSwitch)
        sweepHeartbeatWindowLabel = findViewById(R.id.sweepHeartbeatWindowLabel)
        sweepHeartbeatWindowSeekBar = findViewById(R.id.sweepHeartbeatWindowSeekBar)

        // Initialize from current service state
        sweepSwitch.isChecked = UMAssistedAccessibilityService.sweepEnabled
        voiceSwitch.isChecked = UMAssistedAccessibilityService.voiceEnabled

        setUpSweepSettingsUi()

        sweepSwitch.setOnCheckedChangeListener { _, isChecked ->
            UMAssistedAccessibilityService.sweepEnabled = isChecked
            UMAssistedAccessibilityService.instance?.setSweepEnabled(isChecked)
        }

        voiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            UMAssistedAccessibilityService.voiceEnabled = isChecked
            UMAssistedAccessibilityService.instance?.setVoiceEnabled(isChecked)
        }

        captureButton.setOnClickListener {
            val svc = UMAssistedAccessibilityService.instance
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
            val svc = UMAssistedAccessibilityService.instance
            if (svc != null && UMAssistedAccessibilityService.sweepEnabled) {
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
            val svc = UMAssistedAccessibilityService.instance
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
            val svc = UMAssistedAccessibilityService.instance
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
            val svc = UMAssistedAccessibilityService.instance
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
            val svc = UMAssistedAccessibilityService.instance
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
            val svc = UMAssistedAccessibilityService.instance
            if (svc != null && UMAssistedAccessibilityService.sweepEnabled) {
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

    /**
     * Wires the whole "Sweep behavior" panel (REQ-A22): period (grid-snapped
     * slider + free-entry text field), pacing shape, start facility/direction,
     * and the pass count/slowdown pair that only matters in DECELERATING_PASSES
     * mode. Every control reads its initial value from UserSettings and writes
     * straight back through it, so external state (another screen, a future
     * voice command) always sees the same source of truth.
     */
    private fun setUpSweepSettingsUi() {
        fun closestGridIndex(ms: Long): Int =
            periodGridMs.indices.minByOrNull { kotlin.math.abs(periodGridMs[it] - ms) } ?: 0

        fun applyPeriod(ms: Long, updateSlider: Boolean, updateEditText: Boolean) {
            val snapped = periodGridMs[closestGridIndex(ms)]
            UserSettings.setSweepPeriodMs(snapped)
            sweepPeriodLabel.text = "Sweep period: $snapped ms"
            if (updateSlider) sweepPeriodSeekBar.progress = closestGridIndex(snapped)
            if (updateEditText) sweepPeriodEditText.setText(snapped.toString())
        }

        sweepPeriodSeekBar.max = periodGridMs.size - 1
        val initialPeriod = UserSettings.getSweepPeriodMs()
        sweepPeriodSeekBar.progress = closestGridIndex(initialPeriod)
        sweepPeriodLabel.text = "Sweep period: $initialPeriod ms"
        sweepPeriodEditText.setText(initialPeriod.toString())

        sweepPeriodSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) applyPeriod(periodGridMs[progress], updateSlider = false, updateEditText = true)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        sweepPeriodSetButton.setOnClickListener {
            val entered = sweepPeriodEditText.text.toString().toLongOrNull()
            if (entered != null) {
                applyPeriod(entered.coerceIn(500L, 20000L), updateSlider = true, updateEditText = true)
            } else {
                statusText.text = "Enter a number of milliseconds"
            }
        }

        // Pacing shape
        val modeNames = listOf("Sinusoidal (lingers near facilities)", "Linear (constant speed)", "Decelerating passes (quick, then slower)")
        val modes = listOf(
            UserSettings.SweepPacingMode.SINUSOIDAL,
            UserSettings.SweepPacingMode.LINEAR,
            UserSettings.SweepPacingMode.DECELERATING_PASSES
        )
        sweepPacingModeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modeNames)
        val initialMode = UserSettings.getSweepPacingMode()
        sweepPacingModeSpinner.setSelection(modes.indexOf(initialMode).coerceAtLeast(0))
        sweepPassesGroup.visibility = if (initialMode == UserSettings.SweepPacingMode.DECELERATING_PASSES) View.VISIBLE else View.GONE
        sweepPacingModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                val chosen = modes[position]
                UserSettings.setSweepPacingMode(chosen)
                sweepPassesGroup.visibility = if (chosen == UserSettings.SweepPacingMode.DECELERATING_PASSES) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // Start facility. Direction is meaningless at the row's edges: "leftward" from
        // Speed (leftmost) or "rightward" from Wit (rightmost) has nowhere to go before
        // immediately wrapping to the far side, so the toggle is forced and locked there
        // instead of offering a choice that isn't really one.
        val facilityNames = listOf("Speed", "Stamina", "Power", "Guts", "Wit")
        sweepStartFacilitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, facilityNames)

        fun applyDirectionConstraint(facilityIndex: Int) {
            when (facilityIndex) {
                0 -> { // Speed: only rightward is meaningful
                    sweepDirectionToggle.isChecked = true
                    UserSettings.setSweepStartDirectionRight(true)
                    sweepDirectionToggle.isEnabled = false
                }
                facilityNames.size - 1 -> { // Wit: only leftward is meaningful
                    sweepDirectionToggle.isChecked = false
                    UserSettings.setSweepStartDirectionRight(false)
                    sweepDirectionToggle.isEnabled = false
                }
                else -> {
                    sweepDirectionToggle.isEnabled = true
                    sweepDirectionToggle.isChecked = UserSettings.getSweepStartDirectionRight()
                }
            }
        }

        val initialFacility = UserSettings.getSweepStartFacilityIndex()
        sweepStartFacilitySpinner.setSelection(initialFacility)
        applyDirectionConstraint(initialFacility)
        sweepStartFacilitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                UserSettings.setSweepStartFacilityIndex(position)
                applyDirectionConstraint(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        sweepDirectionToggle.setOnCheckedChangeListener { _, isChecked ->
            if (sweepDirectionToggle.isEnabled) UserSettings.setSweepStartDirectionRight(isChecked)
        }

        // Pass count / slowdown (DECELERATING_PASSES only; hidden otherwise)
        sweepPassCountSeekBar.max = 5 // maps to UserSettings bound [1, 6]
        val initialPassCount = UserSettings.getSweepPassCount()
        sweepPassCountSeekBar.progress = initialPassCount - 1
        sweepPassCountLabel.text = "Passes: $initialPassCount"
        sweepPassCountSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val count = progress + 1
                sweepPassCountLabel.text = "Passes: $count"
                if (fromUser) UserSettings.setSweepPassCount(count)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        sweepPassSlowdownSeekBar.max = 20 // 1.0x..3.0x in 0.1x steps
        val initialSlowdown = UserSettings.getSweepPassSlowdownFactor()
        sweepPassSlowdownSeekBar.progress = ((initialSlowdown - 1.0f) * 10).toInt()
        sweepPassSlowdownLabel.text = "Slowdown per pass: ${"%.1f".format(initialSlowdown)}x"
        sweepPassSlowdownSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val factor = 1.0f + progress / 10f
                sweepPassSlowdownLabel.text = "Slowdown per pass: ${"%.1f".format(factor)}x"
                if (fromUser) UserSettings.setSweepPassSlowdownFactor(factor)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // REQ-A23/A24: duration axis, separate from period. Continuation is gated by a
        // continuation signal (voice today) so it stays a chain of explicit user
        // signals rather than a plain self-loop (REQ-A5).
        sweepRestartOnSignalSwitch.isChecked = UserSettings.getSweepRestartOnSignalEnabled()
        sweepRestartOnSignalSwitch.setOnCheckedChangeListener { _, isChecked ->
            UserSettings.setSweepRestartOnSignalEnabled(isChecked)
        }

        sweepHeartbeatWindowSeekBar.max = 13000 // maps to [2000, 15000] via +2000 offset
        val initialHeartbeatWindow = UserSettings.getSweepHeartbeatWindowMs()
        sweepHeartbeatWindowSeekBar.progress = (initialHeartbeatWindow - 2000L).toInt()
        sweepHeartbeatWindowLabel.text = "Signal window: $initialHeartbeatWindow ms"
        sweepHeartbeatWindowSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = (progress + 2000).toLong()
                sweepHeartbeatWindowLabel.text = "Signal window: $ms ms"
                if (fromUser) UserSettings.setSweepHeartbeatWindowMs(ms)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
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
    private fun deriveChosenAction(svc: UMAssistedAccessibilityService): String {
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
