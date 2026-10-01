package dev.fude.render

import dev.fude.core.InlineRange

/**
 * Maps a position in rendered output back to the source text it came from.
 *
 * The problem this exists to solve: a link renders its label rather than its
 * target, and a list item renders a marker the user never typed. A naive
 * one-to-one offset mapping selects the wrong text in both cases, and the error
 * is invisible until someone tries to select something.
 *
 * So visual runs carry the source range they came from, and this is the inverse:
 * given a range of rendered output, which source offsets does it correspond to.
 */
public class VisualToSourceMapper(
    /** Rendered runs in visual order, each with the source it came from. */
    private val runs: List<VisualRun>,
) {
    /**
     * One run of rendered output.
     *
     * [source] is [InlineRange.ZERO] for a rendered element with no source of its
     * own, such as a list bullet. Such a run contributes nothing to a selection.
     */
    public data class VisualRun(val source: InlineRange)

    /** The source range a single visual offset falls in. */
    public fun sourceAt(visualOffset: Int): InlineRange {
        if (runs.isEmpty()) return InlineRange.ZERO
        val index = visualOffset.coerceIn(0, runs.size)
        return runs.getOrNull(index)?.source ?: runs.last().source
    }

    /** The visual run range a source range renders as. */
    public fun visualOf(source: InlineRange): IntRange {
        val indices = runs.withIndex().filter { (_, run) ->
            run.source.start < source.end && source.start < run.source.end
        }
        if (indices.isEmpty()) return IntRange.EMPTY
        return indices.first().index..indices.last().index
    }

    /**
     * The source text a visual range corresponds to.
     *
     * Runs with no source of their own are skipped rather than contributing an
     * empty string, so selecting "bullet plus item" yields the item's text and
     * not the marker the user never typed.
     */
    public fun sourceTextFor(visualRange: IntRange, text: CharSequence): String {
        val start = visualRange.first
        val end = if (visualRange.isEmpty()) start + 1 else visualRange.last + 1

        val mapped = mutableListOf<InlineRange>()
        for (i in start until end) {
            val run = runs.getOrNull(i)?.source ?: continue
            if (run.isEmpty) continue
            mapped += run
        }
        if (mapped.isEmpty()) return ""

        val from = mapped.minOf { it.start }.coerceIn(0, text.length)
        val to = mapped.maxOf { it.end }.coerceIn(0, text.length)
        return text.subSequence(from, to).toString()
    }
}
