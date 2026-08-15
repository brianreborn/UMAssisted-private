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
        setOf("power"),
        setOf("guts"),
        setOf("wit", "wits")
    )

    private fun normalize(utterance: String): String =
        utterance.trim().lowercase()

    /** Returns the facility index for a recognized utterance, or null if it matches none. */
    fun matchFacility(utterance: String): Int? {
        val norm = normalize(utterance)
        return synonyms.indexOfFirst { norm in it }.takeIf { it >= 0 }
    }

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
    private val heartbeatPhrases = setOf("continue", "keep going", "resume", "go", "keep moving", "continue sweep")

    fun isHeartbeat(candidates: List<String>): Boolean =
        candidates.any { normalize(it) in heartbeatPhrases }

    /** REQ-A19/A20/A21/A26: Named macro command resolution. */
    fun matchMacroCommand(candidates: List<String>): MacroCommand? {
        for (c in candidates) {
            val norm = normalize(c)
            if (norm == "start auto run" || norm == "start run" || norm == "auto run" || norm == "start career") {
                return MacroCommand.START_AUTO_RUN
            }
            if (norm == "start auto run defaults" || norm == "start run defaults" || norm == "auto run defaults") {
                return MacroCommand.START_AUTO_RUN_DEFAULTS
            }
            if (norm == "start auto run recording defaults" || norm == "record defaults") {
                return MacroCommand.START_AUTO_RUN_RECORDING
            }
            if (norm == "finish auto run" || norm == "finish career" || norm == "complete auto run" || norm == "finish run" || norm == "stop auto run") {
                return MacroCommand.FINISH_AUTO_RUN
            }
            if (norm == "super skip" || norm == "max skip" || norm == "full skip" || norm == "fast forward") {
                return MacroCommand.SUPER_SKIP
            }
        }
        return null
    }
}

enum class MacroCommand {
    START_AUTO_RUN,
    START_AUTO_RUN_DEFAULTS,
    START_AUTO_RUN_RECORDING,
    FINISH_AUTO_RUN,
    SUPER_SKIP
}
