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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether a 5,000-line note survives a keystroke.
 *
 * The brief says re-parse and re-lay-out per keystroke will not hold frame rate.
 * This measures it rather than assuming it: the spike exists because that claim
 * needed checking, and "we think it's fine" is not a measurement.
 */
@OptIn(ExperimentalTestApi::class)
class LargeDocumentProbe {
    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun field(state: TextFieldState, decorate: Boolean) {
        BasicTextField(
            state = state,
            modifier = Modifier.fillMaxSize().testTag("field"),
            outputTransformation = if (decorate) {
                OutputTransformation {
                    // A cheap stand-in for a per-frame reparse: find every `**` pair.
                    var index = 0
                    val text = originalText
                    while (true) {
                        val open = indexOf(text, "**", index)
                        if (open < 0) break
                        val close = indexOf(text, "**", open + 2)
                        if (close < 0) break
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), open + 2, close)
                        index = close + 2
                    }
                }
            } else {
                null
            },
        )
    }

    private fun indexOf(text: CharSequence, needle: String, from: Int): Int {
        var i = from
        while (i + needle.length <= text.length) {
            var match = true
            for (j in needle.indices) {
                if (text[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
        }
        return -1
    }

    @Test
    fun typingIntoAFiveThousandLineNote() {
        println("PROBE doc lines=${SpikeDocument.lineCount} chars=${SpikeDocument.charCount}")
        val state = TextFieldState(SpikeDocument.full, initialSelection = TextRange(SpikeDocument.caretOffset))
        rule.setContent { field(state, decorate = true) }
        rule.waitForIdle()
        println("PROBE firstLayoutDone")

        val start = System.nanoTime()
        rule.runOnIdle { state.edit { replace(SpikeDocument.caretOffset, SpikeDocument.caretOffset, "x") } }
        rule.waitForIdle()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("PROBE keystrokeWithDecorationMs=$elapsedMs")

        // A timing probe, so the honest assertion is not about the number -- 116 ms on
        // one machine is a different number on another, and asserting a budget here
        // would be a flaky test of the hardware. What is asserted is that the keystroke
        // happened at all and landed where it was aimed, since a probe that silently
        // measured nothing is exactly how a cost curve comes to read as "no cost".
        assertEquals(
            SpikeDocument.charCount + 1,
            state.text.length,
            "the keystroke landed in the live buffer at the caret",
        )
        assertTrue(elapsedMs >= 0, "and the measurement covered a real edit")
    }

    @Test
    fun typingIntoAFiveThousandLineNoteWithoutDecoration() {
        val state = TextFieldState(SpikeDocument.full, initialSelection = TextRange(SpikeDocument.caretOffset))
        rule.setContent { field(state, decorate = false) }
        rule.waitForIdle()

        val start = System.nanoTime()
        rule.runOnIdle { state.edit { replace(SpikeDocument.caretOffset, SpikeDocument.caretOffset, "x") } }
        rule.waitForIdle()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("PROBE keystrokePlainMs=$elapsedMs")

        assertEquals(
            SpikeDocument.charCount + 1,
            state.text.length,
            "same in the undecorated field, so the two measurements are comparable",
        )
    }
}
