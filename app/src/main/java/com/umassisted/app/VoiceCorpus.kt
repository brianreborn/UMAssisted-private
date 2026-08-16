package com.umassisted.app

/**
 * The voice-phrase corpus: every phrase matching currently accepts, plus
 * REQ-V7/V13/V14 defaults that are specified but not wired yet.
 *
 * [resolve] is the single decision used by the live dispatcher and by
 * corpus tests — speak or inject a phrase, get the same answer.
 */
object VoiceCorpus {

    sealed class Match {
        data class Facility(val index: Int, val name: String, val repeats: Int = 1) : Match()
        /** REQ-V22: "$facility Training" — one-shot jump into that facility's
         * training sub-screen from the hub, no arm/confirm (REQ-V4). */
        data class FacilityTraining(val index: Int, val name: String) : Match()
        data class Heartbeat(val phrase: String) : Match()
        data class Macro(val command: MacroCommand, val quick: Boolean = false) : Match()
        data class Cancel(val phrase: String) : Match()
        /** REQ-V23 (narrow case): bare word with no facility attached — tap
         * that literal text if currently visible on screen (OCR bounding box,
         * not a fixed coordinate). Not general any-screen navigation. */
        data class HubButton(val label: String) : Match()
        /** Bare confirm word — only fires if a facility is already armed. */
        data class Confirm(val phrase: String) : Match()
        data object None : Match()
        /** Two different actions in one utterance (e.g. "stamina and speed"). */
        data object Ambiguous : Match()
        /** REQ-V13: turn voice listening off entirely (zero-touch kill switch). */
        data class StopListening(val phrase: String) : Match()
    }

    data class Phrase(val spoken: String, val expected: Match)

    /**
     * What we actually used from the recognizer text to fire an action.
     * [usedParts] are the vocabulary hits in speaking order; [built] is
     * those parts joined — the substring we treated as the command —
     * taken from the full [utterance] hypothesis.
     */
    data class ActionEvidence(
        val utterance: String,
        val usedParts: List<String>,
        val match: Match
    ) {
        val built: String = usedParts.joinToString(" ")

        fun actedOnLine(): String {
            val parts = if (usedParts.isEmpty()) {
                "(none)"
            } else {
                usedParts.joinToString(" + ") { "\"$it\"" }
            }
            val builtBit = if (usedParts.size > 1) " built \"$built\";" else ""
            return "ACTED-ON: $parts$builtBit from \"$utterance\" → $match"
        }
    }

