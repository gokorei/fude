package dev.fude.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditTest {
    private val buffer = TextBuffer.of("hello world")

    @Test
    fun insertAtOffset() {
        val edit = Insert(5, ",")
        assertEquals("hello, world", edit.applyTo(buffer.text))
    }

    @Test
    fun deleteRemovesRange() {
        val edit = Delete(TextRange(5, 11), " world")
        assertEquals("hello", edit.applyTo(buffer.text))
    }

    @Test
    fun everyEditIsInvertible() {
        val edits = listOf(
            Insert(0, "X"),
            Delete(TextRange(5, 11), " world"),
            Replace(TextRange(0, 5), removed = "hello", inserted = "goodbye"),
        )
        for (edit in edits) {
            val after = edit.applyTo(buffer.text)
            val back = edit.inverse().applyTo(after)
            assertEquals(buffer.text, back, "round trip failed for ${edit.describe}")
        }
    }

    @Test
    fun insertInverseRemovesExactlyWhatWasInserted() {
        val edit = Insert(5, "XYZ")
        assertEquals("hello world", edit.inverse().applyTo(edit.applyTo(buffer.text)))
    }

    @Test
    fun replaceCapturesRemovedTextForItsInverse() {
        val edit = replace(buffer, TextRange(0, 5), "goodbye")
        assertTrue(edit is Replace)
        assertEquals("goodbye world", edit.applyTo(buffer.text))
        assertEquals("hello world", edit.inverse().applyTo(edit.applyTo(buffer.text)))
    }

    @Test
    fun caretAtInsertionPointMovesDownstream() {
        val edit = Insert(5, "XYZ")
        assertEquals(8, edit.mapOffset(5, OffsetAffinity.DOWNSTREAM))
        assertEquals(5, edit.mapOffset(5, OffsetAffinity.UPSTREAM))
    }

    @Test
    fun caretInsideDeletedRangeCollapsesToDeletionStart() {
        val edit = Delete(TextRange(5, 11), " world")
        assertEquals(5, edit.mapOffset(7))
        assertEquals(5, edit.mapOffset(5))
        assertEquals(5, edit.mapOffset(11))
    }

    @Test
    fun caretAfterEditShiftsByDelta() {
        val edit = Insert(0, "ab")
        assertEquals(13, edit.mapOffset(11))
    }

    @Test
    fun editsCompareByValue() {
        assertEquals(Insert(3, "x"), Insert(3, "x"))
        assertNotEquals<Edit>(Insert(3, "x"), Insert(3, "y"))
    }

    @Test
    fun noOpEditsAreDetectable() {
        assertTrue(Insert(0, "").isNoOp(buffer))
        assertTrue(Delete(TextRange(3, 3), "").isNoOp(buffer))
        assertEquals(buffer, buffer.withEdit(Insert(0, "")))
    }
}
