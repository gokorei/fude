package dev.fude.core

/**
 * An inclusive-exclusive character range in the document.
 *
 * Distinct from [TextRange] because the two are used for different jobs and
 * conflating them is how caret offsets get confused with decoration ranges.
 * A [TextRange] is a selection and can be collapsed; an [InlineRange] is a span
 * of styled content and never is.
 */
data class InlineRange(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "start must be non-negative, was $start" }
        require(end >= start) { "end ($end) must not precede start ($start)" }
    }

    val isEmpty: Boolean get() = start == end

    val length: Int get() = end - start

    /**
     * Whether [offset] lies in `[start, end)`.
     *
     * Half-open, matching this class's KDoc and matching [TextRange]. It was
     * `offset in start..end`, which is a **closed** range in Kotlin and therefore
     * claimed the offset one past the end.
     *
     * That matters as soon as there are two decorations side by side. Given `[3,7)`
     * and `[7,11)`, an inclusive test claims offset 7 for both, so "did the user click
     * this decoration?" has two correct answers and no way to choose between them —
     * and the choice would differ from call site to call site. Half-open makes abutting
     * ranges partition the document exactly once.
     *
     * Two consequences, both intended:
     *
     * - An **empty** range contains nothing at all. That is what makes [ZERO] usable as
     *   a sentinel for "this rendered element has no source of its own"
     *   ([VisualToSourceMapper]); a sentinel that claimed offset 0 would collide with
     *   the real range starting there. That mapper tests emptiness separately and never
     *   calls `contains`, so nothing there changes.
     * - Callers wanting to treat a collapsed range as a hit need an explicit `isEmpty`
     *   guard. A collapsed range is not content, so this is the honest answer rather
     *   than a convenient one.
     */
    fun contains(offset: Int): Boolean = offset in start until end

    /**
     * Whether the two ranges share at least one character.
     *
     * `overlaps` was byte-identical to this and has been deleted: two public names for
     * one implementation costs nothing until a future fix edits one of them and not the
     * other. Only tests called it.
     *
     * Note that an empty range is reported as intersecting anything that spans it,
     * because this is the same formula [TextRange.intersects] uses. Kept deliberately
     * consistent with the sibling type rather than quietly diverging from it.
     */
    fun intersects(other: InlineRange): Boolean = start < other.end && other.start < end

    /** This range mapped through [edit]. */
    fun mapThrough(edit: Edit): InlineRange {
        val newStart = edit.mapOffset(start, OffsetAffinity.UPSTREAM)
        val newEnd = edit.mapOffset(end, OffsetAffinity.DOWNSTREAM)
        return InlineRange(minOf(newStart, newEnd), maxOf(newStart, newEnd))
    }

    companion object {
        val ZERO: InlineRange = InlineRange(0, 0)

        /** A collapsed range at [offset]. */
        fun collapsed(offset: Int): InlineRange = InlineRange(offset, offset)
    }
}
