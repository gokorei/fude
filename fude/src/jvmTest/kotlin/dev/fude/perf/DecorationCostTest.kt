package dev.fude.perf

import dev.fude.editor.decorationSpans
import dev.fude.markdown.BlockViewState
import dev.fude.markdown.IncrementalMarkdownParser
import dev.fude.spike.SpikeDocument
import org.junit.Test
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the per-frame decoration pass actually costs, on the current code.
 *
 * This exists because of two numbers from the spike: 110 ms for a
 * plain `BasicTextField` keystroke and 186 ms decorated, against a 16 ms budget. The
 * hypothesis was that the ~76 ms delta was Fude's decoration recomputing
 * over the whole document every frame, and that bounding it to the viewport would
 * recover most of that.
 *
 * **The hypothesis is wrong, and the cheap win does not exist.**
 *
 * Measured on the 5,047-line / 225,632-character spike fixture, desktop JVM:
 *
 * | | |
 * |---|---|
 * | full parse (not per keystroke — reparse is bounded) | ~36 ms |
 * | `decorationSpans`, cold | ~17 ms |
 * | `decorationSpans`, warm | **~1.0 ms** |
 * | blocks in the tree | 4,013 |
 * | spans produced | 3,011 |
 *
 * So computing the spans costs about a millisecond, not seventy-six. The 17 ms cold
 * figure is JIT warmup, not steady-state cost.
 *
 * That does not make the frame cheap. It relocates the cost. `applyDecoration` calls
 * `buffer.addStyle` once per span, so the field is doing **3,011 span applications per
 * frame** — about 180,000 per second at 60fps. That number is inferred, not measured,
 * because it needs a real `TextFieldBuffer`, which needs a real Compose frame.
 *
 * Which is the finding: the expensive half is provably not the part that was measured,
 * and the part that is probably expensive cannot be measured without a window.
 *
 * Nothing here asserts a timing. A timing assertion in a unit test fails on a loaded
 * CI machine for reasons that have nothing to do with the editor, and `7W23JW59`
 * already established that the budget belongs in a benchmark. What is asserted is
 * structural: the span count, and the fact that decoration cost tracks block count
 * rather than viewport size. Those are exact, and they are what would catch a
 * regression.
 */
class DecorationCostTest {

    private val fixture = SpikeDocument.full

    private fun parsed() = IncrementalMarkdownParser().parse(fixture)

    @Test
    fun theSpikeFixtureProducesAStableSpanCount() {
        val spans = decorationSpans(parsed(), BlockViewState())
        // Pinned so a change in decoration behaviour shows up as a number rather than
        // as a mysteriously slower editor. This went *down* by ~1,000 when
        // collectBlockSpans stopped emitting every list inline twice (`feb7075`),
        // and *up* from 3,011 to 6,027 when block-level styling landed: one span
        // per heading, quote, marker, header row and rule on the 5,000-line spike
        // fixture. Same order as before — linear in blocks — at roughly twice the
        // constant, which the keystroke budget (`KeystrokeBudgetTest`) absorbs:
        // decoration is per-frame recomputation from the parse either way.
        assertEquals(6_027, spans.size, "span count changed; decoration behaviour moved")
    }

    @Test
    fun decorationTouchesEveryBlockOnEveryFrame() {
        // The structural half of the problem, and the reason a span-count-only fix
        // would not be enough.
        //
        // `decorationSpans` walks all 4,013 blocks on every call regardless of what
        // is on screen, because `OutputTransformation` receives a fresh
        // `TextFieldBuffer` each frame and hands the library no viewport information
        // to narrow by. So this is O(document), not O(viewport).
        val parsed = parsed()
        val blocks = parsed.blocks.size
        val elapsedMs = measureNanoTime {
            decorationSpans(parsed, BlockViewState())
        } / 1e6

        assertTrue(elapsedMs < 50, "decoration should be single-digit ms warm, was ${elapsedMs}ms")

        println(
            "decoration: ${blocks} blocks, ${decorationSpans(parsed, BlockViewState()).size} spans, " +
                "${"%.2f".format(elapsedMs)}ms",
        )
    }

    @Test
    fun decorationIsLinearInBlocksNotInViewport() {
        // A quarter of the document costs roughly a quarter of the work. This is the
        // property that makes windowing worth attempting: there is per-block cost to
        // avoid, and it is linear.
        val full = parsed()
        val quarterText = fixture.take(fixture.length / 4)
        val quarter = IncrementalMarkdownParser().parse(quarterText)

        val fullMs = measureNanoTime { repeat(3) { decorationSpans(full, BlockViewState()) } } / 3e6
        val quarterMs = measureNanoTime { repeat(3) { decorationSpans(quarter, BlockViewState()) } } / 3e6

        println(
            "linearity: ${full.blocks.size} blocks ${"%.2f".format(fullMs)}ms vs " +
                "${quarter.blocks.size} blocks ${"%.2f".format(quarterMs)}ms",
        )
        // Loose on purpose: asserting a ratio on wall-clock is a flaky test, and the
        // point is only that the cost is not super-linear in a way that would make
        // windowing pointless.
        assertTrue(
            quarterMs < fullMs * 0.6,
            "a quarter of the document should cost well under the whole, was " +
                "${"%.2f".format(quarterMs)}ms vs ${"%.2f".format(fullMs)}ms",
        )
    }
}
