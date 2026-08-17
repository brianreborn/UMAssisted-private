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

    /**
     * How many edits a pattern of this length is allowed to tolerate and
     * still count as a confident match.
     *
     * Deliberately requires an EXACT match (0 edits) below 6 characters,
     * not just a lower ratio — found via code review plus this file's own
     * unit tests: short/common English words sit only 1 edit from several
     * rule patterns ("next"/"text", "close"/"chose", "goals"/"goal" are all
     * single-substitution-or-insertion neighbors), so any fuzz tolerance on
     * a <6-char pattern risks a false "no choice" verdict on a real choice
     * screen whose unrelated on-screen text happens to contain the
     * neighboring word — worse than missing an OCR misread on that word.
     * 1-2 character OCR-typo tolerance only kicks in once the pattern is
     * long enough that an accidental 1-edit collision with an unrelated
     * real word becomes rare. This is a blunt, a-priori heuristic, not a
     * calibrated one — REQ-M6/OQ-31 already flags that these thresholds
     * need real empirical tuning; treat this as a safe starting default,
     * not a finished answer.
     */
    private fun allowedEdits(patternLength: Int): Int = when {
        patternLength < 6 -> 0
        patternLength <= 9 -> 1
        else -> 2
    }

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
     * Best edit distance of [pattern] against any *substring* of [haystack]
     * ending anywhere, computed in one O(haystack.length * pattern.length)
     * pass (the standard approximate-substring-match DP: zero-initialize the
     * first row so the pattern is free to start matching at any haystack
     * position, then take the minimum of the last row as the best
     * end-of-match distance).
     *
     * Replaces an earlier fixed-width sliding-window version that computed a
     * full edit distance from scratch at every haystack position — besides
     * being O(haystack.length * pattern.length^2), that version's windows
     * were always sized pattern.length+slack, so a match starting mid-window
     * (i.e. anywhere except the last couple characters of the haystack) was
     * forced to absorb trailing junk into the distance and would rarely
     * clear the confidence gate at all. Found via code review, reproduced:
     * the gate's own worked example ("ciose" -> "close") scored 0.80 in
     * isolation but 0.40 once embedded in a realistic sentence.
     */
    private fun bestEditDistance(haystack: String, pattern: String): Int {
        if (pattern.isEmpty()) return 0
        if (haystack.isEmpty()) return pattern.length
        val m = pattern.length
        val prev = IntArray(m + 1) { it }
        val curr = IntArray(m + 1)
        var best = Int.MAX_VALUE
        for (hc in haystack) {
            curr[0] = 0 // pattern may start matching here for free
            for (j in 1..m) {
                val cost = if (hc == pattern[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,      // insertion
                    prev[j] + 1,          // deletion
                    prev[j - 1] + cost    // substitution
                )
            }
            // The answer is the minimum of the FULL-PATTERN column (curr[m])
            // across all haystack positions, not the minimum of any one row —
            // curr[0] is trivially 0 every row (an empty pattern prefix always
            // "matches" for free), so scanning a whole row for its minimum
            // picks that up and always returns 0. Track curr[m] as we go.
            if (curr[m] < best) best = curr[m]
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return best
    }

    /**
     * Normalized similarity (0..1, 1 = exact) of [pattern] against the best
     * matching substring of [haystack], and whether it clears the
     * length-tiered [allowedEdits] gate. Returns similarity=1.0 immediately
     * for a literal substring match without running the DP.
     */
    private fun fuzzyScore(haystack: String, pattern: String): Double {
        if (pattern.isEmpty()) return 0.0
        if (haystack.contains(pattern)) return 1.0
        val dist = bestEditDistance(haystack, pattern)
        if (dist > allowedEdits(pattern.length)) return 0.0
        return (1.0 - dist.toDouble() / pattern.length).coerceIn(0.0, 1.0)
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
            if (score <= 0.0) continue
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
        return MatchResult(false, false, "no rule matched above confidence gate")
    }

    /** Logs an already-computed [result] — call match() once per OCR text, not twice. */
    fun logMatch(result: MatchResult) {
        Log.i(TAG, "match result: matched=${result.matched} noChoice=${result.isNoChoice} reason=${result.reason}")
    }
}
