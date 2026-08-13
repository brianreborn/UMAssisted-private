package com.umassisted.app

/**
 * REQ-V12's double-utterance arm/confirm pattern, applied to sweep facility
 * selection (REQ-A22): the first recognized facility name pauses/rewinds the
 * sweep there (the "arm" step); the same name spoken again within the
 * confirm window commits it. Pure decision logic — no Android/gesture
 * dependency — so it can be reasoned about (and tested) independent of
 * SpeechRecognizer or GestureDescription plumbing.
 */
class VoiceFacilitySelection(private val confirmWindowMs: () -> Long) {

    sealed class Action {
        /** First utterance for this facility: pause/rewind the sweep there. */
        data class Arm(val facilityIndex: Int) : Action()
        /** Same facility repeated within the window: commit the selection. */
        data class Confirm(val facilityIndex: Int) : Action()
        /** A different facility named while one was already armed: re-arm on the new one. */
        data class ReArm(val facilityIndex: Int) : Action()
    }

    private var armedFacilityIndex: Int? = null
    private var armedAtMs: Long = 0L

    /** Call when a facility-name utterance is recognized. */
    fun onFacilityUtterance(facilityIndex: Int, nowMs: Long): Action {
        val pending = armedFacilityIndex
        val expired = pending != null && nowMs - armedAtMs > confirmWindowMs()
        return when {
            pending == null || expired -> {
                armedFacilityIndex = facilityIndex
                armedAtMs = nowMs
                Action.Arm(facilityIndex)
            }
            pending == facilityIndex -> {
                armedFacilityIndex = null
                Action.Confirm(facilityIndex)
            }
            else -> {
                armedFacilityIndex = facilityIndex
                armedAtMs = nowMs
                Action.ReArm(facilityIndex)
            }
        }
    }

    /** True once the armed selection has aged past the confirm window without a repeat. */
    fun isExpired(nowMs: Long): Boolean {
        val at = armedAtMs
        return armedFacilityIndex != null && nowMs - at > confirmWindowMs()
    }

    /** Clears the armed state — e.g. once expiry has been handled and the sweep resumed. */
    fun clear() {
        armedFacilityIndex = null
    }

    fun currentlyArmed(): Int? = armedFacilityIndex
}
