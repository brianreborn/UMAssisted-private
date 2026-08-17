package com.umassisted.app

import android.util.Log

/**
 * OQ-49 Stage 1 corpus matcher: real fuzzy matching + a confidence gate
 * against a hand-seeded phrase set (REQ-M6's "never silently pick the
 * closest of a bad set" rule). Stage 2 (REQ-M5/M7's real event/generic-UI
 * corpus, the visual-match fallback, scrollbar geometry) is the remaining
 * gap — this stage only replaces the matching *mechanism* (exact
 * substring -> fuzzy, edit-distance-gated), not the data source.
 */
object CorpusMatcher {

    private const val TAG = "CorpusMatcher"

    // Below this normalized similarity, a candidate is not trusted — REQ-M6's
    // confidence gate. Picked to tolerate a 1-2 character OCR misread on a
    // short phrase (e.g. "ciose" -> "close") without accepting a wrong guess.
    private const val CONFIDENCE_THRESHOLD = 0.80

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
        val reason: String,
        val confidence: Double = 0.0
    )

    /** Levenshtein edit distance between two strings. */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,      // insertion
                    prev[j] + 1,          // deletion
                    prev[j - 1] + cost    // substitution
                )
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }

    /**
     * Best-match similarity of [pattern] against any equal-length-ish window of
     * [haystack], normalized to 0..1 (1 = exact). OCR text is a whole screen's
     * worth of lines, not just the target phrase, so we slide a window sized to
     * the pattern rather than diffing the entire haystack against it.
     */
    private fun fuzzyScore(haystack: String, pattern: String): Double {
        if (pattern.isEmpty()) return 0.0
        if (haystack.contains(pattern)) return 1.0
        if (haystack.length < pattern.length) {
            return 1.0 - editDistance(haystack, pattern).toDouble() / pattern.length
        }
        var best = Int.MAX_VALUE
        // Allow the window to run a couple chars short/long of the pattern to
        // absorb OCR insertions/drops without needing a fully general alignment.
        val slack = 2
        for (start in 0 until haystack.length) {
            val end = minOf(start + pattern.length + slack, haystack.length)
            val window = haystack.substring(start, end)
            val dist = editDistance(window, pattern)
            if (dist < best) best = dist
        }
        val similarity = 1.0 - best.toDouble() / pattern.length
        return similarity.coerceIn(0.0, 1.0)
    }

    fun match(ocrText: String): MatchResult {
        if (ocrText.isBlank()) {
            return MatchResult(false, false, "empty")
        }

        // normalizedForMatch, not plain .lowercase(): several rules below are
        // multi-word ("save & exit", "give up", "go for it", ...) and ML
        // Kit's OCR text joins separately-detected lines with `\n` — the
        // same bug class confirmed live elsewhere in this app (see
        // AutoRunMacro.normalizedForMatch's doc comment) applies here too,
        // upstream of every macro/voice decision that consults this result.
        val lower = AutoRunMacros.normalizedForMatch(ocrText)

        // Score every rule, keep only candidates that clear the confidence
        // gate — REQ-M6's "never silently pick the closest of a bad set"
        // rule. Negative (has-choice) signals are still checked first and
        // win ties, same conservative priority as the original stub.
        var bestChoice: Pair<String, Double>? = null
        var bestNoChoice: Pair<String, Double>? = null
        for ((pattern, isNoChoice) in rules) {
            val score = fuzzyScore(lower, pattern)
            if (score < CONFIDENCE_THRESHOLD) continue
            if (isNoChoice) {
                if (bestNoChoice == null || score > bestNoChoice!!.second) bestNoChoice = pattern to score
            } else {
                if (bestChoice == null || score > bestChoice!!.second) bestChoice = pattern to score
            }
        }

        bestChoice?.let { (pattern, score) ->
            return MatchResult(true, false, "choice signal: $pattern (confidence=%.2f)".format(score), score)
        }
        bestNoChoice?.let { (pattern, score) ->
            return MatchResult(true, true, "no-choice: $pattern (confidence=%.2f)".format(score), score)
        }

        // Very conservative default for alpha: unknown or below confidence
        // gate → treat as has choice, never silently pick the closest guess.
        return MatchResult(false, false, "no rule matched above confidence gate ($CONFIDENCE_THRESHOLD)")
    }

    fun logMatch(ocrText: String) {
        val res = match(ocrText)
        Log.i(TAG, "match result: matched=${res.matched} noChoice=${res.isNoChoice} reason=${res.reason}")
    }
}
