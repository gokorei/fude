package dev.fude.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Undo grouping.
 *
 * The rule that matters and is most often got wrong: a space does **not** break
 * the run. "hello world" is one undo step, because that is what a person means by
 * typing a word. A rule that splits on whitespace turns eleven keystrokes into
 * eleven undos, which is the single most common complaint about an editor's undo.
 */
class UndoStackTest {
    private fun stack(clock: TimeSource = TimeSource.FIXED, coalesce: Boolean = true): UndoStack =
        UndoStack(UndoGrouping(clock = clock, coalesceTyping = coalesce))

    /** Types [text] one character at a time, the way a keyboard delivers it. */
    private fun type(initial: EditorState, text: String, undo: UndoStack): EditorState {
        var state = initial
        for (char in text) {
            val next = state.insert(char.toString())
            undo.record(state, next, Insert(state.selection.start, char.toString()))
            state = next
        }
        return state
    }

    @Test
    fun aWordIsOneUndoStep() {
        val undo = stack(coalesce = false)
        var state = EditorState()
        for (c in "hello") {
            val next = state.insert(c.toString())
            undo.record(state, next, Insert(state.selection.start, c.toString()))
            state = next
        }
        assertEquals(5, undo.undoDepth, "with coalescing off every keystroke is its own step")
    }

    @Test
    fun typingCoalescesIntoOneStep() {
        val undo = stack()
        val state = type(EditorState(), "hello", undo)
        assertEquals("hello", state.text.text)
        assertEquals(1, undo.undoDepth, "five characters are one undo step")

        val undone = undo.undo(state)!!
        assertEquals("", undone.text.text, "one undo removes the whole word")
    }

    @Test
    fun aSpaceDoesNotBreakTheRun() {
        val undo = stack()
        val state = type(EditorState(), "hello world", undo)
        assertEquals("hello world", state.text.text)
        assertEquals(1, undo.undoDepth, "a space must not start a new undo step")

        val undone = undo.undo(state)!!
        assertEquals("", undone.text.text)
    }

    @Test
    fun typingOnWithNoPauseStaysOneStep() {
        val undo = stack()
        var state = type(EditorState(), "hello ", undo)
        assertEquals(1, undo.undoDepth)

        state = type(state, "world", undo)
        assertEquals(1, undo.undoDepth, "continuing to type without a pause is one run")
        assertEquals("", undo.undo(state)!!.text.text)
    }

    @Test
    fun aNewlineBreaksTheRun() {
        val undo = stack()
        val state = type(EditorState(), "ab\ncd", undo)
        assertTrue(undo.undoDepth >= 2, "a newline is structural, not typing")
    }

    @Test
    fun movingTheCaretBreaksTheRun() {
        val undo = stack()
        var state = type(EditorState(), "hello", undo)
        assertEquals(1, undo.undoDepth)

        state = state.moveCaretTo(0)
        val next = state.insert("X")
        undo.record(state, next, Insert(0, "X"))
        state = next

        assertEquals(2, undo.undoDepth, "typing elsewhere is a new intent")
    }

    @Test
    fun nonContiguousTypingBreaksTheRun() {
        val undo = stack()
        var state = type(EditorState(), "abc", undo)
        state = state.moveCaretTo(1)
        val next = state.insert("X")
        undo.record(state, next, Insert(1, "X"))
        state = next
        assertTrue(undo.undoDepth >= 2)
    }

    @Test
    fun anIdleGapBreaksTheRun() {
        val clock = TimeSource.Companion.Manual()
        val undo = stack(clock = clock)
        var state = EditorState()
        state = type(state, "ab", undo)
        assertEquals(1, undo.undoDepth)

        clock.advance(5_000)
        val next = state.insert("c")
        undo.record(state, next, Insert(state.selection.start, "c"))
        state = next

        assertEquals(2, undo.undoDepth, "typing after a pause starts a new step")
    }

    @Test
    fun subTimeoutGapsKeepTheRunTogether() {
        val clock = TimeSource.Companion.Manual()
        val undo = stack(clock = clock)
        var state = EditorState()

        // Six characters 300ms apart. Every gap is well under the 800ms idle limit,
        // but the run's total duration is 1.8s — longer than the limit itself. The
        // limit is on the gap *between* keystrokes, not on how long a word takes.
        repeat(6) {
            clock.advance(300)
            val next = state.insert("a")
            undo.record(state, next, Insert(state.selection.start, "a"))
            state = next
        }

        assertEquals("aaaaaa", state.text.text)
        assertEquals(1, undo.undoDepth, "a continuous run is one step however long it takes")
        assertEquals("", undo.undo(state)!!.text.text, "one undo clears the word")
    }

