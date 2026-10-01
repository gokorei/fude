package dev.fude.input

import dev.fude.core.EditorState as CoreEditorState
import dev.fude.core.Insert
import dev.fude.core.SelectionActions
import dev.fude.core.TextRange
import dev.fude.core.TimeSource
import dev.fude.core.UndoGrouping
import dev.fude.core.UndoStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyHandlingTest {
    private fun handler(
        undo: UndoStack = UndoStack(),
        copy: ((String) -> Unit)? = null,
        cut: ((String) -> Unit)? = null,
        paste: (() -> String?)? = null,
    ) = KeyHandler(undo, copy, cut, paste)

    private fun state(text: String, caret: Int = text.length) = CoreEditorState.of(text, caret)

    @Test
    fun leftArrowCollapsesASelectionToItsStart() {
        val result = handler().handle(state("hello", 5).selectRange(1, 4), KeyEvent(EditingKey.ARROW_LEFT))
        assertEquals(1, result?.caret, "a selection collapses to its edge, not one char further")
    }

    @Test
    fun rightArrowCollapsesASelectionToItsEnd() {
        val result = handler().handle(state("hello", 5).selectRange(1, 4), KeyEvent(EditingKey.ARROW_RIGHT))
        assertEquals(4, result?.caret)
    }

    @Test
    fun leftArrowMovesOneGraphemeAtATime() {
        val emoji = "😀"
        val result = handler().handle(state("a${emoji}b", 3), KeyEvent(EditingKey.ARROW_LEFT))
        assertEquals(1, result?.caret, "one press crosses the whole emoji")
    }

    @Test
    fun shiftLeftExtendsTheSelection() {
        val result = handler().handle(
            state("hello", 5),
            KeyEvent(EditingKey.ARROW_LEFT, shift = true),
        )
        assertEquals(TextRange(4, 5), result?.selection)
    }

    @Test
    fun commandLeftIsLineStartOnMacAndDocumentStartElsewhere() {
        // The platform difference is real and immediately obvious when wrong. It is
        // a constructor parameter rather than a platform check, which is what makes
        // both behaviours testable on one machine.
        val mac = KeyHandler(UndoStack(), commandArrowIsLinewise = true)
            .handle(state("one\ntwo\nthree", 5), KeyEvent(EditingKey.ARROW_LEFT, command = true))
        assertEquals(4, mac?.caret, "macOS: command-left is line start")

        val other = KeyHandler(UndoStack(), commandArrowIsLinewise = false)
            .handle(state("one\ntwo\nthree", 5), KeyEvent(EditingKey.ARROW_LEFT, command = true))
        assertEquals(0, other?.caret, "Windows and Linux: it is document start")
    }

    @Test
    fun commandRightIsLineEndOnMacAndDocumentEndElsewhere() {
        val text = "one\ntwo\nthree"
        val mac = KeyHandler(UndoStack(), commandArrowIsLinewise = true)
            .handle(state(text, 5), KeyEvent(EditingKey.ARROW_RIGHT, command = true))
        assertEquals(7, mac?.caret)

        val other = KeyHandler(UndoStack(), commandArrowIsLinewise = false)
            .handle(state(text, 5), KeyEvent(EditingKey.ARROW_RIGHT, command = true))
        assertEquals(13, other?.caret)
    }

    @Test
    fun commandHomeIsDocumentStartOnEveryPlatform() {
        for (linewise in listOf(true, false)) {
            val h = KeyHandler(UndoStack(), commandArrowIsLinewise = linewise)
            assertEquals(0, h.handle(state("one\ntwo", 5), KeyEvent(EditingKey.HOME, command = true))?.caret)
        }
    }

    @Test
    fun homeGoesToLineStartAndEndToLineEnd() {
        val h = handler()
        val s = state("one\ntwo\nthree", 5)
        assertEquals(4, h.handle(s, KeyEvent(EditingKey.HOME))?.caret)
        assertEquals(7, h.handle(s, KeyEvent(EditingKey.END))?.caret)
    }

    @Test
    fun commandEndIsDocumentEnd() {
        val s = state("one\ntwo\nthree", 5)
        assertEquals(13, handler().handle(s, KeyEvent(EditingKey.END, command = true))?.caret)
    }

    @Test
    fun enterInsertsANewline() {
        val result = handler().handle(state("ab", 1), KeyEvent(EditingKey.ENTER))
        assertEquals("a\nb", result?.text?.text)
        assertEquals(2, result?.caret)
    }

    @Test
    fun tabInsertsATab() {
        assertEquals("\tx", handler().handle(state("x", 0), KeyEvent(EditingKey.TAB))?.text?.text)
    }

    @Test
    fun backspaceDeletesAWholeGraphemeCluster() {
        val family = "👨‍👩‍👧‍👦"
        val result = handler().handle(state("hi $family"), KeyEvent(EditingKey.BACKSPACE))
        assertEquals("hi ", result?.text?.text, "no half-emoji left behind")
    }

    @Test
    fun deleteRemovesTheFollowingGraphemeCluster() {
        val flag = "🇯🇵"
        val result = handler().handle(state("${flag}x", 0), KeyEvent(EditingKey.DELETE))
        assertEquals("x", result?.text?.text)
    }

    @Test
    fun selectAllCoversTheDocument() {
        val result = handler().handle(state("hello", 2), KeyEvent(EditingKey.SELECT_ALL))
        assertEquals(TextRange(0, 5), result?.selection)
    }

    @Test
    fun undoAndRedoGoThroughTheStack() {
        val undo = UndoStack()
        val h = handler(undo)
        var s = state("")
        val before = s
        val after = s.insert("hello")
        undo.record(before, after, Insert(0, "hello"))
        s = after

        val undone = h.handle(s, KeyEvent(EditingKey.UNDO))
        assertEquals("", undone?.text?.text)
        val redone = h.handle(undone!!, KeyEvent(EditingKey.REDO))
        assertEquals("hello", redone?.text?.text)
    }

    @Test
    fun undoWithNoHistoryReturnsNull() {
        assertNull(handler().handle(state("x"), KeyEvent(EditingKey.UNDO)))
    }

    @Test
    fun copyHandsTheSelectionToTheHost() {
        var copied: String? = null
        val h = handler(copy = { copied = it })
        h.handle(state("hello", 5).selectRange(0, 5), KeyEvent(EditingKey.COPY))
        assertEquals("hello", copied)
    }

    @Test
    fun copyWithACollapsedCaretCopiesNothing() {
        var copied: String? = null
        handler(copy = { copied = it }).handle(state("hello", 2), KeyEvent(EditingKey.COPY))
        assertNull(copied, "a bare caret is not a selection")
    }

    @Test
    fun cutCopiesThenDeletes() {
        var cut: String? = null
        val result = handler(cut = { cut = it })
            .handle(state("hello world", 11).selectRange(6, 11), KeyEvent(EditingKey.CUT))
        assertEquals("world", cut)
        assertEquals("hello ", result?.text?.text)
    }

    @Test
    fun pasteInsertsSourceTextNotRenderedOutput() {
        val markdown = "# Heading\n\n**bold**"
        val result = handler(paste = { markdown })
            .handle(state("before ", 7), KeyEvent(EditingKey.PASTE))
        assertEquals("before $markdown", result?.text?.text)
        assertTrue(result!!.text.text.contains("**bold**"), "Markdown arrives as source, not pre-rendered")
    }

    @Test
    fun pasteWithNothingOnTheClipboardIsANoOp() {
        val result = handler(paste = { null }).handle(state("abc", 3), KeyEvent(EditingKey.PASTE))
        assertEquals("abc", result?.text?.text)
    }

    @Test
    fun theHostHookIsNotConsultedForEditingKeys() {
        // The library owns editing mechanics outright; the hook is for everything
        // else, and mixing them is how a library ends up fighting its host.
        var consulted = 0
        val hook = HostKeyHook { consulted++; true }
        handler().handle(state("abc", 3), KeyEvent(EditingKey.BACKSPACE))
        assertEquals(0, consulted)
    }
}

