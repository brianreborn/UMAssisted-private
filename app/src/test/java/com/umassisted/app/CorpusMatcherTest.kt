package com.umassisted.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the specific bugs found and fixed in a code review
 * of OQ-49 Stage 1 (commit e478de9 and the fixup after it):
 * - the confidence gate must actually fire for embedded misreads (not just
 *   an isolated token at the end of the string — the original sliding-window
 *   implementation had a bug there);
 * - patterns under 6 characters must require an EXACT match, no fuzz
 *   tolerance at all — testing surfaced that "next" is a 1-edit neighbor of
 *   the ordinary word "text", on top of the "close"/"chose" and
 *   "goals"/"goal" collisions found in review, so the safe floor is general
 *   (by pattern length), not a denylist of the specific collisions found so
 *   far.
 */
class CorpusMatcherTest {

    @Test
    fun `fuzzy match fires for an embedded OCR misread, not just an isolated one`() {
        // The bug: a fixed-width sliding window forced trailing characters
        // into the diff unless the match sat at the very end of the string.
        // "continue" (8 chars) clears the >=6-char fuzz floor.
        val result = CorpusMatcher.match("please tap contlnue to proceed with training")
        assertTrue(result.matched)
        assertTrue(result.isNoChoice)
    }

    @Test
    fun `patterns of 6 or more characters tolerate a single-character misread`() {
        assertTrue(CorpusMatcher.match("please coniirm and proceed now").let { it.matched && it.isNoChoice })
        assertTrue(CorpusMatcher.match("tap repiay to watch again").let { it.matched && it.isNoChoice })
    }

    @Test
    fun `patterns under 6 characters require an exact match, not a fuzzy one`() {
        // "next", "menu", "done", "skip", "ok" are all under 6 chars — a
        // single-character misread on any of them must NOT match, because
        // short/common English words sit too close in edit-distance space
        // (e.g. "next" is 1 edit from "text").
        assertFalse(CorpusMatcher.match("completely unrelated screen text").matched)
        assertFalse(CorpusMatcher.match("screen says meno please").let { it.matched && it.isNoChoice })
        assertFalse(CorpusMatcher.match("dane with training").let { it.matched && it.isNoChoice })
    }

    @Test
    fun `close does not fuzzy-match unrelated text containing chose`() {
        // "close" is a 1-edit neighbor of the ordinary word "chose" —
        // fuzzy-matching it risks silently auto-advancing a real choice
        // screen whose OCR text happens to contain "chose" in flavor text.
        val result = CorpusMatcher.match("you chose the wrong path here")
        assertFalse("should not fuzzy-match \"chose\" to the \"close\" rule", result.isNoChoice)
    }

    @Test
    fun `goals does not fuzzy-match unrelated text containing goal`() {
        // Same collision risk: "goal" is common, unrelated in-game text
        // (training goal blurbs) that must not fuzzy-match the "goals" rule.
        val result = CorpusMatcher.match("urao goal complete for today")
        assertFalse("should not fuzzy-match \"goal\" to the \"goals\" rule", result.isNoChoice)
    }

    @Test
    fun `next does not fuzzy-match unrelated text containing text`() {
        // "next" is a 1-edit neighbor of "text" — found by this test suite
        // itself, not the original code review, which only checked the two
        // collisions above. Confirms the general length floor (not a
        // per-pattern denylist) is what actually closes this class of bug.
        val result = CorpusMatcher.match("please read the text on screen")
        assertFalse("should not fuzzy-match \"text\" to the \"next\" rule", result.isNoChoice)
    }

    @Test
    fun `exact match still works regardless of pattern length`() {
        assertTrue(CorpusMatcher.match("tap close to continue").isNoChoice)
        assertTrue(CorpusMatcher.match("training goals reached").isNoChoice)
        assertTrue(CorpusMatcher.match("tap next to proceed").isNoChoice)
    }

    @Test
    fun `unrelated text does not match anything`() {
        val result = CorpusMatcher.match("a completely unremarkable sentence")
        assertFalse(result.matched)
    }

    @Test
    fun `choice signal wins over no-choice signal on the same text`() {
        val result = CorpusMatcher.match("choose your training carefully")
        assertTrue(result.matched)
        assertFalse(result.isNoChoice)
    }
}
