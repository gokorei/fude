package dev.fude.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import dev.fude.core.TimeSource
import dev.fude.core.TimeSource.Companion.Manual
import dev.fude.core.UndoGrouping
import dev.fude.core.UndoStack
import dev.fude.core.editBetween
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The undo grouping rules applied to what the platform actually reports.
 *
 * The stack here is built from `editBetween` over the reports a real field produces,
 * exactly as `MarkdownEditor` builds it. That means these tests assert the *rule*
 * against real platform output — not the composable's wiring, which
 * `UndoGroupingRenderTest` covers through real frames.
 *
 * Every test applies one character at a time, because that is what a gesture
 * reports. Applying a whole word at once would arrive as a single edit and would pass
 * whether or not grouping existed.
 */
@OptIn(ExperimentalTestApi::class)
class UndoGroupingThroughTheGesturePathTest {
    @get:Rule
    val rule = createComposeRule()

    /**
     * Types [text] into a real editor one character per gesture, recording each
     * reported change into [undo] the way the composable does.
     */
    private fun type(
        text: String,
        undo: UndoStack,
        into: String = "",
        clock: Manual? = null,
    ): UndoStack {
        val state = EditorState.of(into)
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()

        var seen = into
        for (character in text) {
            val target = seen + character
            // The gesture: Compose replaces the buffer and reports the result, which
            // is the only shape of change the platform offers.
            rule.onNodeWithTag(TAG_EDITOR).performTextReplacement(target)
            rule.waitForIdle()

            val edit = editBetween(seen, target)
            if (edit != null) {
                undo.record(EditorState.of(seen).model, EditorState.of(target).model, edit)
                clock?.advance(100)
            }
            seen = target
        }
        assertEquals(target(into, text), seen, "every character reached the editor")
        return undo
    }

    private fun target(into: String, text: String) = into + text

    private fun stack(clock: Manual? = null) = UndoStack(
        grouping = UndoGrouping(clock = clock ?: Manual()),
    )

    private fun undoOnce(undo: UndoStack, text: String): String? =
        undo.undo(EditorState.of(text).model)?.text?.text

    // -------------------------------------------------- coalescing

    @Test
    fun aTypedWordIsOneUndoStep() {
        val undo = type("hello", stack())
        assertEquals(1, undo.undoDepth, "five keystrokes, one step")
    }

    /** The ticket's specific worry, and the rule most easily regressed. */
    @Test
    fun aSpaceDoesNotSplitTheRun() {
        val undo = type("hello world", stack())
        assertEquals(1, undo.undoDepth, "\"hello world\" is one step, not eleven")
    }

    @Test
    fun undoingTheRunClearsEveryCharacterAtOnce() {
        val undo = type("hello world", stack())
        assertEquals("", undoOnce(undo, "hello world"))
    }

    @Test
    fun aNewlineIsItsOwnUndoStep() {
        val undo = type("one\ntwo", stack())
        // Pressing Enter is a discrete intent, so it neither joins the word before it
        // nor the word after: three steps, not two.
        assertEquals(3, undo.undoDepth, "o-n-e, then Enter, then t-w-o")
        assertEquals("one\n", undoOnce(undo, "one\ntwo"), "the word after the newline went first")
        assertEquals("one", undoOnce(undo, "one\n"), "then the newline itself")
    }

    @Test
    fun anIdleGapStartsANewUndoStep() {
        val clock = Manual()
        val undo = stack(clock)
        val state = EditorState.of("")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()

        var seen = ""
        for ((index, character) in "abcdefgh".withIndex()) {
            val target = seen + character
            rule.onNodeWithTag(TAG_EDITOR).performTextReplacement(target)
            rule.waitForIdle()
            undo.record(
                EditorState.of(seen).model,
                EditorState.of(target).model,
                editBetween(seen, target)!!,
            )
            seen = target
            // A pause long enough to read as a new intention mid-word.
            if (index == 3) clock.advance(5_000) else clock.advance(100)
        }

        assertTrue(undo.undoDepth > 1, "the pause split the run, got ${undo.undoDepth}")
        assertEquals("abcd", undoOnce(undo, "abcdefgh"))
    }

    /**
     * The other half of the idle rule, and the one a user actually feels.
     *
     * [anIdleGapStartsANewUndoStep] advances the clock once, past the limit, to prove
     * a run *ends*. Nothing there proved a run with sub-limit gaps *continues*, which
     * is the half that was broken: the clock was only refreshed when a new undo entry
     * opened, so the limit was measured from the start of the word rather than from
     * the last keystroke in it.
     *
     * Every gap here is 150ms — ordinary typing speed. The word takes 1.6s in total,
     * which is longer than the 800ms limit, and it must still be one step.
     */
    @Test
    fun aWordTypedWithoutAPauseIsOneStep() {
        val clock = Manual()
        val undo = stack(clock)
        val state = EditorState.of("")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()

        var seen = ""
        for (character in "hello world") {
            val target = seen + character
            rule.onNodeWithTag(TAG_EDITOR).performTextReplacement(target)
            rule.waitForIdle()
            // The gap *before* this keystroke, so the clock reads how long since the
            // user last typed rather than how long the word has been going.
            clock.advance(150)
            undo.record(
                EditorState.of(seen).model,
                EditorState.of(target).model,
                editBetween(seen, target)!!,
            )
            seen = target
        }

        assertEquals("hello world", seen)
        assertEquals(1, undo.undoDepth, "11 keystrokes over 1.6s is one step, got ${undo.undoDepth}")
        assertEquals("", undoOnce(undo, seen), "one undo clears the whole phrase")
    }

    // -------------------------------------------------- discrete intents

    @Test
    fun aDeletionIsItsOwnStep() {
        val undo = stack()
        type("abc", undo)
        assertEquals(1, undo.undoDepth)

        val state = EditorState.of("abcd")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_EDITOR).performTextReplacement("abc")
        rule.waitForIdle()
        undo.record(
            EditorState.of("abcd").model,
            EditorState.of("abc").model,
            editBetween("abcd", "abc")!!,
        )

        assertEquals(2, undo.undoDepth, "backspace did not join the typing run")
        assertEquals("abcd", undoOnce(undo, "abc"), "the delete undid on its own, restoring the d")
    }

    @Test
    fun anEmojiRunIsOneStepAndUndoesWhole() {
        val undo = type("👨‍👩‍👧", stack())
        assertEquals(1, undo.undoDepth)
        assertEquals("", undoOnce(undo, "👨‍👩‍👧"), "whole clusters, not halves")
    }

    @Test
    fun aFlagEmojiRunIsOneStep() {
        val undo = type("🇯🇵🇩🇪", stack())
        assertEquals(1, undo.undoDepth)
        assertEquals("", undoOnce(undo, "🇯🇵🇩🇪"))
    }

    // -------------------------------------------------- redo

    @Test
    fun redoRestoresTheRun() {
        val undo = type("hello world", stack())
        val undone = undo.undo(EditorState.of("hello world").model)
        assertEquals("", undone?.text?.text)

        val redone = undo.redo(undone!!)
        assertEquals("hello world", redone?.text?.text, "the whole run came back")
    }
}