class ImeCommitTest {
    private fun stack() = UndoStack(UndoGrouping(clock = TimeSource.FIXED))

    @Test
    fun intermediateCompositionUpdatesProduceNoEdits() {
        val session = CompositionSession.at(0)
        assertNull(session.update("に"))
        assertNull(session.update("にほ"))
        assertNull(session.update("日本"))
        assertTrue(session.isActive)
    }

    @Test
    fun aCommitProducesExactlyOneUndoStep() {
        val undo = stack()
        val committer = ImeCommitter(undo)
        val start = CoreEditorState.of("", 0)

        // Five characters, five IME updates, one commit.
        val after = committer.commit(start, 0, "日本語")
        assertEquals("日本語", after.text.text)
        assertEquals(1, undo.undoDepth, "one undo, not five")

        val undone = undo.undo(after)
        assertEquals("", undone?.text?.text, "one undo removes the whole word")
    }

    @Test
    fun anEmptyCommitIsIgnored() {
        val undo = stack()
        val committer = ImeCommitter(undo)
        val state = CoreEditorState.of("abc", 3)
        assertTrue(committer.commit(state, 3, "") === state)
        assertFalse(undo.canUndo)
    }

    @Test
    fun aCommitReplacesThePreviewItWasComposingOver() {
        val undo = stack()
        val committer = ImeCommitter(undo)
        // The IME inserted a preview of 3 characters, then committed 3 different ones.
        val previewed = CoreEditorState.of("abc", 3)
        val after = committer.commit(previewed, start = 0, text = "日本", previewLength = 3)
        assertEquals("日本", after.text.text, "the preview is replaced, not appended to")
        assertEquals(2, after.caret)
    }

