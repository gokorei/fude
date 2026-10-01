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

        var steps = 0
        while (state.undoState.canUndo) {
            state.undoState.undo()
            steps++
            if (steps > 20) break
        }
        println("PROBE undoStepsToEmpty=$steps")
    }

    @Test
    fun typingIsGroupedRatherThanPerCharacter() {
        val state = TextFieldState("", initialSelection = TextRange.Zero)
        rule.setContent { field(state) }
        rule.onNodeWithTag("field").performTextInput("hello")
        rule.waitForIdle()
        var steps = 0
        while (state.undoState.canUndo) {
            state.undoState.undo()
            steps++
            if (steps > 20) break
        }
        println("PROBE fiveCharsWentInUndoSteps=$steps")
    }
}
