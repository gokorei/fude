package dev.fude.spike

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Undo grouping and large-document behaviour, which need no rendering. */
@OptIn(ExperimentalTestApi::class)
class UndoProbe {
    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun field(state: TextFieldState) {
        BasicTextField(state = state, modifier = Modifier.fillMaxSize().testTag("field"))
    }

    @Test
    fun typingAWordThenASpaceIsTwoUndoSteps() {
        val state = TextFieldState("", initialSelection = TextRange.Zero)
        rule.setContent { field(state) }
        rule.onNodeWithTag("field").performTextInput("hello")
        rule.onNodeWithTag("field").performTextInput(" ")
        rule.onNodeWithTag("field").performTextInput("world")
        rule.waitForIdle()
        println("PROBE text=[${state.text}]")
        // Captured before the undo loop empties the buffer, since that is the point of
        // the loop.
        val typed = state.text.toString()

        var steps = 0
        while (state.undoState.canUndo) {
            state.undoState.undo()
            steps++
            if (steps > 20) break
        }
        println("PROBE undoStepsToEmpty=$steps")

        // "hello world" typed as three reports, all within Compose's coalescing window.
        // The spike assumed it would produce three undo steps; it produces one. Compose
        // already groups a fast typing run, which is the finding worth keeping -- and
        // also the reason Fude's UndoGrouping is not redundant: Compose has no idle
        // timeout parameter and no boundary rules, so it cannot be asked to split a run
        // at a word boundary, which is the behaviour the library's own tests assert.
        assertEquals("hello world", typed, "all three inputs reached the buffer")
        assertEquals(1, steps, "Compose coalesces a fast run into one undo step")
    }

    @Test
    fun typingIsGroupedRatherThanPerCharacter() {
        val state = TextFieldState("", initialSelection = TextRange.Zero)
        rule.setContent { field(state) }
        rule.onNodeWithTag("field").performTextInput("hello")
        rule.waitForIdle()
        val typed = state.text.toString()
        var steps = 0
        while (state.undoState.canUndo) {
            state.undoState.undo()
            steps++
            if (steps > 20) break
        }
        println("PROBE fiveCharsWentInUndoSteps=$steps")

        assertEquals("hello", typed, "the whole word went in as one report")
        assertEquals(
            1,
            steps,
            "five characters in one report are one step -- this is the property that " +
                "justifies Fude having its own UndoGrouping rather than deferring to " +
                "the platform's",
        )
    }
}