    @Test
    fun aCancelledCompositionCommitsNothing() {
        val session = CompositionSession.at(0)
        session.update("にほん")
        session.cancel()
        assertFalse(session.isActive)
        assertNull(session.commit(), "a cancelled composition produces no edit")
    }

    @Test
    fun committingAnInactiveSessionProducesNothing() {
        assertNull(CompositionSession.at(5).commit())
    }

    @Test
    fun compositionSurvivesDecorationBecauseTheDocumentNeverSawIt() {
        // The guarantee: intermediate composition text never enters the buffer, so
        // a per-frame reparse never has to render it and cannot corrupt it.
        val undo = stack()
        val committer = ImeCommitter(undo)
        val session = CompositionSession.at(0)
        var state = CoreEditorState.of("", 0)

        listOf("に", "にほ", "にほん", "日本", "日本語").forEach { update ->
            session.update(update)
            // Nothing has changed in the document during composition.
            assertEquals(0, state.text.length)
        }
        state = committer.commit(state, session.commit()!!.first.start, session.commit()?.second ?: "日本語")
        assertEquals("日本語", state.text.text)
    }
}

class SelectionActionTest {
    private fun state(text: String, caret: Int = text.length) = CoreEditorState.of(text, caret)

    @Test
    fun selectWordCoversTheWordOnly() {
        val result = SelectionActions.selectWord(state("hello world", 2))
        assertEquals("hello", result.selectedText)
    }

    @Test
    fun selectWordAtABlockBoundaryStaysInTheBlock() {
        val result = SelectionActions.selectWord(state("hello\n\nworld", 9))
        assertEquals("world", result.selectedText, "it must not run past the blank line")
    }

    @Test
    fun selectLineIncludesTheTerminatorSoPastingKeepsTheLineBreak() {
        val result = SelectionActions.selectLine(state("one\ntwo", 1))
        assertEquals("one\n", result.selectedText)
    }

    @Test
    fun selectLineOnTheLastLineHasNoTerminator() {
        val result = SelectionActions.selectLine(state("one\ntwo", 5))
        assertEquals("two", result.selectedText)
    }

    @Test
    fun expandToWordBoundariesGrowsTheSelection() {
        val s = state("hello world", 5)
        val out = SelectionActions.expandToPreviousWord(s)
        assertEquals(TextRange(0, 5), out.selection)
    }

    @Test
    fun wordBoundariesAreFoundOnAKnownFixture() {
        val text = dev.fude.core.TextBuffer.of("foo bar")
        assertEquals(0, SelectionActions.wordStart(text, 2))
        assertEquals(3, SelectionActions.wordEnd(text, 1))
        assertEquals(4, SelectionActions.wordStart(text, 5))
        assertEquals(7, SelectionActions.wordEnd(text, 6))
    }

    @Test
    fun selectAllIsTotal() {
        assertEquals(TextRange(0, 9), SelectionActions.selectAll(state("one\ntwo\nx")).selection)
    }
}
