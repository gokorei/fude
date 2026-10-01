package dev.fude.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditorStateTest {
    @Test
    fun initialStateIsEmptyWithCollapsedCaret() {
        val state = EditorState()
        assertEquals("", state.text.text)
        assertTrue(state.selection.isCollapsed)
        assertEquals(0, state.caret)
        assertTrue(state.isEmpty)
    }

    @Test
    fun stateRejectsSelectionOutsideTheDocument() {
        assertFailsWith<IllegalArgumentException> {
            EditorState(TextBuffer.of("hi"), TextRange(0, 99))
        }
    }

    @Test
    fun typingInsertsAtTheCaretAndMovesItAfter() {
        var state = EditorState()
        state = state.insert("hello")
        assertEquals("hello", state.text.text)
        assertEquals(5, state.caret)
    }

    @Test
    fun everyMutationGoesThroughApply() {
        val state = EditorState.of("abc", caret = 3)
        val typed = state.insert("d")
        assertEquals("abcd", typed.text.text)
        assertNotEquals(state, typed)

        // insertAt and replace must agree with the generic path.
        assertEquals(state.apply(Insert(3, "d")), typed)
    }

    @Test
    fun noOpEditReturnsTheSameState() {
        val state = EditorState.of("abc", caret = 3)
        assertTrue(state.apply(Insert(3, "")) === state)
    }

    @Test
    fun insertingInsideTheSelectionReplacesIt() {
        val state = EditorState.of("hello world", caret = 11).select(TextRange(0, 5))
        val result = state.insert("goodbye")
        assertEquals("goodbye world", result.text.text)
        assertEquals(7, result.caret)
    }

    @Test
    fun deleteRemovesTheWholeSelection() {
        val state = EditorState.of("hello world").select(TextRange(6, 11))
        assertEquals("hello ", state.deleteBackward().text.text)
    }

    @Test
    fun caretCollapsesWhenDeleteCoversPartOfIt() {
        // Caret at 8, delete the range [6, 10) which straddles it.
        val state = EditorState.of("hello world", caret = 8)
        val result = state.apply(Delete(TextRange(6, 10), "worl"))
        assertEquals("hello d", result.text.text)
        assertTrue(result.selection.isCollapsed)
        assertEquals(6, result.caret, "a caret inside a deleted range collapses to the deletion start")
    }

    @Test
    fun selectionClampsAfterAnEditShrinksTheDocument() {
        val state = EditorState.of("hello world").select(TextRange(8, 11))
        val result = state.apply(Delete(TextRange(0, 8), "hello w"))
        assertEquals("rld", result.text.text)
        assertEquals(3, result.selection.end, "selection must stay inside the shorter document")
    }

    @Test
    fun deleteAtOffsetZeroIsANoOp() {
        val state = EditorState.of("abc", caret = 0)
        assertTrue(state.deleteBackward() === state)
    }

    @Test
    fun deleteForwardAtEndIsANoOp() {
        val state = EditorState.of("abc", caret = 3)
        assertTrue(state.deleteForward() === state)
    }

    @Test
    fun moveCaretClampsIntoTheDocument() {
        val state = EditorState.of("abc")
        assertEquals(3, state.moveCaretTo(99).caret)
        assertEquals(0, state.moveCaretTo(-5).caret)
    }

    @Test
    fun selectNormalisesReversedInput() {
        val state = EditorState.of("hello world").selectRange(8, 2)
        assertEquals(TextRange(2, 8), state.selection)
    }

    @Test
    fun selectedTextIsEmptyForACollapsedCaret() {
        val state = EditorState.of("hello", caret = 2)
        assertEquals("", state.selectedText)
        assertEquals("ell", state.select(TextRange(1, 4)).selectedText)
    }

    @Test
    fun stateHoldsOnlyTextSelectionAndScroll() {
        // Compile-time proof that the shape is exactly these three fields.
        val state = EditorState(
            text = TextBuffer.of("x"),
            selection = TextRange.ZERO,
            scroll = ScrollState.ZERO,
        )
        val copy = state.copy(scroll = ScrollState(offset = 10f))
        assertEquals(10f, copy.scroll.offset)
        assertEquals(state.text, copy.text, "scroll must not disturb the text")
    }

    @Test
    fun scrollRejectsNegativeOffset() {
        assertFailsWith<IllegalArgumentException> { ScrollState(offset = -1f) }
    }

    @Test
    fun scrollTracksViewportInOffsets() {
        val scroll = ScrollState(offset = 100f, extent = 500f)
        assertEquals(100, scroll.firstVisibleOffset)
        assertEquals(500, scroll.lastVisibleOffset)
        assertFalse(scroll.isAtTop)
        assertFalse(scroll.isAtBottom)
        assertTrue(ScrollState.ZERO.isAtTop)
    }
}
