package dev.fude.editor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeysDown
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Undo and redo driven by the keyboard, through real frames.
 *
 * The other undo tests assert the grouping *rules* against the reports a field
 * produces. This one asserts that ⌘Z is wired to them at all — a rule that works and
 * is never reached is not a feature.
 *
 * Each keystroke is a separate `performTextInput` so the platform reports eleven
 * changes rather than one.
 */
@OptIn(ExperimentalTestApi::class)
class UndoThroughTheKeyboardTest {
    @get:Rule
    val rule = createComposeRule()

    private fun typeOneCharacterAtATime(text: String) {
        for (character in text) {
            rule.onNodeWithTag(TAG_EDITOR).performTextInput(character.toString())
            rule.waitForIdle()
        }
    }

    private fun command(key: Key) {
        rule.onNodeWithTag(TAG_EDITOR).performKeyInput {
            withKeysDown(listOf(Key.MetaLeft, key)) { }
        }
        rule.waitForIdle()
    }

    @Test
    fun undoRemovesAWholeTypedWordInOnePress() {
        val state = EditorState.of("")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()

        typeOneCharacterAtATime("hello world")
        assertEquals("hello world", state.text)

        command(Key.Z)
        assertEquals("", state.text, "one press cleared all eleven characters")
    }

    @Test
    fun aSecondUndoThenDoesNothingRatherThanCorruptingTheDocument() {
        val state = EditorState.of("")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()
        typeOneCharacterAtATime("hello")

        command(Key.Z)
        assertEquals("", state.text)

        // History is empty now. The key must be passed through rather than consumed,
        // so the platform can still act on it — and either way the text is untouched.
        command(Key.Z)
        assertEquals("", state.text, "still empty, and intact")
    }

    @Test
    fun redoBringsTheRunBack() {
        val state = EditorState.of("")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()
        typeOneCharacterAtATime("hello world")

        command(Key.Z)
        assertEquals("", state.text)

        command(Key.Z) // shift is not toggled in this helper, so this is still undo
        assertEquals("", state.text)

        rule.onNodeWithTag(TAG_EDITOR).performKeyInput {
            withKeysDown(listOf(Key.MetaLeft, Key.ShiftLeft, Key.Z)) { }
        }
        rule.waitForIdle()
        assertEquals("hello world", state.text, "shift-⌘Z redid the run")
    }

    @Test
    fun undoDoesNotDisturbUnrelatedDocumentText() {
        val state = EditorState.of("keep me\n\n")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()

        typeOneCharacterAtATime("typed")
        assertEquals("keep me\n\ntyped", state.text)

        command(Key.Z)
        assertEquals("keep me\n\n", state.text, "only the typing was undone")
    }

    /**
     * 7A7KQZPF: undo fires on KeyDown with every press consumed, including
     * auto-repeat. Typing "one\ntwo" is three undo steps (word, newline, word),
     * so two presses must undo exactly two steps — one step per press, neither
     * zero (KeyUp-only handling would still pass a single-press test, but the
     * release phase is no longer what fires) nor two-at-once (which is what
     * handling both phases would do).
     */
    @Test
    fun eachUndoPressUndoesExactlyOneStep() {
        val state = EditorState.of("")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()
        typeOneCharacterAtATime("one\ntwo")
        assertEquals("one\ntwo", state.text)

        command(Key.Z)
        assertEquals("one\n", state.text, "first press undid only the word after the newline")

        command(Key.Z)
        assertEquals("one", state.text, "second press undid the newline itself")
    }

    /**
     * 9CZQBV9P: the gesture path aliased `before` to the `EditorState` shell, so
     * after `applyEdit` both sides of `record` read the new model and history
     * stayed empty. This asserts the composable's own wiring, not manual record.
     */
    @Test
    fun gesturePathRecordsIntoInjectedHistory() {
        val state = EditorState.of("")
        val undo = dev.fude.core.UndoStack()
        rule.setContent { MarkdownEditor(state = state, undo = undo) }
        rule.waitForIdle()

        typeOneCharacterAtATime("hello")
        assertEquals("hello", state.text)
        assertEquals(1, undo.undoDepth, "five keystrokes through the composable are one step")

        command(Key.Z)
        assertEquals("", state.text, "Cmd+Z clears through the composable's own history")
    }
}
