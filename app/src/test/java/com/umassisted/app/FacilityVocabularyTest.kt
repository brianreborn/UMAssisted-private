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
    }

    @Test
    fun testMatchFacilityUnknownReturnsNull() {
        assertNull(FacilityVocabulary.matchFacility("unknown"))
        assertNull(FacilityVocabulary.matchFacility("random utterance"))
    }

    @Test
    fun testMatchFacilityFromCandidateList() {
        assertEquals(3, FacilityVocabulary.matchFacility(listOf("cats", "guts", "go")))
        assertEquals(0, FacilityVocabulary.matchFacility(listOf("need speed", "speed")))
    }

    @Test
    fun testIsHeartbeatContinuationSignals() {
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("continue")))
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("keep going")))
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("resume")))
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("go")))
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("keep moving")))
        assertTrue(FacilityVocabulary.isHeartbeat(listOf("continue sweep")))
    }

    @Test
    fun testIsHeartbeatNonHeartbeat() {
        assertFalse(FacilityVocabulary.isHeartbeat(listOf("speed")))
        assertFalse(FacilityVocabulary.isHeartbeat(listOf("guts")))
        assertFalse(FacilityVocabulary.isHeartbeat(listOf("stop")))
    }
}
