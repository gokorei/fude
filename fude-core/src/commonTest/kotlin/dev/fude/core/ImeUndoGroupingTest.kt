package dev.fude.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One IME commit is one undo step, without Fude observing the IME at all.
 *
 * `CompositionSession` and `ImeCommitter` existed to force this: a five-character
 * Japanese word arriving one character at a time would otherwise cost five undos. They
 * had no caller, and Compose implements the whole IME transaction itself, so both are
 * deleted. That is only safe if the property survives their removal, which is what
 * this asserts rather than assumes.
 *
 * The mechanism is that undo is fed from the *gesture path*, not from keystrokes.
 * `MarkdownEditor` receives a whole-string report from the platform and recovers a
 * single `Edit` with `editBetween`, and `UndoStack.record` is handed that one `Edit`.
 * A platform committing a composed word as one buffer change therefore produces one
 * edit, and there is nothing to coalesce because nothing was split.
 *
 * The word arriving as five separate reports is the case `ImeCommitter` was written
 * for, and it is covered here too — it turns out `UndoGrouping` already handles it,
 * because merging is decided by adjacency and the idle timeout rather than by whether
 * an edit was typed or composed. That is worth stating as the finding rather than
 * presenting the one-buffer-change case as the only thing that works.
 *
 * The guard test matters as much as the passing ones: with coalescing switched off,
 * the same five reports must cost five steps. Without it, "a word is one undo" would
 * also be satisfiable by a stack that discarded edits.
 */
class ImeUndoGroupingTest {

    /** Appends [chunk] as one buffer change, exactly as a platform would report it. */
    private fun report(stack: UndoStack, state: EditorState, chunk: String): EditorState {
        val before = state
        val after = state.apply(Insert(state.text.length, chunk))
        val edit = editBetween(before.text.text, after.text.text)
        if (edit != null) stack.record(before, after, edit)
        return after
    }

    @Test
    fun aCommittedWordArrivingAsOneBufferChangeIsOneUndoStep() {
        val clock = TimeSource.Companion.Manual()
        val stack = UndoStack(UndoGrouping(clock = clock))
        var state = EditorState.of("")

        state = report(stack, state, "a ")
        clock.advance(2_000)
        state = report(stack, state, "日本語")      // one commit
        clock.advance(2_000)
        state = report(stack, state, " b")

        assertEquals("a 日本語 b", state.text.text)
        assertEquals(3, stack.undoDepth, "three separated edits, three steps")

        // Undo pops the newest entry, so the word is revealed after two steps. It
        // disappears as a unit: not one character of it, and not three steps of it.
        val afterOne = stack.undo(state)!!
        assertEquals("a 日本語", afterOne.text.text, "the trailing edit goes first")
        val afterTwo = stack.undo(afterOne)!!
        assertEquals("a ", afterTwo.text.text, "and the whole word is one step")
        assertEquals(1, stack.undoDepth)
    }

    @Test
    fun theSameWordArrivingOneCharacterAtATimeIsAlsoOneUndoStep() {
        val clock = TimeSource.Companion.Manual()
        val stack = UndoStack(UndoGrouping(clock = clock))
        var state = EditorState.of("")

        state = report(stack, state, "a ")
        clock.advance(2_000)
        for (c in listOf("日", "本", "語")) state = report(stack, state, c)
        clock.advance(2_000)
        state = report(stack, state, " b")

        assertEquals("a 日本語 b", state.text.text)
        assertEquals(3, stack.undoDepth, "still three steps: the three characters coalesced")

        val afterOne = stack.undo(state)!!
        assertEquals("a 日本語", afterOne.text.text)
        val afterTwo = stack.undo(afterOne)!!
        assertEquals("a ", afterTwo.text.text, "the three characters were one step, not three")
    }

    @Test
    fun withCoalescingOffEachReportIsItsOwnStep() {
        // The guard. Five reports, five steps — so the two tests above are observing
        // coalescing rather than a stack that quietly drops edits.
        val stack = UndoStack(UndoGrouping(coalesceTyping = false))
        var state = EditorState.of("")
        for (c in listOf("日", "本", "語")) state = report(stack, state, c)
        assertEquals(3, stack.undoDepth)
    }

    @Test
    fun anIdleGapIsWhatSeparatesTwoSteps() {
        // Which is the real mechanism: merging is time-based, not keystroke-based.
        // Two edits inside the timeout are one step; the same two outside it are two.
        val clock = TimeSource.Companion.Manual()
        val stack = UndoStack(UndoGrouping(clock = clock, idleTimeoutMillis = 800L))
        var state = EditorState.of("")

        state = report(stack, state, "one")
        clock.advance(300)
        state = report(stack, state, "two")
        assertEquals(1, stack.undoDepth, "within the idle timeout, one run")

        clock.advance(5_000)
        state = report(stack, state, "three")
        assertEquals(2, stack.undoDepth, "past it, a separate step")
    }
}
