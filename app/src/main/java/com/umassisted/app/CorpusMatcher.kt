package com.umassisted.app

import android.util.Log

/**
 * Extremely simple alpha corpus matcher.
 *
 * In a real build this would:
 * - Load event text from a bundled master.mdb extract (REQ-M5)
 * - Load hand-labeled generic-UI entries with "no-choice" / "has-choice" flags (REQ-F4)
 * - Do fuzzy matching against OCR output.
 *
 * For now we have a tiny hand-seeded rule set derived from the public corpus
 * (screenshots/SESSION_NOTES.md) so we can demonstrate the flow.
 */
object CorpusMatcher {

    private const val TAG = "CorpusMatcher"

    // Seeded from common patterns observed in the Aoharu Hai / Unity Cup corpus.
    // Each entry: (substring, isNoChoice)
    // Keep this small and conservative for alpha. New patterns should come from labeled corpus review.
    private val rules = listOf(
        // Safe no-choice / advance actions (generic UI) — from SESSION_NOTES + labels
        "close" to true,
        "close_button" to true,
        "next" to true,
        "next_button" to true,
        "ok" to true,
        "confirm" to true,
        "cancel_ok" to true,
        "race" to true,
        "enter race" to true,
        "enter" to true,
        "results" to true,
        "watch concert" to true,
        "replay" to true,
        "skip" to true,
        "done" to true,
        "finish" to true,
        "continue" to true,
        "goals" to true,
        "goals_modal" to true,

        // Menu / exit flows (from labeled give_up + snap12 + misc)
        "save & exit" to true,
        "give up" to true,
        "give_up" to true,
        "menu" to true,
        "hamburger" to true,

        // Spirit burst / GO style (when it is a pure "go" without choice)
        // Note: many GO prompts are actually "has choice" in spirit burst; we stay conservative here.

        // Things that clearly have real choices → never auto-advance
        "go for it" to false,
        "choose" to false,
        "select" to false,
        "effects" to false,
        "fulcrum" to false,
        "giving it your all" to false,
        "inspiration" to false,
        "scheduled race" to false,
        "warning" to false,
        "insufficient fans" to false,
    )

    data class MatchResult(
        val matched: Boolean,
        val isNoChoice: Boolean,
        val reason: String
    )

    fun match(ocrText: String): MatchResult {
        if (ocrText.isBlank()) {
            return MatchResult(false, false, "empty")
        }

        val lower = ocrText.lowercase()

        // Check negative signals first (has real choice)
        for ((pattern, isNoChoice) in rules) {
            if (!isNoChoice && lower.contains(pattern)) {
                return MatchResult(true, false, "choice signal: $pattern")
            }
        }

        // Check positive no-choice signals
        for ((pattern, isNoChoice) in rules) {
            if (isNoChoice && lower.contains(pattern)) {
                return MatchResult(true, true, "no-choice: $pattern")
            }
        }

        // Very conservative default for alpha: unknown → treat as has choice
        return MatchResult(false, false, "no rule matched")
    }

    fun logMatch(ocrText: String) {
        val res = match(ocrText)
        Log.i(TAG, "match result: matched=${res.matched} noChoice=${res.isNoChoice} reason=${res.reason}")
    }
}