    val implemented: List<Phrase> = listOf(
        // Facilities (REQ-V8/V11/V14)
        Phrase("speed", Match.Facility(0, "Speed")),
        Phrase("stamina", Match.Facility(1, "Stamina")),
        Phrase("stam", Match.Facility(1, "Stamina")),
        Phrase("power", Match.Facility(2, "Power")),
        Phrase("guts", Match.Facility(3, "Guts")),
        Phrase("wit", Match.Facility(4, "Wit")),
        Phrase("wits", Match.Facility(4, "Wit")),
        Phrase("wiz", Match.Facility(4, "Wit")),
        Phrase("wisdom", Match.Facility(4, "Wit")),
        Phrase("energy", Match.Facility(4, "Wit")),
        // Heartbeats (REQ-A23/A24)
        Phrase("continue", Match.Heartbeat("continue")),
        Phrase("keep going", Match.Heartbeat("keep going")),
        Phrase("resume", Match.Heartbeat("resume")),
        Phrase("keep moving", Match.Heartbeat("keep moving")),
        Phrase("continue sweep", Match.Heartbeat("continue sweep")),
        // Macros (REQ-A19/A20/A21/A26)
        Phrase("start auto run", Match.Macro(MacroCommand.START_AUTO_RUN)),
        Phrase("start run", Match.Macro(MacroCommand.START_AUTO_RUN)),
        Phrase("auto run", Match.Macro(MacroCommand.START_AUTO_RUN)),
        Phrase("start career", Match.Macro(MacroCommand.START_AUTO_RUN)),
        Phrase("resume career", Match.Macro(MacroCommand.START_AUTO_RUN)),
        Phrase("continue career", Match.Macro(MacroCommand.START_AUTO_RUN)),
        Phrase("start auto run defaults", Match.Macro(MacroCommand.START_AUTO_RUN_DEFAULTS)),
        Phrase("start run defaults", Match.Macro(MacroCommand.START_AUTO_RUN_DEFAULTS)),
        Phrase("auto run defaults", Match.Macro(MacroCommand.START_AUTO_RUN_DEFAULTS)),
        Phrase("start auto run recording defaults", Match.Macro(MacroCommand.START_AUTO_RUN_RECORDING)),
        Phrase("record defaults", Match.Macro(MacroCommand.START_AUTO_RUN_RECORDING)),
        Phrase("finish auto run", Match.Macro(MacroCommand.FINISH_AUTO_RUN)),
        Phrase("finish career", Match.Macro(MacroCommand.FINISH_AUTO_RUN)),
        Phrase("complete auto run", Match.Macro(MacroCommand.FINISH_AUTO_RUN)),
        Phrase("complete career", Match.Macro(MacroCommand.FINISH_AUTO_RUN)),
        Phrase("finish run", Match.Macro(MacroCommand.FINISH_AUTO_RUN)),
        Phrase("stop auto run", Match.Macro(MacroCommand.FINISH_AUTO_RUN)),
        Phrase("super skip", Match.Macro(MacroCommand.SUPER_SKIP)),
        Phrase("max skip", Match.Macro(MacroCommand.SUPER_SKIP)),
        Phrase("full skip", Match.Macro(MacroCommand.SUPER_SKIP)),
        Phrase("fast forward", Match.Macro(MacroCommand.SUPER_SKIP)),
        Phrase("sweep", Match.Macro(MacroCommand.START_SWEEP)),
        Phrase("auto sweep", Match.Macro(MacroCommand.TOGGLE_SWEEP)),
        // REQ-V19 cancel / retract
        Phrase("cancel", Match.Cancel("cancel")),
        Phrase("oops", Match.Cancel("oops")),
        Phrase("escape", Match.Cancel("escape")),
        Phrase("abort", Match.Cancel("abort")),
        Phrase("no wait", Match.Cancel("no wait")),
    )

    /**
     * REQ-V7/V13/V14 defaults that matching must *not* silently treat as
     * a facility/heartbeat/macro until their dispatch exists.
     */
    val specifiedNotWired: List<String> = listOf(
        "rest", "skills", "infirmary", "recreation", "date", "races",
        "back", "log", "menu",
        "skip on", "skip off", "press skip",
        "quick", "toggle quick", "enable quick", "disable quick",
        "turbo", "turbo mode", "enable turbo", "turbo on", "disable turbo", "turbo off",
        "start listening",
        "first option", "second option", "option one", "option 1",
        "gamble", "safe",
        // "training" (REQ-V23 narrow case -> HubButton), "stop listening"/
        // "mute"/"voice off" (REQ-V13 kill switch) are wired now — moved out
        // of this not-yet-wired list, not removed from the corpus.
    )

    fun resolve(candidates: List<String>): Match = resolveDetailed(candidates).match

    fun resolveDetailed(candidates: List<String>): ActionEvidence {
        for (c in candidates) {
            val one = resolveOneDetailed(c)
            if (one.match !is Match.None) return one
        }
        return ActionEvidence(candidates.firstOrNull().orEmpty(), emptyList(), Match.None)
    }

    /**
     * A single hypothesis is accepted only when it names exactly one action.
     * Repeating that same action ("stamina stamina") is the REQ-V12 confirm
     * form and stays unambiguous. Mixing two different facilities, or a
     * facility with a heartbeat/macro, is rejected.
     */
    fun resolveOne(utterance: String): Match = resolveOneDetailed(utterance).match

    private val cancelPhrases = listOf(
        "no wait", "never mind", "nevermind", "cancel", "oops", "escape", "abort", "iie"
    )

