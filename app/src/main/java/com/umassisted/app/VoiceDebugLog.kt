package com.umassisted.app

import android.os.SystemClock

/**
 * In-memory ring buffer of high-level voice-pipeline events, feeding the
 * debug-only live log window (MainActivity's "Voice Debug Log" button).
 *
 * REQ-S3: this is the deliberate, contained exception to "no raw user-input
 * content logged outside a debug build" — the whole surface (recording here
 * AND the viewer) is a no-op unless BuildConfig.DEBUG, checked once in log()
 * so call sites never need their own guard and can't forget one. Not a
 * production diagnostics channel; nothing here reaches logcat or any file.
 */
object VoiceDebugLog {
    data class Entry(val atMs: Long, val text: String)

    private const val MAX_ENTRIES = 300
    private val entries = ArrayDeque<Entry>()
    private val lock = Any()

    fun log(text: String) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            entries.addLast(Entry(SystemClock.elapsedRealtime(), text))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }
}
