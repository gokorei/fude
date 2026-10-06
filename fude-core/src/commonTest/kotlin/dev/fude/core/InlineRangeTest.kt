package dev.fude.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Range arithmetic, including the surrogate-pair cases.
 *
 * Every one of these exists because the naive version is wrong somewhere: an
 * offset that counts code units and calls them characters will happily split a
 * surrogate pair and produce a caret in the middle of an emoji.
 */
class InlineRangeTest {
    @Test
    fun aRangeKnowsItsLength() {
        assertEquals(4, InlineRange(2, 6).length)
        assertTrue(InlineRange(3, 3).isEmpty)
    }

    /**
     * [InlineRange] is half-open: it covers `[start, end)`.
     *
     * This test used to be called `containmentIsInclusiveOfEndpoints` and asserted
     * `InlineRange(2, 6).contains(6)`. That was the wrong reading of the class's own
     * KDoc — "an inclusive-exclusive character range" — and `offset in start..end` is a
     * closed range in Kotlin, so it claimed the offset one past the end.
     *
     * It was changed rather than deleted, and the assertion was inverted rather than
     * dropped, because the ticket is the fix and a silent deletion would hide it. The
     * reason half-open is the right answer is in [abuttingRangesClaimExactlyOneOffset]:
     * inclusive semantics gives an offset between two adjacent decorations two correct
     * answers, and every call site would pick differently.
     */
    @Test
    fun containmentIsHalfOpenAndStopsAtTheEnd() {
        val range = InlineRange(2, 6)
        assertTrue(range.contains(2), "the first offset is inside")
        assertTrue(range.contains(5), "the last offset is inside")
        assertTrue(!range.contains(6), "the offset one past the end is not")
        assertTrue(!range.contains(1), "and neither is the one before the start")
    }

    /**
     * Two decorations side by side, and the offset between them belongs to exactly one.
     *
     * This is the case `230QC1VE` needs when it asks "did the user click this
     * decoration?". With an inclusive `contains`, offset 7 is claimed by both
     * `[3,7]` and `[7,11]`, so a hit test has two correct answers and no way to choose
     * between them — and the choice it makes will differ from call site to call site.
     */
    @Test
    fun abuttingRangesClaimExactlyOneOffset() {
        val left = InlineRange(3, 7)
        val right = InlineRange(7, 11)

        val claimants = listOf(left, right).filter { it.contains(7) }
        assertEquals(1, claimants.size, "offset 7 is the boundary and belongs to one range")
        assertEquals(
            right,
            claimants.single(),
            "and with half-open semantics it is the range that starts there",
        )

        // The whole shared boundary, not just this one pair. `[0,20)` and `[20,40)`
        // cover every offset in between exactly once, with nothing double-claimed and
        // nothing orphaned — which is the property an inclusive test cannot give.
        val first = InlineRange(0, 20)
        val second = InlineRange(20, 40)
        for (offset in 0 until 40) {
            assertEquals(
                1,
                listOf(first, second).count { it.contains(offset) },
                "offset $offset must be claimed by exactly one of [0,20) and [20,40)",
            )
        }
        assertTrue(!first.contains(40) && !second.contains(0), "and no range claims a neighbour's interior")
    }

    /**
     * An empty range contains nothing, which is the consequence of half-open.
     *
     * Worth its own test because `InlineRange.ZERO` is used as a sentinel for "this
     * rendered element has no source of its own" — see [VisualToSourceMapper] — and a
     * sentinel that claimed offset 0 would collide with the real source range that
     * starts there. `VisualToSourceMapper` tests emptiness separately and never calls
     * `contains`, so nothing there changes.
     */
    @Test
    fun anEmptyRangeContainsNothing() {
        assertTrue(InlineRange.ZERO.isEmpty)
        assertTrue(!InlineRange.ZERO.contains(0), "the sentinel must not claim any offset")
        assertTrue(!InlineRange(4, 4).contains(4))
    }

    @Test
    fun rangesOverlapOnlyWhenTheyActuallyShare() {
        assertTrue(InlineRange(0, 5).intersects(InlineRange(4, 8)))
        assertTrue(!InlineRange(0, 5).intersects(InlineRange(5, 8)), "abutting ranges do not overlap")
        assertTrue(!InlineRange(0, 2).intersects(InlineRange(3, 5)))
    }

    /** `intersects` is already half-open; it must not gain an inclusive endpoint. */
    @Test
    fun intersectionIsHalfOpenToo() {
        assertTrue(!InlineRange(0, 5).intersects(InlineRange(5, 8)), "an endpoint is not an overlap")
        assertTrue(InlineRange(0, 6).intersects(InlineRange(5, 8)), "one shared character is")
        assertTrue(InlineRange(0, 5).intersects(InlineRange(0, 5)), "a range overlaps itself")
    }

    @Test
    fun reversedRangesAreRejected() {
        assertFailsWith<IllegalArgumentException> { InlineRange(5, 2) }
        assertFailsWith<IllegalArgumentException> { InlineRange(-1, 3) }
    }

    @Test
    fun rangesMapThroughAnInsertion() {
        val range = InlineRange(10, 20)
        val mapped = range.mapThrough(Insert(5, "abc"))
        assertEquals(InlineRange(13, 23), mapped)
    }

    @Test
    fun aRangeAfterADeletionShrinks() {
        val mapped = InlineRange(10, 20).mapThrough(Delete(TextRange(0, 5), "12345"))
        assertEquals(InlineRange(5, 15), mapped)
    }

    @Test
    fun aRangeInsideADeletedRangeCollapses() {
        val mapped = InlineRange(10, 20).mapThrough(Delete(TextRange(8, 22), "x"))
        assertEquals(InlineRange(8, 8), mapped, "there is nothing left to decorate")
    }

    @Test
    fun rangesAndSelectionsAreDistinctTypes() {
        // Deliberately separate: a selection can be collapsed, a decoration range
        // cannot, and conflating them is how a caret ends up inside a style span.
        val selection = TextRange(3, 3)
        val range = InlineRange(3, 8)
        assertTrue(selection.isCollapsed)
        assertTrue(!range.isEmpty)
    }
}