    /** REQ-V13: zero-touch kill switch. "start listening" is deliberately not
     * in this corpus — it must work while voice is already OFF, which needs
     * an always-on low-power wake path this recognizer doesn't have yet. */
    private val stopListeningPhrases = listOf("stop listening", "mute", "voice off")

    fun matchingStopListeningPhrase(utterance: String): String? {
        val norm = normalizeUtterance(utterance)
        if (norm.isEmpty()) return null
        for (phrase in stopListeningPhrases.sortedByDescending { it.length }) {
            if (norm == phrase) return phrase
        }
        return null
    }

    /** Same-utterance or follow-up confirm (REQ-V12). Not valid alone unless armed. */
    private val confirmPhrases = listOf("do it", "okay", "confirm", "ok", "yes", "go", "roger", "ryoukai", "hai")

    // Was a byte-identical private copy of FacilityVocabulary.normalize —
    // calling that directly instead removes the duplicate (and the risk of
    // the two drifting apart, which already happened once in-session before
    // being caught).
    private fun normalizeUtterance(utterance: String): String = FacilityVocabulary.normalize(utterance)

    fun matchingCancelPhrase(utterance: String): String? {
        val norm = normalizeUtterance(utterance)
        if (norm.isEmpty()) return null
        for (phrase in cancelPhrases.sortedByDescending { it.length }) {
            if (norm == phrase) return phrase
            if (norm.endsWith(" $phrase")) {
                val prefix = norm.removeSuffix(phrase).trim()
                    .split(' ').filter { it.isNotEmpty() }
                if (prefix.size <= 1) return phrase
            }
        }
        return null
    }

    fun confirmPartsIn(utterance: String): List<String> {
        val norm = normalizeUtterance(utterance)
        if (norm.isEmpty()) return emptyList()
        val parts = mutableListOf<String>()
        for (phrase in confirmPhrases.sortedByDescending { it.length }) {
            if (norm == phrase ||
                norm.startsWith("$phrase ") ||
                norm.endsWith(" $phrase") ||
                norm.contains(" $phrase ")
            ) {
                parts.add(phrase)
            }
        }
        return parts.distinct()
    }

    /** Whole utterance is a confirm word (optional one-word filler). */
    fun matchingBareConfirmPhrase(utterance: String): String? {
        val norm = normalizeUtterance(utterance)
        if (norm.isEmpty()) return null
        if (FacilityVocabulary.facilityHits(utterance).isNotEmpty()) return null
        for (phrase in confirmPhrases.sortedByDescending { it.length }) {
            if (norm == phrase) return phrase
            if (norm.endsWith(" $phrase")) {
                val prefix = norm.removeSuffix(phrase).trim()
                    .split(' ').filter { it.isNotEmpty() }
                if (prefix.size <= 1) return phrase
            }
        }
        return null
    }

