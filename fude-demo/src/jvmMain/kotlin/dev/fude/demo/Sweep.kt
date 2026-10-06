package dev.fude.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fude.core.TextRange
import dev.fude.editor.EditorConfig
import dev.fude.editor.EditorState
import dev.fude.editor.MarkdownEditor

/**
 * Measures one keystroke configuration in a real on-screen window, then exits.
 *
 * Every other keystroke figure in this repository came from a headless,
 * software-rendered Compose harness. That is enough to show the cost is real and
 * linear and not enough to choose an architecture on: `runOnIdle` plus
 * `waitForIdle` bundles composition, layout, draw *and* semantics into one number,
 * so no phase can be held responsible, and Skia rasterises on the CPU where a real
 * window uses the GPU.
 *
 * **One configuration per process.** An earlier version swept all of them from
 * inside one window and could not sequence them: mutating the variant from a
 * coroutine inside the `LaunchedEffect` body changed a key the effect depended on
 * and restarted it. Driving the sweep from a shell loop instead means there is no
 * sequencing state to get wrong, every run is short, and a hung run costs one
 * measurement rather than the whole sweep.
 *
 * Three configurations, because a single number cannot say which phase is
 * responsible:
 *
 * - [Variant.EDITOR] — the real `MarkdownEditor`. The number a user experiences.
 * - [Variant.PLAIN] — bare `BasicTextField`, same text, no decoration. EDITOR −
 *   PLAIN is what decoration costs.
 * - [Variant.STATIC] — static text, no field. Window and frame overhead, subtracted
 *   from the other two so harness cost is not reported as editor cost.
 *
 * What this **cannot** separate is the intrinsic-shaping pass from the
 * constrained-layout pass inside Skia's `MultiParagraph.layoutText`. That is
 * Skiko-internal and would need a fork to instrument, so the assumed ~50/50 split
 * stays unverified — which is more useful to record than a number nobody measured.
 */
enum class Variant(val label: String) {
    EDITOR("editor"),
    PLAIN("plain"),
    STATIC("static");

    companion object {
        fun parse(raw: String?): Variant =
            entries.firstOrNull { it.label.equals(raw?.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "FUDE_SWEEP_VARIANT must be one of ${entries.map { it.label }}, was '$raw'",
                )
    }
}

/**
 * A generated document of at least [lines] lines, in the shape a real note has.
 *
 * Deliberately varied — headings, bold, emphasis, code, links, three-item lists, a
 * fenced block — because a wall of identical paragraphs is the fixture shape that
 * made the old 5,047-line figure meaningless in the first place.
 */
internal fun generatedDocument(lines: Int): String {
    val body = StringBuilder("# A generated note\n\n")
    var written = 2
    var section = 1
    while (written < lines) {
        body.append(
            """
            ## Section $section

            A paragraph in section $section with **bold text**, *emphasis*, `code`,
            and a [link](https://example.com/section/$section) so the renderer has
            real inline work rather than plain characters.

            - A list item for section $section
            - Another item with **emphasis**
            - A third item

            ```kotlin
            fun section$section() = $section
            ```

            """.trimIndent(),
        )
        written += 15
        section++
    }
    return body.toString()
}

private const val WARMUP_SAMPLES = 3
private const val MEASURED_SAMPLES = 7

/**
 * Measures and reports, then calls [onFinished] so the process exits.
 *
 * Each sample applies an edit and then waits for **two** frames. The first frame's
 * composition is where the work happens; the second confirms nothing further was
 * scheduled, so the figure is "settled" rather than "composition returned". Timing
 * one frame would under-report by however long the trailing layout pass takes.
 */
@Composable
internal fun SweepHarness(
    lines: Int,
    variant: Variant,
    onFinished: () -> Unit,
) {
    val text = remember(lines) { generatedDocument(lines) }
    val state = remember(lines) { EditorState.of(text, caret = text.length / 2) }
    val fieldState: TextFieldState = rememberTextFieldState(text)

    LaunchedEffect(lines, variant) {
        val caret = text.length / 2
        val samples = mutableListOf<Long>()

        repeat(WARMUP_SAMPLES + MEASURED_SAMPLES) { i ->
            // A different offset each time, so this is not measuring one cached
            // layout path repeatedly.
            val offset = caret + (i % 7)
            val next = text.substring(0, offset) + "x" + text.substring(offset)
            val started = System.nanoTime()

            when (variant) {
                Variant.EDITOR -> {
                    state.applyEdit(next, TextRange(offset, offset))
                    withFrameNanos { }
                    withFrameNanos { }
                }
                Variant.PLAIN -> {
                    fieldState.edit { replace(0, length, next) }
                    withFrameNanos { }
                    withFrameNanos { }
                }
                // The floor the other two are measured from. It waits the same two
                // frames they do — a `delay` here would measure something else
                // entirely, and the difference would then be subtracted from the
                // editor's cost as if it were harness overhead.
                Variant.STATIC -> {
                    withFrameNanos { }
                    withFrameNanos { }
                }
            }

            val elapsed = (System.nanoTime() - started) / 1_000_000
            // The first runs are JIT warm-up and are discarded, not reported.
            if (i >= WARMUP_SAMPLES) samples += elapsed
        }

        val sorted = samples.sorted()
        println(
            "SWEEP variant=${variant.label} lines=$lines chars=${text.length} " +
                "median=${sorted[sorted.size / 2]}ms worst=${sorted.last()}ms " +
                "samples=${samples.joinToString(",")}",
        )
        onFinished()
    }

    when (variant) {
        Variant.EDITOR -> MarkdownEditor(
            state = state,
            modifier = Modifier.fillMaxSize().padding(8.dp),
            config = EditorConfig(textStyle = TextStyle(fontSize = 15.sp)),
            syntaxExtensions = listOf(MentionSyntax(), CalloutSyntax()),
        )
        Variant.PLAIN -> BasicTextField(
            state = fieldState,
            modifier = Modifier.fillMaxSize().padding(8.dp),
            textStyle = TextStyle(fontSize = 15.sp),
        )
        Variant.STATIC -> BasicText(text, style = TextStyle(fontSize = 15.sp))
    }
}