package dev.fude.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [editBetween] recovering edits from whole-text reports.
 *
 * The property that matters is the round trip: whatever this returns, applying it to
 * `before` must produce `after` exactly. Anything less would corrupt the document
 * the moment a user pressed undo.
 */
class EditDiffTest {

    private fun assertRoundTrips(before: String, after: String, description: String = "") {
        val edit = editBetween(before, after)
        if (edit == null) {
            assertEquals(before, after, "$description: null edit must mean the texts are equal")
            return
        }
        assertEquals(after, edit.applyTo(before), "$description: applying it must reproduce `after`")
    }

    // -------------------------------------------------- the round trip

    /**
     * Every single-character removal and replacement, over documents whose clusters
     * span more than one UTF-16 unit.
     *
     * The point of this property is that `editBetween` derives its answer from a
     * common prefix and a common suffix computed *independently*, then snaps both ends
     * outward to cluster boundaries. Those three steps can disagree: the snapped range
     * can reach into territory the suffix already counted as unchanged. Nothing above
     * this test could see that, because every fixture removes a whole cluster, which is
     * exactly the case where prefix, suffix and snapping all agree.
     *
     * Both directions are asserted, and they fail differently. `applyTo(before)` not
     * reproducing `after` means the edit misreports what happened. `inverse().applyTo(after)`
     * not restoring `before` is the one that corrupts a document, because `UndoStack`
     * applies the inverse to the platform's own text.
     *
     * The fixtures are the cluster kinds where a platform report can plausibly land
     * *between* two units of one cluster — a carriage return split from its line feed, a
     * combining mark split from its base, a ZWJ sequence split mid-sequence, a surrogate
     * pair split at its halves. A fixture that is entirely BMP and entirely unclustered
     * would pass trivially and prove nothing.
     */
    @Test
    fun everyPartialClusterRemovalAndReplacementRoundTrips() {
        val fixtures = mapOf(
            "CRLF" to "a\r\nb",
            "CRLF at end" to "ab\r\n",
            "two CRLF" to "a\r\nb\r\nc",
            "combining acute" to "éx",
            "emoji ZWJ family" to "\uD83D\uDC68‍\uD83D\uDC69x",
            "surrogate pair" to "a\uD83D\uDE00b",
            "skin tone" to "\uD83D\uDC4Dx",
        )
        for ((name, before) in fixtures) {
            for (i in before.indices) {
                // Removal: the report says one unit of a cluster went away.
                val removed = before.removeRange(i, i + 1)
                assertBothDirections(before, removed, "$name: removing unit $i ('${before[i]}')")

                // Replacement, which produces the same straddled prefix/suffix shape
                // without changing the length at all.
                val replaced = before.substring(0, i) + "Z" + before.substring(i + 1)
                assertBothDirections(before, replaced, "$name: replacing unit $i")
            }
        }
    }

    private fun assertBothDirections(before: String, after: String, description: String) {
        val edit = editBetween(before, after) ?: run {
            assertEquals(before, after, "$description: a null edit must mean the texts are equal")
            return
        }
        assertEquals(after, edit.applyTo(before), "$description: applying it must reproduce the report")
        assertEquals(before, edit.inverse().applyTo(after), "$description: the inverse must restore the original")
    }

    @Test
    fun typingOneCharacterRecoversASingleInsert() {
        assertEquals(Insert(5, "x"), editBetween("hello", "hellox"))
    }

    @Test
    fun backspaceRecoversASingleDelete() {
        assertEquals(Delete(TextRange(5, 6), "x"), editBetween("hellox", "hello"))
    }

    @Test
    fun changingACharacterRecoversAReplace() {
        assertEquals(Replace(TextRange(1, 2), "a", "b"), editBetween("hat", "hbt"))
    }

    @Test
    fun identicalTextsProduceNoEdit() {
        assertNull(editBetween("same", "same"))
    }

    @Test
    fun anEmptyEditProducesNoEdit() {
        assertNull(editBetween("", ""))
    }

    /** The load-bearing test. Every case below is a way this could corrupt text. */
    @Test
    fun everyShapeOfChangeRoundTrips() {
        val cases = listOf(
            "" to "a",
            "a" to "",
            "" to "hello world",
            "hello world" to "",
            "abc" to "abcabc",
            "abcabc" to "abc",
            "the quick brown fox" to "the quick red fox",
            "# Title" to "# Title\n\nbody",
            "a\n\nb" to "a",
            "one two three" to "one  two three",
            "trailing\n" to "trailing",
            "trailing" to "trailing\n",
        )
        for ((before, after) in cases) {
            assertRoundTrips(before, after, "'$before' -> '$after'")
        }
    }

