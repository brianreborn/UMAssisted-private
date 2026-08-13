package com.umassisted.app

import android.app.Application

/**
 * Minimal Application class for 1.0 alpha.
 * No network initialization, no analytics.
 */
class UMAssistedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UserSettings.init(this)
        // Future: load local corpus here (REQ-M5)
    }
}
