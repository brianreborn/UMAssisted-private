package com.umassisted.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCorpusTest {

    @Test
    fun everyImplementedPhraseResolvesToItsExpectedMatch() {
        val failures = mutableListOf<String>()
        for (entry in VoiceCorpus.implemented) {
            val got = VoiceCorpus.resolve(listOf(entry.spoken))
            if (got != entry.expected) {
                failures.add("'${entry.spoken}' expected ${entry.expected} got $got")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun implementedPhrasesAreUnique() {
        val dupes = VoiceCorpus.implemented.groupBy { it.spoken.lowercase() }
            .filter { it.value.size > 1 }
            .keys
        assertTrue("duplicate phrases: $dupes", dupes.isEmpty())
    }

    @Test
    fun specifiedNotWiredPhrasesDoNotSilentlyMatch() {
        val leaks = mutableListOf<String>()
        for (phrase in VoiceCorpus.specifiedNotWired) {
            val got = VoiceCorpus.resolve(listOf(phrase))
            if (got != VoiceCorpus.Match.None) {
                leaks.add("'$phrase' leaked as $got")
            }
        }
        assertTrue(leaks.joinToString("\n"), leaks.isEmpty())
    }

    @Test
    fun staminaPunctuationAndFillerStillMatchFacility() {
        // The 967f040 on-device observation that OQ-44 recorded:
        assertEquals(VoiceCorpus.Match.Facility(1, "Stamina"), VoiceCorpus.resolve(listOf("stamina.")))
        assertEquals(VoiceCorpus.Match.Facility(1, "Stamina"), VoiceCorpus.resolve(listOf("um, stamina")))
    }

    @Test
    fun mixedFacilitiesAreAmbiguousExceptSameNameTwice() {
        assertEquals(VoiceCorpus.Match.Ambiguous, VoiceCorpus.resolve(listOf("speed power")))
        assertEquals(VoiceCorpus.Match.Ambiguous, VoiceCorpus.resolve(listOf("Stamina and speed")))
        assertEquals(VoiceCorpus.Match.Ambiguous, VoiceCorpus.resolve(listOf("continue speed")))
        // Same facility twice — the confirm form — is unambiguous.
        assertEquals(
            VoiceCorpus.Match.Facility(1, "Stamina", repeats = 2),
            VoiceCorpus.resolve(listOf("stamina stamina"))
        )
        assertEquals(
            VoiceCorpus.Match.Facility(1, "Stamina", repeats = 2),
            VoiceCorpus.resolve(listOf("stam stamina"))
        )
    }

    @Test
    fun moreSpecificMacroWinsOverShorterPrefix() {
        assertEquals(
            VoiceCorpus.Match.Macro(MacroCommand.START_AUTO_RUN_DEFAULTS),
            VoiceCorpus.resolve(listOf("start auto run defaults"))
        )
        assertEquals(
            VoiceCorpus.Match.Macro(MacroCommand.START_AUTO_RUN_RECORDING),
            VoiceCorpus.resolve(listOf("start auto run recording defaults"))
        )
        assertEquals(
            VoiceCorpus.Match.Macro(MacroCommand.FINISH_AUTO_RUN),
            VoiceCorpus.resolve(listOf("finish auto run"))
        )
        assertEquals(
            VoiceCorpus.Match.Macro(MacroCommand.FINISH_AUTO_RUN),
            VoiceCorpus.resolve(listOf("stop auto run"))
        )
        assertEquals(
            VoiceCorpus.Match.Macro(MacroCommand.START_AUTO_RUN),
            VoiceCorpus.resolve(listOf("resume career"))
        )
        assertEquals(
            VoiceCorpus.Match.Macro(MacroCommand.START_AUTO_RUN),
            VoiceCorpus.resolve(listOf("continue career"))
        )
        // Bare "resume" stays the sweep heartbeat, not the career macro.
        assertEquals(
            VoiceCorpus.Match.Heartbeat("resume"),
            VoiceCorpus.resolve(listOf("resume"))
        )
        val resumeCareer = VoiceCorpus.resolveOneDetailed("resume career")
        assertEquals(listOf("resume career"), resumeCareer.usedParts)
        assertEquals(VoiceCorpus.Match.Macro(MacroCommand.START_AUTO_RUN), resumeCareer.match)
    }

    @Test
    fun sameUtteranceConfirmExecutesFacility() {
        for (spoken in listOf("stamina, ok", "stamina, go", "Stamina, confirm", "stam stam")) {
            val got = VoiceCorpus.resolve(listOf(spoken))
            assertTrue("$spoken → $got", got is VoiceCorpus.Match.Facility)
            val fac = got as VoiceCorpus.Match.Facility
            assertEquals(spoken, 1, fac.index)
            assertEquals(2, fac.repeats)
        }
        assertEquals(VoiceCorpus.Match.Confirm("ok"), VoiceCorpus.resolve(listOf("ok")))
        assertEquals(VoiceCorpus.Match.Confirm("go"), VoiceCorpus.resolve(listOf("go")))
        assertEquals(VoiceCorpus.Match.Confirm("confirm"), VoiceCorpus.resolve(listOf("confirm")))
    }

    @Test
    fun cancelVocabularyIsUnambiguousAndImmediate() {
        for (phrase in listOf("cancel", "oops", "escape", "abort", "no wait", "please cancel")) {
            val got = VoiceCorpus.resolve(listOf(phrase))
            assertTrue(
                "'$phrase' should cancel, got $got",
                got is VoiceCorpus.Match.Cancel
            )
        }
        assertEquals(VoiceCorpus.Match.Cancel("cancel"), VoiceCorpus.resolve(listOf("cancel")))
        assertEquals(VoiceCorpus.Match.Cancel("oops"), VoiceCorpus.resolve(listOf("oops")))
    }

    @Test
    fun actedOnEvidenceNamesTheSubstringBuiltFromParts() {
        val filler = VoiceCorpus.resolveOneDetailed("um, stamina")
        assertEquals(listOf("stamina"), filler.usedParts)
        assertEquals("stamina", filler.built)
        assertEquals("um, stamina", filler.utterance)
        assertTrue(filler.actedOnLine().contains("\"stamina\""))
        assertTrue(filler.actedOnLine().contains("from \"um, stamina\""))

        val twice = VoiceCorpus.resolveOneDetailed("stam stamina")
        assertEquals(listOf("stam", "stamina"), twice.usedParts)
        assertEquals("stam stamina", twice.built)
        assertTrue(twice.actedOnLine().contains("built \"stam stamina\""))

        val mixed = VoiceCorpus.resolveOneDetailed("Stamina and speed")
        assertEquals(VoiceCorpus.Match.Ambiguous, mixed.match)
        assertEquals(listOf("stamina", "speed"), mixed.usedParts)

        val keep = VoiceCorpus.resolveOneDetailed("please keep going")
        assertEquals(listOf("keep going"), keep.usedParts)
        assertEquals(
            VoiceCorpus.Match.Heartbeat("keep going"),
            keep.match
        )
    }
}
