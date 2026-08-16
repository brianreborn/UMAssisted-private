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
     * Advance by tapping whichever of [candidates] is actually visible, tried
     * in order — for a no-choice interstitial whose exact button wording
     * varies by occurrence (e.g. a news/announcement dismissal that might
     * read "Close", "OK", or "Next" depending on which notice is showing).
     * Distinct from [Decision]: none of these candidates is a real choice
     * between different outcomes, they're synonyms for the same dismissal.
     */
    data class TapAnyText(val candidates: List<String>) : MacroAction()

    /**
     * REQ-A27: the Complete Career hub screen, which shows unspent skill
     * points alongside the "Complete Career" button itself (not a separate
     * screen — confirmed via live capture, 2026-08). Not a generic tap:
     * gated on whether the current OCR text shows a nonzero "Skill Pts"
     * count and whether the invoking command was "quickly" — handled
     * specially in macroTick rather than as a plain TapText, since the
     * decision to stop-and-let-the-user-spend vs. proceed depends on both.
     */
    object CompleteCareerCheckpoint : MacroAction()

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

    /**
     * Recognized as a genuine loading/transition screen — do nothing, just tick
     * again shortly. Distinct from UNRECOGNISED_SCREEN's short retry-then-give-up
     * budget: loading tip text rotates through many variants (observed on-device:
     * "Tazuna's Advice" pairs with different, unpredictable tip copy each time),
     * so matching this screen *at all* — regardless of which tip is showing — is
     * the general fix, rather than enumerating every tip variant as its own step.
     */
    object Wait : MacroAction()
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
    /**
     * Day-boundary screens: a calendar-day rollover can interpose these between
     * *any* macro's steps, not just a career's natural completion (finishCareer
     * was where they were first captured, live, 2026-08) — a "start auto run"
     * issued on a fresh day after the Continue Career modal's Resume is just as
     * likely to hit Login Bonus/Notices/Date Changed as a finish is. Shared
     * between startCareer and finishCareer via spread (`*dayBoundarySteps`)
     * rather than duplicated, so a fix to one applies to both.
     */
    private val dayBoundarySteps: Array<MacroStep> = arrayOf(
        MacroStep(
            name = "Date Changed dialog",
            matches = containsAny("date changed"),
            action = MacroAction.TapText("OK")
        ),
        MacroStep(
            name = "blank loading transition",
            // The day-rollover transition (horseshoe-pattern background, no text
            // at all) OCRs as empty/near-empty — distinct from the "now loading"
            // tip-text screen elsewhere in this file, which always has real text.
            matches = { text -> text.trim().length < 5 },
            action = MacroAction.Wait
        ),
        MacroStep(
            name = "Login Bonus: tap through",
            // No stable button text ("tap anywhere to continue" per REQ-A19-
            // adjacent live testing) — center-screen tap, clear of the reward
            // icon/carat display and the skip control in the corner. Fraction
            // is relative to the game WINDOW (win.top/win.height() in
            // macroTick's TapWindowFraction handling), not the full display —
            // the live test this was grounded in used a raw full-screen tap
            // (y=1000 of 2400), which naively divided out to 0.42; corrected
            // here against the window's actual top offset (~132px status bar,
            // confirmed via dumpsys) and height (~2268px): (1000-132)/2268.
            matches = containsAny("login bonus"),
            action = MacroAction.TapWindowFraction(0.5f, 0.38f)
        ),
        MacroStep(
            name = "Notices: dismiss",
            // "Close" at the list level, "Back" if a tap happened to land on an
            // item and opened its detail — either dismisses this screen.
            matches = containsAny("notices"),
            action = MacroAction.TapAnyText(listOf("Close", "Back"))
        )
    )
    val startCareer = MacroDefinition(
        name = START_CAREER,
        steps = listOf(
            MacroStep(
                name = "title splash: tap to start",
                // The stylized "Umamusume Pretty Derby" logo art and the animated
                // "TAP TO START" prompt OCR unreliably (observed on-device:
                // "FAETTYDEREY", "AP TO SAR" during the intro animation cycle).
                // "Trainer ID: Tap here to display" and the version/copyright line
                // are plain, non-stylized text that read cleanly in every capture
                // of this screen, animated or not — key off those instead.
                matches = containsAll("trainer id"),
                // Screen center: clear of the overlay controls (top-left), the
                // hamburger menu and CRIWARE badge (bottom corners), and the
                // Trainer ID toggle itself (top-left text) — anywhere on the
                // rest of the splash advances past it.
                action = MacroAction.TapWindowFraction(0.5f, 0.5f)
            ),
            MacroStep(
                name = "loading screen (any tip text)",
                // "Now Loading..." is the one stable signal across every observed tip
                // variant — the tip copy itself rotates unpredictably ("Tazuna's
                // Advice" pairs with different, un-enumerable text each time) and
                // sometimes has no actionable button text at all (no "OKAY!"), so
                // matching on tip content doesn't scale. Just wait it out.
                matches = containsAny("now loading"),
                action = MacroAction.Wait
            ),
            MacroStep(
                name = "news/announcement dismissal (no-choice)",
                // REQ-A19 explicitly lists this as a licensed no-choice
                // interstitial on the resume path ("news/announcement
                // dismissals that are Close/Next/OK-only"), between the title
                // splash and the home CAREER button. Deliberately narrower
                // than the old generic "any Next/OK/Confirm" fallback this
                // replaces (removed after it was observed blindly tapping
                // through unlicensed new-career decision screens): requires
                // actual announcement/notice vocabulary to be present, not
                // just dismissal-shaped button text, so it can't fire on a
                // real decision screen that happens to also have a Next/OK
                // button — none of those mention "notice"/"announcement"/
                // "news". Not yet observed/captured on-device; the exact
                // wording is a best effort pending a real capture (OQ-49).
                matches = { text ->
                    val t = text.lowercase()
                    val isAnnouncement = t.contains("notice") || t.contains("announcement") || t.contains("news")
                    val hasDismiss = listOf("close", "ok", "next", "got it").any { t.contains(it) }
                    isAnnouncement && hasDismiss
                },
                action = MacroAction.TapAnyText(listOf("Close", "OK", "Got It", "Next"))
            ),
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
            *dayBoundarySteps,
            MacroStep(
                name = "career started (training hub reached)",
                // Terminal state: the in-career hub. Observed on-device: a bare
                // "training" + "turn" substring match false-positived on a loading
                // *tip* screen ("Friendship Training becomes available after a
                // Friendship Gauge turns orange. Now Loading...") — "turns orange"
                // contains "turn" too. Require the specific "X turns left" counter
                // phrase (not just the word "turn" anywhere) and explicitly exclude
                // any loading screen, tip text included, rather than trusting a
                // single word to mean "we arrived."
                matches = { text ->
                    val t = text.lowercase()
                    val isLoadingScreen = t.contains("now loading") || t.contains("loading...")
                    val hasTurnsLeftCounter = Regex("\\d+\\s*turns?\\s*left").containsMatchIn(t)
                    !isLoadingScreen && t.contains("training") && (hasTurnsLeftCounter || t.contains("goal"))
                },
                action = MacroAction.Terminal
            )
            // No generic "tap Next/OK/Confirm wherever those words appear"
            // fallback here (removed — see startCareerMissingCoverage note
            // below and OQ-49). Observed on-device: on a fresh save (no
            // Continue Career modal, i.e. the new-career path this macro does
            // not license per REQ-A19's "does not license the new-career
            // path" clause), the generic fallback blindly tapped Next/OK/
            // Confirm through the trainee-select, legacy, and support-card
            // screens it has no matcher for, overshooting real choice points
            // and landing mid-way into an unrelated training-mode toggle.
            // Better to exhaust retries and stop (UNRECOGNISED_SCREEN,
            // falls through to the user) than to guess through unlicensed
            // screens — REQ-A19 requires stopping at a decision point, not
            // navigating through screens outside the resume path.
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
            // --- Branch A: still mid-run, exiting early via Menu > Give Up / Save & Exit. ---
            MacroStep(
                name = "Independent Training complete: proceed to Career",
                // Grounded in a live capture (2026-08): "Independent Training" header,
                // "TRAINING COMPLETE!" banner, Cancel/Career buttons. Checked before
                // "open career menu" below since this screen also contains "training",
                // though in practice the two don't overlap ("turn"/"goal" don't appear
                // here — this modal shows "Time Left 0:00:00 left", not a turns-left
                // counter).
                matches = containsAny("training complete"),
                action = MacroAction.TapText("Career")
            ),
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
            // --- Branch B: career already ran its course naturally. Grounded in a
            // live capture (2026-08) of the full post-completion sequence: Independent
            // Training complete (shared with Branch A above) -> Training Log -> Complete
            // Career hub -> Date Changed -> blank loading transition -> Login Bonus ->
            // Notices -> Home.
            //
            // Day-boundary steps are checked BEFORE the hub/log steps below, not after
            // — defensive ordering, not a confirmed failure. Every one of Date Changed/
            // Login Bonus/Notices renders as a modal over a *blurred* Complete Career
            // hub background in the live captures this branch is grounded in. If OCR
            // ever reads "complete career" or "training log" text through that blur
            // (unconfirmed either way — blur usually defeats ML Kit outright, but not
            // guaranteed), the hub/log steps below would false-fire on top of a dialog
            // instead of the dialog's own step firing. Checking the dialogs' own
            // (unblurred, foreground) titles first avoids the failure mode entirely
            // regardless of whether the blur theory is even right. ---
            *dayBoundarySteps,
            MacroStep(
                name = "Complete Career hub: confirm completion",
                // The hub screen (Attributes/Skills, Fans, Stats) also has a "Training
                // Log" button whose label would match the dismiss step below's own
                // "training log" text check — checked first and unconditionally
                // preferred so the macro never wastes steps re-opening the log from
                // the hub, and goes straight to the button that actually finishes this.
                matches = containsAny("complete career"),
                action = MacroAction.CompleteCareerCheckpoint
            ),
            MacroStep(
                name = "Training Log: dismiss",
                // CORRECTED (was wrongly assumed "skipped entirely, never landed on"):
                // tapping "Career" on the Independent Training complete modal lands on
                // this multi-page log (Overview/Career/Aptitudes/Skill Hints/
                // Inspiration) FIRST — it is a real intermediate screen, not bypassed.
                // Live-captured: "OK" sits at a fixed position at the bottom regardless
                // of which of the 5 pages is showing, so a single tap dismisses it from
                // any page without needing to page through — checked after the hub step
                // above since the hub's own "Training Log" button text would otherwise
                // false-positive here too.
                matches = containsAny("training log"),
                action = MacroAction.TapText("OK")
            ),
            // --- Shared terminal state for both branches. ---
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
