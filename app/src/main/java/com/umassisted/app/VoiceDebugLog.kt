package com.umassisted.app

import android.os.SystemClock

/**
 * In-memory ring buffer of high-level voice-pipeline events, feeding the
 * debug-only live log window (MainActivity's "Voice Pipeline Log" button).
 *
 * REQ-S3: this is the deliberate, contained exception to "no raw user-input
 * content logged outside a debug build" — the whole surface (recording here
 * AND the viewer) is a no-op unless BuildConfig.DEBUG, checked once in log()
 * so call sites never need their own guard and can't forget one. Not a
 * production diagnostics channel; nothing here reaches logcat or any file.
 */
object VoiceDebugLog {
    data class Entry(val atMs: Long, val text: String)

    /** Classic ledger ditto mark, standing in for "same as the line above." */
    private const val DITTO = "\""
    private const val MAX_ENTRIES = 300
    private val entries = ArrayDeque<Entry>()
    private val lock = Any()
    // Tracked separately from entries.last().text: once a repeat collapses to
    // "", the *stored* text no longer reflects what was actually last logged,
    // so comparing against it would fail to recognize a third+ consecutive
    // repeat. This always holds the last real (non-deduped) text logged.
    private var lastRealText: String? = null

    fun log(text: String) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            // Light dedup: noisy repeats (e.g. "speech detected" firing on every
            // ~600ms VAD tick) burn through the 300-entry ring buffer fast,
            // evicting the actually-interesting older entries. A line identical
            // to the immediately preceding one is recorded as a ditto mark
            // instead — still one entry (so timestamps/count stay honest), but
            // the viewer renders a lightweight repeat marker instead of the full
            // text again, without collapsing distinct-but-coincidentally-equal
            // lines that aren't actually consecutive.
            val stored = if (text == lastRealText) DITTO else text
            lastRealText = text
            entries.addLast(Entry(SystemClock.elapsedRealtime(), stored))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) {
        entries.clear()
        lastRealText = null
    }
}
