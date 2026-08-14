package com.umassisted.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFacilitySelectionTest {

    @Test
    fun testArmAndConfirmWorkflow() {
        val confirmWindowMs = 3000L
        val selection = VoiceFacilitySelection { confirmWindowMs }

        // First utterance: Arm
        val action1 = selection.onFacilityUtterance(facilityIndex = 3, nowMs = 1000L)
        assertTrue(action1 is VoiceFacilitySelection.Action.Arm)
        assertEquals(3, (action1 as VoiceFacilitySelection.Action.Arm).facilityIndex)
        assertEquals(3, selection.currentlyArmed())

        // Repeat same utterance within confirm window: Confirm
        val action2 = selection.onFacilityUtterance(facilityIndex = 3, nowMs = 2500L)
        assertTrue(action2 is VoiceFacilitySelection.Action.Confirm)
        assertEquals(3, (action2 as VoiceFacilitySelection.Action.Confirm).facilityIndex)
        assertEquals(null, selection.currentlyArmed())
    }

    @Test
    fun testReArmWorkflowDifferentFacility() {
        val selection = VoiceFacilitySelection { 3000L }

        selection.onFacilityUtterance(facilityIndex = 0, nowMs = 1000L) // Arm Speed
        val action = selection.onFacilityUtterance(facilityIndex = 2, nowMs = 2000L) // Speak Power
        assertTrue(action is VoiceFacilitySelection.Action.ReArm)
        assertEquals(2, (action as VoiceFacilitySelection.Action.ReArm).facilityIndex)
        assertEquals(2, selection.currentlyArmed())
    }

    @Test
    fun testExpiryWorkflow() {
        val selection = VoiceFacilitySelection { 3000L }

        selection.onFacilityUtterance(facilityIndex = 1, nowMs = 1000L)
        assertFalse(selection.isExpired(nowMs = 3500L))
        assertTrue(selection.isExpired(nowMs = 4501L))

        // Repeat after expiry should Arm, not Confirm
        val action = selection.onFacilityUtterance(facilityIndex = 1, nowMs = 5000L)
        assertTrue(action is VoiceFacilitySelection.Action.Arm)
    }
}