    @Test
    fun theIdleLimitBoundsTheGapNotTheRun() {
        val clock = TimeSource.Companion.Manual()
        val undo = stack(clock = clock)
        var state = EditorState()

        // A gap of exactly idleTimeoutMillis must still merge: the limit is the point
        // beyond which the user is considered to have stopped typing, not the point at
        // which they are considered to have typed too much.
        repeat(6) {
            clock.advance(800)
            val next = state.insert("a")
            undo.record(state, next, Insert(state.selection.start, "a"))
            state = next
        }
        assertEquals(1, undo.undoDepth, "four seconds of typing without a pause is one step")

        clock.advance(801)
        val next = state.insert("a")
        undo.record(state, next, Insert(state.selection.start, "a"))
        state = next
        assertEquals(2, undo.undoDepth, "one millisecond past the limit does break the run")
        assertEquals("aaaaaa", undo.undo(state)!!.text.text, "and only the character after the pause undoes")
    }

    @Test
    fun aDeleteIsItsOwnStep() {
        val undo = stack()
        var state = EditorState.of("hello")
        state = type(state, "x", undo)
        assertEquals(1, undo.undoDepth)

        val before = state
        val after = state.deleteBackward()
        undo.record(before, after, Delete(TextRange(5, 6), "x"))
        state = after

        assertEquals(2, undo.undoDepth, "a delete is a discrete intent")
        assertEquals("hellox", undo.undo(state)!!.text.text)
    }

    @Test
    fun anImeCommitIsOneStepRegardlessOfIntermediateUpdates() {
        // An IME reports composition updates as the user picks characters, then
        // commits once. The commit is a single edit, so it is a single undo step.
        val undo = stack()
        val before = EditorState.of("", caret = 0)
        val commit = Insert(0, "日本語")
        val after = before.apply(commit)
        undo.record(before, after, commit)

        assertEquals(1, undo.undoDepth)
        val undone = undo.undo(after)!!
        assertEquals("", undone.text.text, "one undo removes the whole committed word")
    }

    @Test
    fun undoThenRedoRestoresTheDocument() {
        val undo = stack()
        val state = type(EditorState(), "hello", undo)
        assertTrue(undo.canUndo)

        val undone = undo.undo(state)!!
        assertTrue(undo.canRedo)

        val redone = undo.redo(undone)!!
        assertEquals("hello", redone.text.text)
        assertEquals(5, redone.caret)
    }

    @Test
    fun undoWithNoHistoryReturnsNull() {
        val undo = stack()
        assertFalse(undo.canUndo)
        assertNull(undo.undo(EditorState()), "null rather than a silently unchanged state")
        assertNull(undo.redo(EditorState()))
    }

    @Test
    fun typingAfterAnUndoAbandonsTheRedoBranch() {
        val undo = stack()
        var state = type(EditorState(), "ab", undo)
        val undone = undo.undo(state)!!
        assertTrue(undo.canRedo)

        val next = undone.insert("z")
        undo.record(undone, next, Insert(0, "z"))
        state = next

        assertFalse(undo.canRedo, "a new edit abandons the undone branch")
    }

    @Test
    fun noOpEditsAreNotRecorded() {
        val undo = stack()
        val state = EditorState.of("abc")
        val edit = Insert(3, "")
        undo.record(state, state, edit)
        assertFalse(undo.canUndo)
    }

    @Test
    fun selectionOnlyChangesAreNotRecorded() {
        val undo = stack()
        val before = EditorState.of("abc", caret = 1)
        val after = before.moveCaretTo(2)
        undo.record(before, after, Insert(1, ""))
        assertFalse(undo.canUndo, "moving the caret is not an edit")
    }

    @Test
    fun aPauseThenMoreTypingUndoesInTwoSteps() {
        val clock = TimeSource.Companion.Manual()
        val undo = stack(clock = clock)
        var state = type(EditorState(), "hi ", undo)
        assertEquals("hi ", state.text.text)

        clock.advance(2_000)
        state = type(state, "there", undo)
        assertEquals("hi there", state.text.text)
        assertEquals(2, undo.undoDepth, "a pause ends the run")

        val once = undo.undo(state)!!
        assertEquals("hi ", once.text.text, "only what was typed after the pause undoes")
        val twice = undo.undo(once)!!
        assertEquals("", twice.text.text)
    }

    @Test
    fun historyIsBounded() {
        val undo = UndoStack(UndoGrouping(coalesceTyping = false), limit = 3)
        var state = EditorState()
        for (c in "abcdefgh") {
            val next = state.insert(c.toString())
            undo.record(state, next, Insert(state.selection.start, c.toString()))
            state = next
        }
        assertEquals(3, undo.undoDepth, "older entries fall off the bottom")
    }
}
