# UMAssisted 1.0 Alpha — UAT Test Procedure

Formal test procedure for exercising 1.0 alpha functionality on the device
under test (DUT), verified against the `feature-stabilization` branch. This
document does not include screenshots; each step notes, in an inline comment
block, what a screenshot at that point *could* capture, for anyone assembling
a companion image set later.

Every claim of "implemented" / "not implemented" below is verified against
the actual code (file:line), not against REQUIREMENTS.md's own prose — one
place (Test 6.2) the doc itself was found stale relative to the code during
this session and has since been corrected.

---

## 1. Purpose & Scope

Exercises every voice command, macro, and overlay control that is actually
wired in this build, in the order a real session would encounter them:
arm voice → operate a training hub → run the start-of-career macro → run the
end-of-career macro (both its branches) → settings. Explicitly excludes
anything confirmed unbuilt (§9) so test time isn't spent on commands that
cannot possibly work yet.

## 2. Environment / Preconditions

- [ ] Latest `feature-stabilization` APK installed on the DUT.
- [ ] `com.umassisted.app`'s `AccessibilityService` re-enabled if this is a
      fresh install (Android disables accessibility services on install/update
      in some configurations — check Settings → Accessibility if the overlay
      handle doesn't appear).
- [ ] Umamusume (`com.cygames.umamusume`) installed and foregrounded.
- [ ] Do Not Disturb **on** for the whole session (REQ-DEV4) — a notification
      tone during voice testing is a real false-activation risk, and a banner
      can visually cover the game UI at the exact moment a gesture dispatches.
- [ ] Tester physically present and watching for the entire session
      (REQ-DEV1/2/3) — this procedure assumes a human is watching the DUT
      screen throughout, not running unattended.
- [ ] Quiet room, or at least no other speech audible near the mic.

<!-- SCREENSHOT: DUT home screen with the UMAssisted overlay handle visible,
     docked under/near the OS accessibility button, in its idle (gray) state. -->

## 3. Read this before you start: known blockers

These are **not implemented**, confirmed by reading the code, not by
inference from the doc. Don't spend test time trying to make them work —
if any of these "work," that's the surprise worth reporting, not the
baseline.

| # | What | Why it can't work yet | Where confirmed |
|---|------|------------------------|------------------|
| B1 | Starting a **brand-new** career (trainee select → support deck → race schedule) | No `MacroStep` exists for any of these screens. `startCareer` will correctly stop with an unrecognized-screen state rather than guess — that's correct behavior, not a bug, if you hit it. | `AutoRunMacro.kt` — `startCareerMissingCoverage` list, no matching steps |
| B2 | Actually **spending unspent skill points** before finishing a career | The completion-flow checkpoint only *stops and waits* for you — it never opens Skills or purchases anything. 1.0-beta scoped. | `MacroAction.CompleteCareerCheckpoint` |
| B3 | Starting a career via the **"Trainer Aptitude Test"** event banner | Only the plain CAREER button path is wired; the banner is invisible to the macro. 1.0-beta scoped (REQ-A33). | No matching code exists |
| B4 | **Continuous/always-on** screen reading (a toggle that keeps reading in the background) | The 🔍 overlay control is one-shot only; there is no polling loop. REQ-V21 is drafted in the doc but explicitly not yet implemented. | grep for "continuous"/classification loop: no hits |
| B5 | Any **spoken read-back** of on-screen choices (TTS) | Zero TTS code exists anywhere in the app — not even a stub call. | grep for `TextToSpeech`/`.speak(`: only a comment references REQ-T1, no implementation |
| B6 | **Voice commands** for the 🔍 (read) or ▶ (run) overlay buttons | No phrase is registered for either. Must be tapped. 1.0-beta scoped (REQ-V26). | `VoiceCorpus.kt` corpus list — no entries dispatch either action |

## 4. Test Procedure

### 4.1 Overlay controls & kill switches

| # | Step | Expected result |
|---|------|------------------|
| 4.1.1 | Tap the collapsed overlay handle | Cluster expands: sweep (🧹), voice (🎤), read (🔍), run (▶), phrase-panel (📋) cells appear |
| 4.1.2 | Tap 🎤 | Handle turns **orange** ("still warming up"), then **green** once the recognizer's first real audio sample arrives — this takes a few real seconds; that delay is expected startup latency, not a stall |
| 4.1.3 | Say `stop listening`, or `mute`, or `voice off` (separately, re-arming between each) | Mic disarms each time; handle returns to idle color |
| 4.1.4 | Tap 🧹 | Sweep arms/disarms; cell background toggles color |
| 4.1.5 | Tap 🔍 with sweep off | Glyph cycles ⏳ → one of ✅/❓/❌ depending on what's on screen; **no tap is ever dispatched into the game** by this control |
| 4.1.6 | Tap ▶ with sweep **off** | Shows 🚫, does nothing |
| 4.1.7 | Tap ▶ with sweep **on** | Runs exactly one sweep pass across the five facilities |
| 4.1.8 | Tap 📋 | Phrase/screen panel opens: a raw OCR excerpt of the last capture, labeled "Screen (raw OCR)" (not a classified screen name — none exists yet), plus the list of currently-valid voice commands for the live state |

<!-- SCREENSHOT: overlay expanded, all six cells visible, handle mid-way
     between idle and armed color for a "before/after" pair. -->

### 4.2 Facility voice commands

Preconditions: voice armed (4.1.2), at the in-career training hub.

| # | Step | Expected result |
|---|------|------------------|
| 4.2.1 | Say `speed` | Arms Speed (pauses an in-progress sweep if one was running) |
| 4.2.2 | Say `ok` (or `go` / `confirm` / `yes` / `do it` / `roger` / `ryoukai` / `hai`) | Commits the armed facility |
| 4.2.3 | Say `stamina stamina` (same word twice, one utterance) | Arms **and** confirms Stamina in a single utterance |
| 4.2.4 | Arm a facility, then say `cancel` (or `oops` / `escape` / `abort` / `no wait`) | Un-arms; no tap dispatched |
| 4.2.5 | Say each of `wit`, `wits`, `wiz`, `wisdom`, `energy`, `wait` (separately) | All six resolve to arming Wit |
| 4.2.6 | Say `speed training` (one utterance) | Jumps straight into Speed's training sub-screen — no separate confirm step |
| 4.2.7 | Say `training` or `facilities` (bare, no facility name) | Taps whatever is currently on-screen and labeled "Training" |
| 4.2.8 | Say `speed and stamina` (two facility names) | Resolves ambiguous; nothing happens |

<!-- SCREENSHOT: training hub with a facility freshly armed (highlighted
     state, if the game shows one) right before saying the confirm word. -->

### 4.3 Heartbeat / sweep continuation

| # | Step | Expected result |
|---|------|------------------|
| 4.3.1 | With a sweep paused mid-pass, say `continue sweep` | Resumes the sweep — **this exact phrase resolved as `Ambiguous` (did nothing) until a same-session fix; worth extra scrutiny here specifically** |
| 4.3.2 | Same, with `resume` / `continue` / `keep going` / `keep moving` | Same result, resumes |
| 4.3.3 | Any of the above phrases with sweep **off** | No-op — does not start a new sweep |

### 4.4 Sweep

| # | Step | Expected result |
|---|------|------------------|
| 4.4.1 | Say `sweep` | Arms sweep if needed, then runs one pass |
| 4.4.2 | Say `auto sweep` | Toggles the sweep setting only — does **not** run a pass |
| 4.4.3 | Watch a full hover pass closely | ⚠ **Unverified geometry** — confirm the hover motion visually lands on/near all five facility icons. This path uses fixed-fraction coordinates that were never re-verified after a sibling coordinate system (the voice tap path) was found miscalibrated earlier this session. If it's off, that's a real, plausible finding — not a surprise. |

<!-- SCREENSHOT: mid-hover-pass frame, to visually cross-check alignment
     against each facility icon's actual on-screen position. -->

### 4.5 Start auto run — resume-an-existing-career path

From the title/"TAP TO START" splash screen.

| # | Step | Expected result |
|---|------|------------------|
| 4.5.1 | Say any of: `start auto run`, `resume career`, `continue career`, `start run`, `auto run`, `start career` | All six route through the identical macro — confirm at least two different phrases across separate runs actually produce the same walked sequence below |
| 4.5.2 | Watch the walk | title splash → loading → (announcement dismissal, if one is showing) → Home → Continue Career modal → its Resume button |
| 4.5.3 | If a calendar day rolled over since last login | Date Changed → Login Bonus (may take 1-2 taps to fully advance) → Notices all get walked through automatically, no manual taps needed |
| 4.5.4 | End state | Macro stops cleanly once the training hub is reached; does not continue into training actions on its own |
| 4.5.5 | Say `start auto run defaults` | Runs without erroring (may have no stored decision to actually replay yet, depending on prior sessions) |
| 4.5.6 | Say `start auto run recording defaults` | Same — runs without erroring |

⚠ **This whole macro chain (title splash → training hub) has not yet been
observed completing end-to-end in a single live run** — the one prior
attempt this session started from a screen that already satisfied the
terminal condition trivially, so it "succeeded" without exercising any of
the intermediate steps. Treat 4.5.1–4.5.4 as the first real end-to-end test
of this path, not a re-confirmation.

<!-- SCREENSHOT SEQUENCE: one frame per step above (title splash, loading,
     Home, Continue Career modal, training hub) — this is the walk most
     worth a full before/after image set, since it's unverified live. -->

### 4.6 Finish auto run — both branches

#### 4.6.1 — Branch A: still mid-run, exiting early

| # | Step | Expected result |
|---|------|------------------|
| A.1 | From the training hub, say `finish career` | Opens the in-game Menu, then the Save & Exit / Give Up choice **falls through to you** — it is never auto-selected |
| A.2 | Pick either option | The confirmation dialog that follows also falls through to you |

#### 4.6.2 — Branch B: a career that already finished on its own

⚠ REQUIREMENTS.md previously claimed this branch didn't exist at all
("leaves the actually-completed case entirely unhandled"). That claim was
stale — it does exist, was built later in the same session the claim was
written, and the doc has been corrected. Go in expecting this to work.

| # | Step | Expected result |
|---|------|------------------|
| B.1 | With "Independent Training complete" showing (post-training completion modal, Cancel/Career buttons) | — |
| B.2 | Say `complete career` | Taps Career → lands on the **Training Log** (multi-page: Overview/Career/Aptitudes/Skill Hints/Inspiration) |
| B.3 | Watch the Training Log | Macro taps "OK" to dismiss it **without paging through** — confirm it doesn't attempt to read/page through the log first |
| B.4 | Continue watching | Arrives at the Complete Career hub (Attributes/Skills panel, Fans count, pink "Complete Career" button) |
| B.5 | If unspent Skill Pts > 0 | Macro **stops** at the hub and waits for you — confirm it does **not** silently tap past or spend anything (it structurally can't; spending isn't built, see B2 in §3) |
| B.6 | Say `complete career quickly` (or `quickly complete career`) with nonzero points still showing | Skips the stop this time, taps "Complete Career" directly |
| B.7 | Watch the rest | Date Changed → Login Bonus → Notices → arrives back at Home, stops cleanly |

<!-- SCREENSHOT SEQUENCE: Independent Training complete modal → Training Log
     (any page) → Complete Career hub with visible Skill Pts count → the
     stop-and-wait state (if points > 0) → Home. This is the sequence a
     macro-logic bug was actually found and fixed against this session
     (the Training Log dismiss step was missing) — a real before/after
     image set here would be the single most valuable screenshot addition
     to this document. -->

### 4.7 Settings screen (MainActivity)

| # | Step | Expected result |
|---|------|------------------|
| 4.7.1 | Change the sweep period slider | Visibly affects sweep pass speed on the next run |
| 4.7.2 | Change pacing mode (Sinusoidal / Linear / Decelerating) | Visibly different sweep motion per mode |
| 4.7.3 | Change start facility + direction | Sweep begins where/how configured |
| 4.7.4 | Toggle voice chime mute | Applies immediately, even to an already-in-progress voice session |
| 4.7.5 | Tap the Settings screen's own "Exit Career" button (not via voice) | ⚠ **Separate, cruder code path** — uses fixed window-fraction taps, not OCR-anchored text search, unlike the voice `finishCareer` macro. Test this on its own; a pass here does not validate §4.6, and vice versa. |
| 4.7.6 | Open "Voice Pipeline Log", scroll up, wait with no new voice activity | View stays where you scrolled it — does **not** auto-scroll back to the bottom every 400ms (this was a real bug, fixed this session) |
| 4.7.7 | Tap "Clear" in that dialog | Log empties |

## 5. Appendix A — Macro logic inconsistencies found this session

Found by tracing the actual witnessed screen sequences from earlier live
captures against the macro's step coverage, not from a new live test:

1. **Training Log was wrongly assumed unreachable.** The `finishCareer`
   macro's Branch B comment originally claimed the Training Log screen was
   "skipped entirely, not stepped through," on the assumption that tapping
   "Career" from the Independent Training complete modal lands directly on
   the Complete Career hub. The actual witnessed sequence shows it lands on
   the Training Log **first** — a real intermediate screen with no step to
   dismiss it, which would have stalled the macro with an
   unrecognized-screen state every time Branch B actually ran. **Fixed**:
   added a "Training Log: dismiss" step (taps "OK", which sits at a fixed
   position regardless of which of the 5 pages is showing).
2. **Day-boundary dialogs reordered ahead of the hub/log checks,
   defensively.** Date Changed / Login Bonus / Notices all render as modals
   over a *blurred* Complete Career hub background in the live captures.
   If OCR ever reads hub/log text through that blur (unconfirmed either
   way), the hub or log steps could false-fire on top of a dialog instead
   of the dialog's own step. Checking the dialogs' own foreground titles
   first removes the failure mode regardless of whether the blur theory
   is correct. This is a precautionary reorder, not a fix for a confirmed
   live failure — flag it if Branch B behaves unexpectedly around any of
   those three dialogs, since it's the newest, least-tested change here.

Both changes are in `AutoRunMacro.kt`, compiled and unit-tested clean, but
**not yet live-tested** — §4.6.2 is exactly the test that exercises them.

## 6. Appendix B — 1.0 alpha blockers & open questions

Checked REQUIREMENTS.md for every requirement explicitly tagged as a hard
blocker/requirement for 1.0 alpha specifically (not beta/final), and
verified each against code:

| Requirement | Status |
|---|---|
| REQ-A19 (start/resume macro, title-screen invocable) | **Built** — resume-path only, matching the doc's own scope restriction (new-career path is explicitly out of scope, not a gap) |
| REQ-A21 (record-defaults one-shot macro) | **Built** |
| REQ-SF7 (never tap the wrong app; re-verify immediately before each dispatch) | **Built**, with one partial gap: the doc's own text describes an explicit before/after screen-diff confirmation step, which doesn't exist as literal code — what exists instead is the macro retry loop re-capturing on the next tick, which serves a similar purpose indirectly but isn't the same guarantee |

**OQ-1 — RESOLVED: no.** "Does Umamusume's client detect/block synthetic
gestures dispatched by an AccessibilityService?" No — not from a dedicated
spike (the obvious cheap test, script a tap and watch what happens, is
itself the kind of autonomous input injection REQ-DEV1/2 forbid), but from
the volume of ordinary use: this session has dispatched many real gestures
across full career auto-run macros with visible, correct game responses and
no warning, throttling, or block from the client. This says nothing about
whether anything is silently logged server-side — that residual risk isn't
eliminated — but the client-side detect/block question itself is answered.

No other open question in REQUIREMENTS.md is tagged as blocking specifically
for 1.0 alpha — the rest (OQ-49's real screen classifier, OQ-50's tap-map
calibration, OQ-22's full voice coverage, etc.) are explicitly beta-or-later
scoped in the doc itself.