    fun resolveOneDetailed(utterance: String): ActionEvidence {
        // Checked before everything else — a kill switch must win over any
        // other interpretation, same priority as REQ-V19's cancel vocabulary.
        matchingStopListeningPhrase(utterance)?.let { phrase ->
            return ActionEvidence(utterance, listOf(phrase), Match.StopListening(phrase))
        }
        matchingCancelPhrase(utterance)?.let { phrase ->
            return ActionEvidence(utterance, listOf(phrase), Match.Cancel(phrase))
        }
        // REQ-V23 (narrow case): bare "training" or "facilities", no facility
        // name attached — tap the literal "Training" text if it's actually on
        // screen right now. "facilities" means the same destination as bare
        // "training" (there's no on-screen "Facilities" label to tap), so both
        // resolve to the same HubButton target. Checked before facility
        // processing since this utterance has none.
        val normalized = normalizeUtterance(utterance)
        if (normalized == "training" || normalized == "facilities") {
            return ActionEvidence(utterance, listOf(normalized), Match.HubButton("Training"))
        }
        val facDetailed = FacilityVocabulary.facilityHitsDetailed(utterance)
        val facDistinct = facDetailed.map { it.index }.distinct()
        val macroHit = FacilityVocabulary.matchingMacroPhrase(utterance)
        val heartbeatPhrase = FacilityVocabulary.matchingHeartbeatPhrase(utterance)
        val heartbeatToken = FacilityVocabulary.heartbeatTokenIn(utterance)

        // REQ-V22: "$facility Training" is a distinct one-shot command, checked
        // before the ambiguous-facility and arm/confirm paths below — "training"
        // is not itself a facility/heartbeat/macro word, so without this check it
        // would just be silently dropped as noise and fall through to a plain
        // arm/confirm Facility match instead of the direct jump this is for.
        if (facDistinct.size == 1 &&
            normalizeUtterance(utterance).split(' ').contains("training")
        ) {
            val index = facDistinct.single()
            return ActionEvidence(
                utterance,
                facDetailed.map { it.phrase } + "training",
                Match.FacilityTraining(index, FacilityVocabulary.facilityNames[index])
            )
        }

        if (facDistinct.size > 1) {
            return ActionEvidence(utterance, facDetailed.map { it.phrase }, Match.Ambiguous)
        }
        // "continue speed" is two intents even though "continue speed" is
        // not itself a heartbeat phrase.
        if (facDistinct.isNotEmpty() && heartbeatToken != null && macroHit == null) {
            return ActionEvidence(
                utterance,
                listOfNotNull(heartbeatToken) + facDetailed.map { it.phrase },
                Match.Ambiguous
            )
        }

        val hits = mutableListOf<Pair<Match, List<String>>>()
        // "resume" / "continue" are heartbeats, but also prefixes of "resume
        // career" / "continue career" — there the macro phrase is longer and
        // wins. The reverse also happens: "continue sweep" is itself a whole
        // heartbeat phrase (REQ-A24 continuation signal), but also contains
        // the word "sweep," which REQ-A32 registered as its own bare macro
        // phrase — there the HEARTBEAT phrase is longer and must win, or
        // "continue sweep" wrongly resolves Ambiguous instead of the
        // continuation signal it actually is. Whichever phrase is longer
        // (more specific) wins in either direction; only a genuine overlap
        // with neither containing the other is real mixed intent.
        val heartbeatOverlapsMacro = macroHit != null && heartbeatPhrase != null &&
            (macroHit.second.contains(heartbeatPhrase) || heartbeatPhrase.contains(macroHit.second))
        if (heartbeatOverlapsMacro) {
            if (heartbeatPhrase!!.length >= macroHit!!.second.length) {
                hits.add(Match.Heartbeat(heartbeatPhrase) to listOf(heartbeatPhrase))
            } else {
                hits.add(Match.Macro(macroHit.first, macroHit.third) to listOf(macroHit.second))
            }
        } else {
            if (macroHit != null) {
                hits.add(Match.Macro(macroHit.first, macroHit.third) to listOf(macroHit.second))
            }
            if (heartbeatPhrase != null) {
                hits.add(Match.Heartbeat(heartbeatPhrase) to listOf(heartbeatPhrase))
            }
        }
        val confirmParts = confirmPartsIn(utterance)
        facDistinct.singleOrNull()?.let { index ->
            val nameHits = facDetailed.size.coerceAtLeast(1)
            val repeats = if (nameHits >= 2 || confirmParts.isNotEmpty()) 2 else 1
            hits.add(
                Match.Facility(
                    index,
                    FacilityVocabulary.facilityNames[index],
                    repeats = repeats
                ) to (facDetailed.map { it.phrase } + confirmParts)
            )
        }

        if (hits.isEmpty()) {
            matchingBareConfirmPhrase(utterance)?.let { phrase ->
                return ActionEvidence(utterance, listOf(phrase), Match.Confirm(phrase))
            }
        }

        return when (hits.size) {
            0 -> ActionEvidence(utterance, emptyList(), Match.None)
            1 -> ActionEvidence(utterance, hits.single().second, hits.single().first)
            else -> ActionEvidence(
                utterance,
                hits.flatMap { it.second }.distinct(),
                Match.Ambiguous
            )
        }
    }
}
