package dev.fude.spike

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.ExpandPolicy
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TrackedRange
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import org.junit.Rule
import org.junit.Test

/**
 * The spike's central question: can decoration stay attached to the text it
 * decorates while the user edits around it?
 *
 * `OutputTransformation` runs on every displayed frame, so a range literal passed
 * to `addStyle` is recomputed from scratch every time and is only ever as correct
 * as the caller's own reparse. The `TrackedRange` overload is the variant that
 * claims to survive edits, so this probe holds the handle across frames and lets
 * Compose maintain it, which is the only way to find out whether it works.
 */
@OptIn(ExperimentalTestApi::class)
class TrackedRangeProbe {
    @get:Rule
    val rule = createComposeRule()

    private var layout: TextLayoutResult? = null

    @Composable
    private fun field(state: TextFieldState, output: OutputTransformation) {
        BasicTextField(
            state = state,
            modifier = Modifier.fillMaxSize().testTag("field"),
            outputTransformation = output,
            onTextLayout = { layout = it() },
        )
    }

    private fun spans(): String =
        layout?.layoutInput?.text?.spanStyles
            ?.joinToString { "${it.start}..${it.end}:w=${it.item.fontWeight}" }
            ?: "<none>"

    @Test
    fun trackedRangeSurvivesEditsAroundIt() {
        val state = TextFieldState("**caret** tail", initialSelection = TextRange.Zero)
        var handle: TrackedRange<SpanStyle>? = null

        rule.setContent {
            field(
                state,
                OutputTransformation {
                    if (handle == null) {
                        handle = this.addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold),
                            TextRange(0, 9),
                            ExpandPolicy.AtBoth,
                        )
                    }
                },
            )
        }
        rule.waitForIdle()
        println("PROBE tracked.initial      text=[${state.text}] spans=${spans()}")

        rule.runOnIdle { state.edit { replace(4, 4, "XYZ") } }
        rule.waitForIdle()
        println("PROBE tracked.insertInside text=[${state.text}] spans=${spans()}")

        rule.runOnIdle { state.edit { replace(0, 0, ">> ") } }
        rule.waitForIdle()
        println("PROBE tracked.insertBefore text=[${state.text}] spans=${spans()}")

        rule.runOnIdle { state.edit { replace(2, 15, "") } }
        rule.waitForIdle()
        println("PROBE tracked.deleteInside text=[${state.text}] spans=${spans()}")
    }
}
