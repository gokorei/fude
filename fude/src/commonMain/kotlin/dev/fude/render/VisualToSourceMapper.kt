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
 *
 * **Host-facing, and currently for a view Fude does not have.**
 *
 * `MarkdownEditor` decorates in place over the source buffer, so rendered output
 * and source text are the same string here and the mapping is the identity. It
 * becomes necessary the moment there is a read-only rendered pane — a preview
 * beside the editor, a read-only mode — where a click or a selection arrives in
 * rendered coordinates and has to be answered in source ones.
 *
 * That is the honest reason it is not called, and it is a reason to keep it
 * rather than to delete it: it is the seam a second view will need, and its
 * contract is now written down so whoever builds that view does not have to
 * re-derive it. What is *not* honest is counting its six tests as coverage of
 * anything the editor does today.
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
