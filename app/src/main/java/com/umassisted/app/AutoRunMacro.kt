package com.umassisted.app

/**
 * Career start/finish macros — REQ-A19 / REQ-A20 / REQ-A21.
 *
 * A macro is a *bounded, named, user-invoked sequence* (REQ-A1), not a loop.
 * It advances only while it can positively identify the screen it is on, stops at
 * the first thing that needs a human, and has a defined terminal state. REQ-A5 is
 * satisfied by construction: nothing here re-arms, repeats, or continues past its
 * terminal state, and every run needs a fresh explicit command.
 */

/** How much authority the user granted for this single invocation. */
enum class MacroMode {
    /**
     * "start auto run" — advance only steps that carry no choice; fall through to
     * the user at the first real decision (REQ-A19).
     */
    STEP_ONLY,

    /**
     * "start auto run, defaults" — additionally reuse the user's own previous
     * selection for decisions the game does not retain between runs. Never judges
     * which option is better; a decision with no stored selection still falls
     * through on first occurrence (REQ-A8/REQ-A11).
     */
    DEFAULTS,

    /**
     * "start auto run recording defaults" — behaves as if auto-record were enabled,
     * for this invocation only. Records whatever selection ends up being made at
     * each decision point. Composes with DEFAULTS (recording a replayed value is
     * simply idempotent). Does not touch the persistent setting (REQ-A21).
     */
    RECORDING_DEFAULTS;

    val replaysDefaults: Boolean get() = this == DEFAULTS
    val recordsDefaults: Boolean get() = this == RECORDING_DEFAULTS
}

/** What a matched step does. */
sealed class MacroAction {
    /** Advance by tapping something identified by its on-screen text. */
    data class TapText(val text: String) : MacroAction()

    /**
     * Advance by tapping a point expressed as a fraction of the *game window*
     * (never the display — REQ-PL5). Only for controls with no reliable text.
     */
    data class TapWindowFraction(val fx: Float, val fy: Float) : MacroAction()

    /**
     * A real decision. Falls through to the user unless the mode grants authority
     * and a stored selection exists. [key] identifies the decision for
     * record/replay; [subroutine] optionally delegates to a separate named
     * sequence (REQ-A19 allows the race schedule to be its own subroutine).
     */
    data class Decision(val key: String, val subroutine: String? = null) : MacroAction()

    /** The macro's goal state — stop, successfully. */
    object Terminal : MacroAction()
}

/**
 * One step. [matches] is given the OCR text of the current screen and decides
 * whether this step applies. Steps are checked in order, so put specific screens
 * before general ones.
 */
data class MacroStep(
    val name: String,
    val matches: (String) -> Boolean,
    val action: MacroAction
)

data class MacroDefinition(
    val name: String,
    val steps: List<MacroStep>,
    /** Hard ceiling on advances. A macro that needs more than this is lost. */
    val maxSteps: Int = 40,
    /** Hard wall-clock ceiling. */
    val maxDurationMs: Long = 180_000L
)

/**
 * Why a run ended. Every terminating condition is explicit — a macro should never
 * just quietly stop and leave the user wondering whether it is still going.
 */
enum class MacroOutcome {
    /** Reached the defined goal state. */
    COMPLETED,

    /** Hit a decision the user has to make. Not a failure. */
    NEEDS_USER,

    /** Screen did not match any step. Falls through to the user (REQ-M5/REQ-F4). */
    UNRECOGNISED_SCREEN,

    /** Kill switch, left the game, or superseded by a newer command. */
    ABORTED,

    /** Ran past its step or time ceiling — treated as lost, not as success. */
    EXHAUSTED
}

object AutoRunMacros {

    const val START_CAREER = "start_auto_run"
    const val FINISH_CAREER = "finish_auto_run"

    /**
     * Decision keys. Stable identifiers so recorded defaults survive changes to the
     * matching rules; renaming one silently orphans a user's stored default.
     */
    const val DECISION_RACE_SCHEDULE = "career.race_schedule"
    const val DECISION_TRAINEE = "career.trainee"
    const val DECISION_SUPPORT_DECK = "career.support_deck"

    private fun containsAll(vararg needles: String): (String) -> Boolean = { text ->
        val lower = text.lowercase()
        needles.all { lower.contains(it.lowercase()) }
    }

    private fun containsAny(vararg needles: String): (String) -> Boolean = { text ->
        val lower = text.lowercase()
        needles.any { lower.contains(it.lowercase()) }
    }

    /**
     * Text that must never be tapped by a macro, whatever the OCR says. These are
     * destructive or career-ending controls that sit next to the ones we do want:
     * "Delete Data" is one row away from "Resume" on the Continue Career modal, and
     * a fuzzy text match landing on it would destroy a career irrecoverably.
     * Checked at dispatch time, not just when authoring steps.
     */
    val NEVER_TAP = listOf(
        "delete data",
        "delete",
        "give up"
    )

    fun isForbiddenTapTarget(text: String): Boolean {
        val t = text.trim().lowercase()
        return NEVER_TAP.any { t == it || t.contains(it) }
    }

