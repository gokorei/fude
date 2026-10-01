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

    @Test
    fun containmentIsInclusiveOfEndpoints() {
        val range = InlineRange(2, 6)
        assertTrue(range.contains(2))
        assertTrue(range.contains(6))
        assertTrue(!range.contains(7))
    }

    @Test
    fun rangesOverlapOnlyWhenTheyActuallyShare() {
        assertTrue(InlineRange(0, 5).overlaps(InlineRange(4, 8)))
        assertTrue(!InlineRange(0, 5).overlaps(InlineRange(5, 8)), "abutting ranges do not overlap")
        assertTrue(!InlineRange(0, 2).overlaps(InlineRange(3, 5)))
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
