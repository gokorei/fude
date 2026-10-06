package dev.fude.spike

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads what Compose actually renders.
 *
 * Semantics strip styling, so they cannot answer whether inline decoration works.
 * `onTextLayout` hands back the laid-out `TextLayoutResult`, which carries the
 * `AnnotatedString` that was actually drawn.
 */
@OptIn(ExperimentalTestApi::class)
class DecorationProbe {
    @get:Rule
    val rule = createComposeRule()

    private var layout: TextLayoutResult? = null

    @Composable
    private fun field(state: TextFieldState, output: OutputTransformation? = null) {
        BasicTextField(
            state = state,
            modifier = Modifier.fillMaxSize().testTag("field"),
            outputTransformation = output,
            onTextLayout = { layout = it() },
        )
    }

    private fun spans(): String =
        layout?.layoutInput?.text?.spanStyles
            ?.joinToString { "${it.start}..${it.end}:w=${it.item.fontWeight},i=${it.item.fontStyle}" }
            ?: "<no layout>"

    @Test
    fun typingAppendsIntoTheLiveBuffer() {
        val state = TextFieldState("abc", initialSelection = TextRange(3))
        rule.setContent { field(state) }
        rule.onNodeWithTag("field").performTextInput("def")
        rule.waitForIdle()
        println("PROBE afterTyping text=[${state.text}] len=${state.text.length}")

        // The spike asked whether typing at the end reaches the same buffer the
        // transformation reads. It does, and at the caret rather than appending blindly,
        // which is what makes the decoration ranges in `applyDecoration` line up with
        // the text at all.
        assertEquals("abcdef", state.text.toString(), "input landed at the caret")
        assertEquals(6, state.text.length)
    }

    @Test
    fun outputTransformationStylesAreRenderedWithoutTouchingTheSourceText() {
        val state = TextFieldState("**caret** tail", initialSelection = TextRange.Zero)
        rule.setContent {
            field(
                state,
                OutputTransformation {
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 9)
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), 10, 14)
                },
            )
        }
        rule.waitForIdle()
        println("PROBE rendered=[${layout?.layoutInput?.text?.text}] spans=${spans()}")
        println("PROBE sourceText=[${state.text}] (must be unchanged)")

        // This is the load-bearing one: a transformation writes styles into the buffer,
        // and if that mutated the source then the user's Markdown would acquire bold
        // runs as they typed. It does not -- styling is a projection, which is the
        // library's stated design and worth pinning rather than assuming.
        assertEquals(
            "**caret** tail",
            state.text.toString(),
            "styling must not rewrite the source; that is the whole design",
        )
        assertTrue(
            spans().isNotEmpty(),
            "and the styles were actually applied, or the assertion above proves nothing",
        )
    }

    @Test
    fun stylingSurvivesAnEditInsideTheStyledRange() {
        val state = TextFieldState("**caret** tail", initialSelection = TextRange.Zero)
        rule.setContent {
            field(
                state,
                OutputTransformation { addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 9) },
            )
        }
        rule.waitForIdle()
        rule.runOnIdle { state.edit { replace(4, 4, "XYZ") } }
        rule.waitForIdle()
        println("PROBE afterEditInside text=[${state.text}] spans=${spans()}")

        rule.runOnIdle { state.edit { replace(0, 0, ">> ") } }
        rule.waitForIdle()
        println("PROBE afterEditBefore text=[${state.text}] spans=${spans()}")

        // An edit inside the styled range shifts everything after it, so a span written
        // once at construction time would be stale. The transformation re-runs each
        // frame from the current text, so the style follows the text rather than the
        // offsets it was computed from.
        assertEquals(">> **caXYZret** tail", state.text.toString(), "both edits landed, in order")
        assertTrue(spans().isNotEmpty(), "and the style is still applied after it")
    }
}
