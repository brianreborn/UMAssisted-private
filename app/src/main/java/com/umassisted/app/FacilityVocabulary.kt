package com.umassisted.app

/**
 * Maps a recognized voice utterance to a training facility index (REQ-V8/V11:
 * multiple phrases per action, user-definable in a later revision — this is
 * the default set). Facility order/index matches the physical row used
 * throughout UMAssistedAccessibilityService: [Speed, Stamina, Power, Guts, Wit].
 */
object FacilityVocabulary {

    val facilityNames = listOf("Speed", "Stamina", "Power", "Guts", "Wit")

    private val synonyms: List<Set<String>> = listOf(
        setOf("speed"),
        setOf("stamina", "stam"),
        setOf("power", "pow"),
        setOf("guts"),
        // REQ-V8: "energy" is a required default synonym for Wit.
        setOf(
            "wit", "wits", "wiz", "wisdom", "energy",
            // REQ-V24: this engine's consistent mishearing of "wit" — every
            // alternate hypothesis for "wit wit" came back as some form of
            // "wait" on-device, never "wit" itself — listed here as a
            // synonym rather than a word to somehow train the ASR out of.
            "wait",
        )
    )

    private fun normalize(utterance: String): String =
        utterance.trim().lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * True if [phrase] occurs as a whole-word sequence inside [normalized]
     * (both already lowercase, punctuation stripped). "speed" matches
     * "speed training" and "select speed"; "stam" does not match "stamina".
     */
    private fun containsPhrase(normalized: String, phrase: String): Boolean {
        if (normalized == phrase) return true
        if (normalized.startsWith("$phrase ")) return true
        if (normalized.endsWith(" $phrase")) return true
        return normalized.contains(" $phrase ")
    }

    data class FacilityHit(val index: Int, val phrase: String)

    /**
     * Facility mentions in [utterance], in speaking order, with the exact
     * synonym word that hit. Repeating the same facility ("stamina stamina")
     * yields two hits of the same index; naming two different ones
     * ("stamina and speed") yields two distinct indices.
     */
    fun facilityHitsDetailed(utterance: String): List<FacilityHit> {
        val norm = normalize(utterance)
        if (norm.isEmpty()) return emptyList()
        synonyms.forEachIndexed { index, syns ->
            if (norm in syns) return listOf(FacilityHit(index, norm))
        }
        val hits = mutableListOf<FacilityHit>()
        for (word in norm.split(' ').filter { it.isNotEmpty() }) {
            val index = synonyms.indexOfFirst { word in it }
            if (index >= 0) hits.add(FacilityHit(index, word))
        }
        return hits
    }

    fun facilityHits(utterance: String): List<Int> =
        facilityHitsDetailed(utterance).map { it.index }

    /**
     * Heartbeat only if the whole utterance *is* the phrase (or the phrase
     * plus trivial filler). Contains-match on "resume"/"continue" was
     * treating game lines and "resume career" fragments as sweep restarts.
     */
    /** Single-word heartbeat token present as a whole word, or null. */
    fun heartbeatTokenIn(utterance: String): String? {
        val tokens = normalize(utterance).split(' ').filter { it.isNotEmpty() }
        val singles = heartbeatPhrases.filter { ' ' !in it }
        return tokens.firstOrNull { it in singles }
    }

    fun matchingHeartbeatPhrase(utterance: String): String? {
        val norm = normalize(utterance)
        if (norm.isEmpty()) return null
        for (phrase in heartbeatPhrases.sortedByDescending { it.length }) {
            if (norm == phrase) return phrase
            if (norm.endsWith(" $phrase")) {
                val prefixWords = norm.removeSuffix(phrase).trim()
                    .split(' ').filter { it.isNotEmpty() }
                // One filler word only — "please continue", not "resume career".
                if (prefixWords.size <= 1) return phrase
            }
        }
        return null
    }