    /**
     * Career start flow (REQ-A19).
     *
     * Grounded in captured screens rather than guessed:
     *   misc/20260812_090601_snap06 — home/lobby: CAREER button, bottom nav
     *     (Enhance / Story / Home / Race / Scout)
     *   misc/20260812_090623_snap07 — Continue Career modal: title "Continue
     *     Career", Unity Cup, goal + turns left, Current Trainee stat row,
     *     Cancel / Resume, and a Delete Data button
     *
     * Covers the resume-an-in-progress-run path, which is what 1.0 alpha's scope is
     * built around. The brand-new-career path (trainee select, support deck, race
     * schedule) is NOT here: those screens are not in the corpus yet, and guessing
     * matchers for screens nobody has captured is how a macro ends up tapping the
     * wrong thing. They are listed as gaps below.
     */
    val startCareer = MacroDefinition(
        name = START_CAREER,
        steps = listOf(
            MacroStep(
                name = "home: open Career",
                // Home is identifiable by the CAREER button plus the bottom nav.
                // Requiring nav words too avoids matching any screen that merely
                // mentions "career" (the profile modal and results screens do).
                matches = { text ->
                    val t = text.lowercase()
                    t.contains("career") &&
                        listOf("enhance", "story", "home", "race", "scout").count { t.contains(it) } >= 3
                },
                action = MacroAction.TapText("CAREER")
            ),
            MacroStep(
                name = "continue-career modal: resume",
                // "start auto run" is itself the declaration of intent to get into a
                // run, so Resume is the step the command asked for rather than a
                // decision to defer. Cancel and Delete Data are never targets.
                matches = containsAll("continue career", "resume"),
                action = MacroAction.TapText("Resume")
            ),
            MacroStep(
                name = "career started (training hub reached)",
                // Terminal state: the in-career hub. "turn(s) left" and the goal
                // banner distinguish it from other screens that say "Training".
                matches = { text ->
                    val t = text.lowercase()
                    t.contains("training") && (t.contains("turn") || t.contains("goal"))
                },
                action = MacroAction.Terminal
            ),
            MacroStep(
                name = "generic no-choice advance",
                // Kept last so specific screens win. Only fires on screens the
                // corpus matcher has already classified as no-choice.
                matches = containsAny("next", "ok", "confirm"),
                action = MacroAction.TapText("Next")
            )
        )
    )

    /**
     * Screens REQ-A19's "Defaults" clause still needs before the new-career path can
     * be implemented. Listed explicitly so the gap is visible rather than implied by
     * absent code.
     */
    val startCareerMissingCoverage = listOf(
        "new career: trainee selection",
        "new career: support card deck selection",
        "new career: career race schedule (REQ-A19 subroutine)",
        "new career: final confirm / begin career"
    )

    /**
     * Career finish flow (REQ-A20).
     *
     * Grounded in misc/20260812_090822_snap12 (menu modal: Save & Exit, Give Up,
     * Help/Glossary, Career Profile, Epithets, Options, Close) and the give_up
     * confirmation captures.
     *
     * Note "give up" is in NEVER_TAP: ending a career is destructive, so it is not
     * something a macro chooses. The exit kind is a decision that goes to the user
     * unless they have explicitly recorded a preference (REQ-A4/REQ-A8).
     */
    val finishCareer = MacroDefinition(
        name = FINISH_CAREER,
        steps = listOf(
            MacroStep(
                name = "open career menu",
                matches = { text ->
                    val t = text.lowercase()
                    t.contains("training") && (t.contains("turn") || t.contains("goal"))
                },
                action = MacroAction.TapText("Menu")
            ),
            MacroStep(
                name = "menu modal: choose exit kind",
                matches = containsAny("save & exit", "save and exit", "give up"),
                action = MacroAction.Decision("career.exit_kind")
            ),
            MacroStep(
                name = "confirmation",
                matches = containsAny("are you sure", "confirm"),
                action = MacroAction.Decision("career.exit_confirm")
            ),
            MacroStep(
                name = "back at home",
                matches = { text ->
                    val t = text.lowercase()
                    t.contains("career") &&
                        listOf("enhance", "story", "home", "race", "scout").count { t.contains(it) } >= 3
                },
                action = MacroAction.Terminal
            )
        )
    )

    fun byName(name: String): MacroDefinition? = when (name) {
        START_CAREER -> startCareer
        FINISH_CAREER -> finishCareer
        else -> null
    }

    /**
     * Command-phrase resolution for REQ-A20's synonyms. [confirmTextOnScreen] lets
     * the literal wording of the current dialog count as a valid phrasing, which is
     * the wording a user under load actually reaches for.
     *
     * Returns null for anything ambiguous rather than guessing — notably bare
     * "stop", which REQ-A20 says must not silently end a career.
     */
    fun resolveFinishPhrase(phrase: String, confirmTextOnScreen: String?): String? {
        val p = phrase.trim().lowercase()
        val unambiguous = setOf("finish", "complete", "finish auto run", "complete auto run")
        if (p in unambiguous) return FINISH_CAREER
        if (p == "stop auto run") return FINISH_CAREER
        val onScreen = confirmTextOnScreen?.trim()?.lowercase()
        if (!onScreen.isNullOrEmpty() && p == onScreen) return FINISH_CAREER
        return null
    }
}
