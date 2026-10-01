package dev.fude.core

/**
 * An inclusive-exclusive character range in the document.
 *
 * Distinct from [TextRange] because the two are used for different jobs and
 * conflating them is how caret offsets get confused with decoration ranges.
 * A [TextRange] is a selection and can be collapsed; an [InlineRange] is a span
 * of styled content and never is.
 */
public data class InlineRange(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "start must be non-negative, was $start" }
        require(end >= start) { "end ($end) must not precede start ($start)" }
    }

    public val isEmpty: Boolean get() = start == end

    public val length: Int get() = end - start

    public fun contains(offset: Int): Boolean = offset in start..end

    public fun intersects(other: InlineRange): Boolean = start < other.end && other.start < end

    public fun overlaps(other: InlineRange): Boolean = start < other.end && other.start < end

    /** This range mapped through [edit]. */
    public fun mapThrough(edit: Edit): InlineRange {
        val newStart = edit.mapOffset(start, OffsetAffinity.UPSTREAM)
        val newEnd = edit.mapOffset(end, OffsetAffinity.DOWNSTREAM)
        return InlineRange(minOf(newStart, newEnd), maxOf(newStart, newEnd))
    }

    public companion object {
        public val ZERO: InlineRange = InlineRange(0, 0)
    }
}
