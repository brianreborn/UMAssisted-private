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
    private val heartbeatPhrases = setOf("continue", "keep going")

    fun isHeartbeat(candidates: List<String>): Boolean =
        candidates.any { normalize(it) in heartbeatPhrases }
}
