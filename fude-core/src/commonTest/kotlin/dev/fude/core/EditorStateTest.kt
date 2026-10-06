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

    /**
     * Scroll survives every operation on the model.
     *
     * Worth its own file's worth of assertions because the Compose-side shell got this
     * wrong for as long as it existed: `EditorState.applyEdit` rebuilt the state
     * through `of`, which starts at zero, so every keystroke threw the viewport back
     * to the top. The model was never the problem — `apply` copies — but nothing here
     * said so, so a reader had no way to tell which half of the editor was at fault.
     *
     * A no-op edit is the interesting one: it returns *this*, so it preserves scroll
     * by identity rather than by copying.
     */
    @Test
    fun scrollSurvivesEveryMutationTheModelOffers() {
        val scrolled = { EditorState.of("hello world").withScroll(ScrollState(120f, 900f)) }

        assertEquals(120f, scrolled().replace(TextRange(5, 5), "!").scroll.offset, "an edit")
        assertEquals(120f, scrolled().insertAt(3, "abc").scroll.offset, "an insert at an offset")
        assertEquals(120f, scrolled().insert("x").scroll.offset, "an insert at the caret")
        assertEquals(120f, scrolled().deleteBackward().scroll.offset, "a backward delete")
        assertEquals(120f, scrolled().deleteForward().scroll.offset, "a forward delete")
        assertEquals(120f, scrolled().apply(Delete(TextRange(0, 2), "he")).scroll.offset, "a raw delete")
        assertEquals(120f, scrolled().moveCaretTo(3).scroll.offset, "moving the caret")
        assertEquals(120f, scrolled().selectRange(1, 4).scroll.offset, "selecting")
        assertEquals(120f, scrolled().select(TextRange(0, 3)).scroll.offset, "selecting a range")

        val noOp = scrolled().apply(Insert(2, ""))
        assertEquals(120f, noOp.scroll.offset, "and so does an edit that changes nothing")
    }

    /**
     * The document-swap case is a reset, and it is deliberate.
     *
     * `of` is how a host loads a note, and it starts at the top: "the note opened
     * scrolled to the middle of itself" is not a behaviour any editor has. What this
     * pins down is that the zero is *chosen* — and that a host restoring a position
     * can, by constructing the state with one.
     */
    @Test
    fun aNewDocumentStartsAtTheTopAndAHostCanSayOtherwise() {
        assertEquals(ScrollState.ZERO, EditorState.of("a different note").scroll)
        assertEquals(
            250f,
            EditorState.of("a different note").withScroll(ScrollState(250f, 900f)).scroll.offset,
            "and a host that wants a remembered position sets it deliberately",
        )
    }

    /**
     * `caret` is the selection's start, for every selection.
     *
     * This used to be `if (selection.isCollapsed) selection.start else selection.start`
     * — two identical branches. The alternative was `selection.end`, which is wrong
     * here for a reason worth pinning down: `selectRange` normalises its arguments, so
     * `TextRange.end` is the *larger* offset rather than the one a drag came from. A
     * model that has thrown the direction away cannot report an active end, and
     * guessing at one puts the caret at the wrong end of every backwards selection.
     */
    @Test
    fun caretIsTheSelectionStartEvenForARange() {
        assertEquals(4, EditorState.of("hello world", caret = 4).caret)
        assertEquals(2, EditorState.of("hello world").selectRange(2, 8).caret)
        // Which is not the same as the end, whatever the host passed in.
        assertEquals(8, EditorState.of("hello world").selectRange(2, 8).selection.end)
    }
}
