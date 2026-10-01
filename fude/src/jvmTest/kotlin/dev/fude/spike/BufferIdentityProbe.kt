package dev.fude.spike

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import org.junit.Rule
import org.junit.Test

/**
 * Establishes why a tracked range cannot be held across frames: is the buffer
 * handed to `OutputTransformation` the same buffer on the next frame?
 */
@OptIn(ExperimentalTestApi::class)
class BufferIdentityProbe {
    @get:Rule
    val rule = createComposeRule()

    private val seen = mutableListOf<TextFieldBuffer>()

    @Composable
    private fun field(state: TextFieldState) {
        BasicTextField(
            state = state,
            modifier = Modifier.fillMaxSize().testTag("field"),
            outputTransformation = OutputTransformation {
                seen += this
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 5)
            },
        )
    }

    @Test
    fun eachFrameReceivesADifferentBuffer() {
        val state = TextFieldState("**caret** tail", initialSelection = TextRange.Zero)
        rule.setContent { field(state) }
        rule.waitForIdle()
        val afterFirst = seen.size
        rule.runOnIdle { state.edit { replace(0, 0, "x") } }
        rule.waitForIdle()
        println("PROBE invocations=$afterFirst then ${seen.size - afterFirst}")
        println("PROBE distinctBuffers=${seen.map { System.identityHashCode(it) }.distinct().size} of ${seen.size}")
    }
}