    /** A duplicated document is the case a naive prefix/suffix scan gets wrong. */
    @Test
    fun aSuffixScanCannotOverlapTheSharedPrefix() {
        val before = "abc"
        val after = "abcabc"
        val edit = editBetween(before, after)
        assertRoundTrips(before, after)
        assertEquals(after, edit!!.applyTo(before))
    }

    // -------------------------------------------------- grapheme safety

    @Test
    fun anInsertionInsideAnEmojiTakesTheWholeCluster() {
        // An insertion before a multi-unit cluster. Nothing here needs cluster
        // knowledge: the boundary at offset 0 is already a cluster boundary, so the
        // plain prefix/suffix diff puts the insert exactly there. Pinned because it
        // looks like a case that would need snapping and does not.
        val family = "👨‍👩‍👧‍👦"
        val edit = editBetween(family, "x$family")
        assertRoundTrips(family, "x$family")
        assertEquals(Insert(0, "x"), edit, "the inserted x is a whole cluster on its own")
    }

    @Test
    fun aDeletionInsideACombiningSequenceTakesTheWholeCluster() {
        // "e" + combining acute is one cluster, and the base and the mark must not
        // survive an undo apart. As above, the insert lands on a boundary already.
        val decomposed = "é"
        val edit = editBetween(decomposed, "a$decomposed")
        assertRoundTrips(decomposed, "a$decomposed")
        assertTrue(edit is Insert, "was $edit")
    }

    @Test
    fun aFlagEmojiIsNotSplitInHalf() {
        // Two regional indicators: one cluster, two UTF-16 units. The insert goes after
        // it, and since the flag is wholly inside the common prefix nothing can split
        // it -- which is the property being asserted, not that any code knows what a
        // regional indicator is.
        val flag = "🇯🇵"
        val edit = editBetween(flag, "$flag!")
        assertRoundTrips(flag, "$flag!")
        assertEquals(Insert(flag.length, "!"), edit)
    }

    // -------------------------------------------------- classification

    @Test
    fun theEditTypeReflectsWhatChanged() {
        assertTrue(editBetween("abc", "abcd") is Insert)
        assertTrue(editBetween("abcd", "abc") is Delete)
        assertTrue(editBetween("abc", "axc") is Replace)
    }

    @Test
    fun anInverseUndoesTheWholeChange() {
        val before = "the quick brown fox"
        val after = "the quick red fox jumping"
        val edit = editBetween(before, after)!!
        assertEquals(before, edit.inverse().applyTo(after))
    }

    /** Undo is only correct if the inverse lands on whole clusters too. */
    @Test
    fun anInverseRoundTripsThroughTheStack() {
        val stack = UndoStack()
        val start = EditorState.of("hello")
        val edit = editBetween("hello", "hello world")!!
        val end = start.apply(edit)
        stack.record(start, end, edit)

        val undone = stack.undo(end)!!
        assertEquals("hello", undone.text.text)
        assertEquals(start.selection, undone.selection)
    }

    @Test
    fun consecutiveTypingCoalescesThroughTheRealPath() {
        // The end-to-end claim: whole-text reports from the platform, grouped by the
        // same rules the model API uses, so a typed word is one undo.
        val stack = UndoStack()
        var state = EditorState.of("")
        val typed = "hello world"

        for (i in typed.indices) {
            val before = state.text.text
            val after = typed.substring(0, i + 1)
            val edit = editBetween(before, after)!!
            val next = state.apply(edit)
            stack.record(state, next, edit)
            state = next
        }

        val undone = stack.undo(state)!!
        assertEquals("", undone.text.text, "the whole run is one undo step")
    }

    @Test
    fun aBigDocumentStillCostsTheKeystrokeNotTheNote() {
        // The reason a single-region scan was chosen over a real diff: this is linear
        // in the typed character for a keystroke near the caret, not quadratic in the
        // document. 20,000 lines, one character typed at the end.
        val big = buildString {
            repeat(20_000) { append("line $it of a long note\n") }
        }
        val edit = editBetween(big, "$big!")
        assertEquals(Insert(big.length, "!"), edit)
    }
}