    /**
     * Winning macro phrase (most specific), the command it maps to, and
     * whether the REQ-A27 "quickly" modifier was present (leading or
     * trailing) — stripped before matching so "quickly complete career" /
     * "complete career quickly" resolve as the same command as the bare
     * phrase, with the modifier surfaced separately for callers that act on
     * it (currently: skip the skill-purchase-before-exit detour). Narrow,
     * hardcoded handling for this one modifier only; OQ-58 tracks the
     * general modifier-parsing redesign this isn't meant to preempt.
     */
    fun matchingMacroPhrase(utterance: String): Triple<MacroCommand, String, Boolean>? {
        var norm = normalize(utterance)
        if (norm.isEmpty()) return null
        var quick = false
        if (norm.startsWith("quickly ")) {
            norm = norm.removePrefix("quickly ").trim()
            quick = true
        } else if (norm.endsWith(" quickly")) {
            norm = norm.removeSuffix("quickly").trim()
            quick = true
        }
        val groups = listOf(
            MacroCommand.START_AUTO_RUN_RECORDING to listOf(
                "start auto run recording defaults", "record defaults"
            ),
            MacroCommand.START_AUTO_RUN_DEFAULTS to listOf(
                "start auto run defaults", "start run defaults", "auto run defaults"
            ),
            MacroCommand.FINISH_AUTO_RUN to listOf(
                "finish auto run", "finish career", "complete auto run", "complete career",
                "finish run", "stop auto run"
            ),
            MacroCommand.START_AUTO_RUN to listOf(
                "start auto run", "start run", "auto run", "start career",
                "resume career", "continue career"
            ),
            MacroCommand.SUPER_SKIP to listOf(
                "super skip", "max skip", "full skip", "fast forward"
            ),
            // Longer/more specific phrase checked first — same pattern as the
            // START_AUTO_RUN family above. "sweep" is a whole word inside
            // "auto sweep" too, so TOGGLE_SWEEP must be tried before the bare
            // START_SWEEP phrase or "auto sweep" would resolve to the wrong one.
            MacroCommand.TOGGLE_SWEEP to listOf("auto sweep"),
            MacroCommand.START_SWEEP to listOf("sweep"),
        )
        for ((cmd, phrases) in groups) {
            val hit = phrases.firstOrNull { containsPhrase(norm, it) } ?: continue
            return Triple(cmd, hit, quick)
        }
        return null
    }

    /**
     * One facility only. Repeating the same name is allowed (REQ-V12 arm/confirm
     * in a single utterance); two *different* facilities is ambiguous → null.
     */
    fun matchFacility(utterance: String): Int? =
        facilityHits(utterance).distinct().singleOrNull()

    /**
     * Matches against every recognition alternate (not just the top one) — the
     * recognizer's ASR_CONFIDENCE ranking is about acoustic likelihood, not
     * membership in our closed vocabulary, so a correct short word can easily
     * land in alternate #2+ rather than #1. First alternate that matches wins.
     */
    fun matchFacility(candidates: List<String>): Int? {
        for (c in candidates) {
            matchFacility(c)?.let { return it }
        }
        return null
    }

    /** REQ-A23/A24: dedicated continuation-signal phrase to restart the sweep, distinct from a facility name. */
    // "go" is deliberately absent — too short, collides with game VO, and
    // was firing the sweep restart on every guessed fragment.
    private val heartbeatPhrases = setOf("continue", "keep going", "resume", "keep moving", "continue sweep")

    fun isHeartbeat(candidates: List<String>): Boolean =
        candidates.any { matchingHeartbeatPhrase(it) != null }

    /** REQ-A19/A20/A21/A26: Named macro command resolution. */
    fun matchMacroCommand(candidates: List<String>): MacroCommand? {
        for (c in candidates) {
            matchingMacroPhrase(c)?.let { return it.first }
        }
        return null
    }
}

enum class MacroCommand {
    START_AUTO_RUN,
    START_AUTO_RUN_DEFAULTS,
    START_AUTO_RUN_RECORDING,
    FINISH_AUTO_RUN,
    SUPER_SKIP,
    /** REQ-A9/A10: arm the training sweep (if not already) and run one pass
     * immediately — a single spoken command for the whole "get sweep going"
     * intent, rather than requiring a separate touch to arm before any
     * voice heartbeat can start a pass. */
    START_SWEEP,
    /** REQ-A10: toggle sweep armed/disarmed, same effect as tapping the
     * overlay's 🧹 cell — arms or disarms the *mode* only, does not itself
     * run a pass (distinct from START_SWEEP, which also runs one). */
    TOGGLE_SWEEP
}
