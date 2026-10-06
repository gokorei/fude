package dev.fude.perf

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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import dev.fude.spike.SpikeDocument
import org.junit.Rule
import org.junit.Test

/**
 * Keystroke-to-frame on a 5,000-line document, against a stated budget.
 *
 * The ticket asks for this in a benchmark rather than a unit test, and the
 * distinction is the point: a unit test that asserts a timing is a flaky test. So
 * this *reports* and asserts only that the measurement completed, and the numbers
 * are printed for a human to judge. The budget is stated in the message rather
 * than enforced, because enforcing it here would fail on a loaded CI machine for
 * reasons that have nothing to do with the editor.
 *
 * **What this is not.** This does not measure `MarkdownEditor`. It builds a bare
 * `BasicTextField` with an `OutputTransformation` that scans for `**` pairs — a cheap
 * stand-in for a reparse — so it captures the cost of *a decoration pass over a large
 * document*, not the cost of parsing, block-ranging, view-state consultation and
 * style collection that the real editor does per frame.
 *
 * That distinction used to be invisible: the README presented its output as the
 * editor's measured cost, so a reader who ran `./gradlew build` got different figures
 * from the table above it with no way to tell why. The README now names this as the
 * stand-in it is and points at `scripts/keystroke_sweep.sh`, which drives the real
 * editor in a real window and is the source of the published numbers. This test
 * remains useful for what it is: a same-machine A/B between decorated and undecorated,
 * where the parse cost is deliberately held constant.
 */
@OptIn(ExperimentalTestApi::class)
class KeystrokeBudgetTest {
    @get:Rule
    val rule = createComposeRule()

    /** One frame at 60fps. */
    private val frameBudgetMillis = 16

    @Composable
    private fun field(state: TextFieldState, decorate: Boolean) {
        BasicTextField(
            state = state,
            modifier = Modifier.fillMaxSize().testTag("field"),
            outputTransformation = if (decorate) {
                OutputTransformation {
                    // A cheap stand-in for a reparse: find every `**` pair.
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

    private fun measure(label: String, decorate: Boolean, caret: Int) {
        val state = TextFieldState(SpikeDocument.full, initialSelection = TextRange(caret))
        rule.setContent { field(state, decorate) }
        rule.waitForIdle()

        val samples = mutableListOf<Long>()
        repeat(5) { iteration ->
            val offset = caret + iteration
            val start = System.nanoTime()
            rule.runOnIdle { state.edit { replace(offset, offset, "x") } }
            rule.waitForIdle()
            samples += (System.nanoTime() - start) / 1_000_000
        }

        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val worst = sorted.last()
        println(
            "PERF $label median=${median}ms worst=${worst}ms samples=$samples " +
                "(frame budget ${frameBudgetMillis}ms, doc ${SpikeDocument.lineCount} lines)",
        )
    }

    @Test
    fun keystrokeCostOnAFiveThousandLineNote() {
        val caret = SpikeDocument.caretOffset
        measure("plain", decorate = false, caret = caret)
        measure("decorated", decorate = true, caret = caret)
        // The measurement itself is the assertion: if this did not complete, the
        // numbers above mean nothing.
    }
}
