package com.umassisted.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacilityVocabularyTest {

    @Test
    fun testMatchFacilitySingleWords() {
        assertEquals(0, FacilityVocabulary.matchFacility("Speed"))
        assertEquals(0, FacilityVocabulary.matchFacility("speed"))
        assertEquals(1, FacilityVocabulary.matchFacility("stamina"))
        assertEquals(1, FacilityVocabulary.matchFacility("stam"))
        assertEquals(2, FacilityVocabulary.matchFacility("power"))
        assertEquals(3, FacilityVocabulary.matchFacility("guts"))
        assertEquals(4, FacilityVocabulary.matchFacility("wit"))
        assertEquals(4, FacilityVocabulary.matchFacility("wits"))
        assertEquals(4, FacilityVocabulary.matchFacility("wiz"))
        assertEquals(4, FacilityVocabulary.matchFacility("wisdom"))
        assertEquals(4, FacilityVocabulary.matchFacility("energy"))
    }

    @Test
    fun testMatchFacilityUnknownReturnsNull() {
        assertNull(FacilityVocabulary.matchFacility("unknown"))
        assertNull(FacilityVocabulary.matchFacility("random utterance"))
        // "stam" is a whole-word synonym; it must not fire inside "stamina" only
        // via substring — "stamina" is its own synonym, but "stampede" is not.
        assertNull(FacilityVocabulary.matchFacility("stampede"))
    }

    @Test
    fun testMatchFacilityFromCandidateList() {
        assertEquals(3, FacilityVocabulary.matchFacility(listOf("cats", "guts", "go")))
        assertEquals(0, FacilityVocabulary.matchFacility(listOf("need speed", "speed")))
    }

    @Test
    fun testMatchFacilityPhrasesAndPunctuation() {
        assertEquals(0, FacilityVocabulary.matchFacility("speed training"))
        assertEquals(0, FacilityVocabulary.matchFacility("select speed"))
        assertEquals(3, FacilityVocabulary.matchFacility("guts."))
        assertEquals(3, FacilityVocabulary.matchFacility("Guts!"))
        assertEquals(4, FacilityVocabulary.matchFacility("energy please"))
        assertEquals(1, FacilityVocabulary.matchFacility("stam please"))
        assertEquals(1, FacilityVocabulary.matchFacility("stamina stamina"))
        assertNull(FacilityVocabulary.matchFacility("stamina and speed"))
        assertNull(FacilityVocabulary.matchFacility("speed power"))
    }

    @Test
    fun testMacroPrefersMoreSpecificPhrase() {
        assertEquals(MacroCommand.START_AUTO_RUN_DEFAULTS, FacilityVocabulary.matchMacroCommand(listOf("start auto run defaults")))
        assertEquals(MacroCommand.START_AUTO_RUN, FacilityVocabulary.matchMacroCommand(listOf("start auto run")))
        assertEquals(MacroCommand.START_AUTO_RUN, FacilityVocabulary.matchMacroCommand(listOf("resume career")))
        assertEquals(MacroCommand.START_AUTO_RUN, FacilityVocabulary.matchMacroCommand(listOf("continue career")))
        assertEquals(MacroCommand.START_AUTO_RUN_RECORDING, FacilityVocabulary.matchMacroCommand(listOf("record defaults")))
    }

    @Test
    fun testIsHeartbeatContinuationSignals() {
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("continue")))
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("keep going")))
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("resume")))
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("keep moving")))
        assertFalse(FacilityVocabulary.isHeartbeat(listOf("go")))
        assertFalse(FacilityVocabulary.isHeartbeat(listOf("resume career")))
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("continue sweep")))
    }

    @Test
    fun testIsHeartbeatNonHeartbeat() {
        assertFalse(FacilityVocabulary.isHeartbeat(listOf("speed")))
        assertFalse(FacilityVocabulary.isHeartbeat(listOf("guts")))
        assertFalse(FacilityVocabulary.isHeartbeat(listOf("stop")))
    }
}
