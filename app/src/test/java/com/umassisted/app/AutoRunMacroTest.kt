package com.umassisted.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for `startCareer`/`finishCareer`'s step matchers
 * (REQ-A19/A20) — the exact layer where every OCR-matching bug this project
 * has hit live so far (newline joins, ambiguous substrings, step ordering)
 * has actually shown up. No test existed for this file before now.
 *
 * `firstMatch` mirrors macroTick's own selection (`steps.firstOrNull {
 * it.matches(text) }`) exactly, so these tests exercise real step-ordering
 * behavior, not just individual matcher correctness.
 *
 * OCR text uses `\n` between lines deliberately — ML Kit's `Text.getText()`
 * joins separately-detected lines with `\n`, not a space, which is the root
 * cause of several bugs already found and fixed in this codebase.
 */
class AutoRunMacroTest {

    private fun firstMatch(macro: MacroDefinition, text: String): String? =
        macro.steps.firstOrNull { it.matches(text) }?.name

    // --- startCareer (REQ-A19), resume path ---

    @Test
    fun `title splash matches on Trainer ID text`() {
        assertEquals(
            "title splash: tap to start",
            firstMatch(AutoRunMacros.startCareer, "Trainer ID: Tap here to display\nVer. 1.33.1\n1.5-Year Anniversary")
        )
    }

    @Test
    fun `loading screen matches regardless of which tip is showing`() {
        assertEquals(
            "loading screen (any tip text)",
            firstMatch(AutoRunMacros.startCareer, "Now Loading...\nTazuna's Advice\nSome unpredictable tip copy here")
        )
        assertEquals(
            "loading screen (any tip text)",
            firstMatch(AutoRunMacros.startCareer, "Now Loading...\nA completely different tip\nabout something else")
        )
    }

    @Test
    fun `home screen matches and continue-career modal is checked first when both could apply`() {
        assertEquals(
            "home: open Career",
            firstMatch(AutoRunMacros.startCareer, "CAREER\nEnhance\nStory\nHome\nRace\nScout")
        )
        // Worst case: the modal renders over a blurred home background whose
        // nav chrome OCR still partially reads through. "continue-career
        // modal: resume" must win over the more general "home: open Career"
        // here, or the macro re-taps CAREER instead of Resume.
        assertEquals(
            "continue-career modal: resume",
            firstMatch(
                AutoRunMacros.startCareer,
                "Continue Career\nUnity Cup\nCurrent Trainee\nCancel\nResume\nDelete Data\n" +
                    "CAREER\nEnhance\nStory\nHome\nRace\nScout"
            )
        )
    }

    @Test
    fun `continue-career modal resumes, never targets Cancel or Delete Data`() {
        assertEquals(
            "continue-career modal: resume",
            firstMatch(AutoRunMacros.startCareer, "Continue Career\nUnity Cup\nCurrent Trainee\nCancel\nResume\nDelete Data")
        )
    }

    @Test
    fun `day-boundary dialogs are caught no matter where they interpose, including before the title splash`() {
        // Synthetic worst case: a day-boundary dialog rendering with enough
        // ambient text to also satisfy a later, more general step's matcher
        // (title splash's "trainer id"). dayBoundarySteps must win.
        assertEquals(
            "Date Changed dialog",
            firstMatch(AutoRunMacros.startCareer, "Date Changed\nOK\nTrainer ID: Tap here to display")
        )
        assertEquals("Login Bonus: tap through", firstMatch(AutoRunMacros.startCareer, "Login Bonus\nDay 3\nTap anywhere to continue"))
        assertEquals("Notices: dismiss", firstMatch(AutoRunMacros.startCareer, "Notices\nClose"))
        assertEquals("blank loading transition", firstMatch(AutoRunMacros.startCareer, ""))
    }

    @Test
    fun `career started terminal state requires a turns-left counter or goal, not just the word training`() {
        assertEquals(
            "career started (training hub reached)",
            firstMatch(
                AutoRunMacros.startCareer,
                "Junior Year Pre-Debut\nUnity Cup\n2 turns left this year\ngoal Run in Junior Make Debut\nTraining\nSkills"
            )
        )
        // The false positive this step's own comment documents: a loading
        // *tip* screen mentioning training must NOT be read as the hub.
        assertEquals(
            "loading screen (any tip text)",
            firstMatch(
                AutoRunMacros.startCareer,
                "Now Loading...\nFriendship Training becomes available after a Friendship Gauge turns orange."
            )
        )
    }

    // --- finishCareer (REQ-A20) ---

    @Test
    fun `independent training complete proceeds to Career, not confused with the open-career-menu step`() {
        assertEquals(
            "Independent Training complete: proceed to Career",
            firstMatch(AutoRunMacros.finishCareer, "Independent Training\nTRAINING COMPLETE!\nTime Left 0:00:00 left\nCancel\nCareer")
        )
    }

    @Test
    fun `open career menu requires training plus a turn or goal signal`() {
        assertEquals(
            "open career menu",
            firstMatch(
                AutoRunMacros.finishCareer,
                "Junior Year Pre-Debut\nUnity Cup\n2 turns left this year\ngoal Run in Junior Make Debut\nTraining\nMenu"
            )
        )
    }

    @Test
    fun `day-boundary dialogs win over Branch A's open-career-menu step during a mid-run finish`() {
        // Synthetic worst case: "finish auto run" issued right as a day
        // rollover hits mid-run — a Login Bonus dialog over the still-active
        // training hub, whose background alone would satisfy "open career
        // menu"'s broad training+turn heuristic.
        assertEquals(
            "Login Bonus: tap through",
            firstMatch(
                AutoRunMacros.finishCareer,
                "Login Bonus\nDay 5\nTap anywhere to continue\n" +
                    "Junior Year Pre-Debut\n2 turns left this year\ngoal Run in Junior Make Debut\nTraining\nMenu"
            )
        )
    }

    @Test
    fun `menu modal decision and exit confirmation fall through to the user`() {
        assertEquals(
            "menu modal: choose exit kind",
            firstMatch(AutoRunMacros.finishCareer, "Save & Exit\nGive Up\nHelp/Glossary\nCareer Profile\nEpithets\nOptions\nClose")
        )
        assertEquals(
            "confirmation",
            firstMatch(AutoRunMacros.finishCareer, "Are you sure?\nCancel\nOK")
        )
    }

    @Test
    fun `day-boundary dialogs win over Branch B's hub and log steps too`() {
        // The documented (unconfirmed) blur risk: a day-boundary dialog over
        // a blurred Complete Career hub whose background text could satisfy
        // either hub/log step if it bled through.
        assertEquals(
            "Notices: dismiss",
            firstMatch(
                AutoRunMacros.finishCareer,
                "Notices\nClose\nComplete Career\nAttributes/Skills\nFans\nStats\nTraining Log"
            )
        )
    }

    @Test
    fun `complete career hub is preferred over training log when both could apply`() {
        assertEquals(
            "Complete Career hub: confirm completion",
            firstMatch(AutoRunMacros.finishCareer, "Complete Career\nAttributes/Skills\nFans 1238\nStats\nTraining Log")
        )
    }

    @Test
    fun `training log dismisses from any of its pages`() {
        assertEquals(
            "Training Log: dismiss",
            firstMatch(AutoRunMacros.finishCareer, "Training Log\nOverview Career Aptitudes Skill Hints Inspiration\nOK")
        )
    }

    @Test
    fun `back at home is the shared terminal state for both branches`() {
        assertEquals(
            "back at home",
            firstMatch(AutoRunMacros.finishCareer, "CAREER\nEnhance\nStory\nHome\nRace\nScout")
        )
    }

    @Test
    fun `unrecognised screen falls through with no step matched, for both macros`() {
        assertNull(firstMatch(AutoRunMacros.startCareer, "some totally unrelated screen with none of the expected text"))
        assertNull(firstMatch(AutoRunMacros.finishCareer, "some totally unrelated screen with none of the expected text"))
    }

    // --- Safety guard, both macros rely on this at dispatch time ---

    @Test
    fun `never-tap guard blocks destructive targets regardless of OCR line splits`() {
        assert(AutoRunMacros.isForbiddenTapTarget("Delete Data"))
        assert(AutoRunMacros.isForbiddenTapTarget("Give\nUp")) // OCR line split
        assert(!AutoRunMacros.isForbiddenTapTarget("Resume"))
    }
}
