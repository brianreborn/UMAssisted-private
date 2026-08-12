package com.umassisted.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Minimal launcher + control surface for 1.0 alpha.
 * Provides the always-visible kill switches (REQ-A7 / REQ-A10 / V9).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var sweepSwitch: SwitchCompat
    private lateinit var voiceSwitch: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        sweepSwitch = findViewById(R.id.sweepSwitch)
        voiceSwitch = findViewById(R.id.voiceSwitch)

        // Initialize from current service state (in real life we would bind or use shared prefs)
        sweepSwitch.isChecked = UMAccessibilityService.sweepEnabled
        voiceSwitch.isChecked = UMAccessibilityService.voiceEnabled

        sweepSwitch.setOnCheckedChangeListener { _, isChecked ->
            UMAccessibilityService.sweepEnabled = isChecked
            // In a real build we would also tell the running service instance.
            // For alpha skeleton we rely on the static fields + service re-reading on events.
        }

        voiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            UMAccessibilityService.voiceEnabled = isChecked
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

        // Future: add explicit "Run sweep now" button that calls service.performTrainingSweepOnce()
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
        // Update status in case user enabled the service from settings
        if (isAccessibilityServiceEnabled()) {
            statusText.text = getString(R.string.status_ready)
        }
    }
}
