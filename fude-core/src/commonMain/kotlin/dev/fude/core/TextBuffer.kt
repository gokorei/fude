package dev.fude.core

/**
 * The document text, as an immutable value.
 *
 * Why a value and not a mutable buffer: undo history, equality checks, and the
 * "did this actually change?" question a host asks before saving all become
 * trivial when the text is a value. A mutable buffer leaks into the public API
 * and turns every one of those into a bug.
 *
 * A 5,000-line note is a `String`, so `copy()` on every edit is an O(n) copy.
 * That is acceptable here because the platform's own text buffer already holds
 * the document and this type is the *model*, not the storage: it exists to be
 * compared and reasoned about, and it is not on the keystroke path. The
 * keystroke path uses `Edit` values, which are small.
 */
class TextBuffer private constructor(private val value: String) {

    val length: Int get() = value.length

    val text: String get() = value

    override fun toString(): String = "TextBuffer(${value.length} chars)"

    override fun equals(other: Any?): Boolean = other is TextBuffer && other.value == value

    override fun hashCode(): Int = value.hashCode()

    fun substring(start: Int, end: Int): String {
        require(start in 0..length) { "start $start is outside [0, $length]" }
        require(end in start..length) { "end $end is outside [$start, $length]" }
        return value.substring(start, end)
    }

    fun charAt(offset: Int): Char {
        require(offset in 0 until length) { "offset $offset is outside [0, $length)" }
        return value[offset]
    }

    fun isBlank(): Boolean = value.isBlank()

    fun withEdit(edit: Edit): TextBuffer =
        if (edit.isNoOp(this)) this else TextBuffer(edit.applyTo(value))

    companion object {
        val EMPTY: TextBuffer = TextBuffer("")

        fun of(text: String): TextBuffer = if (text.isEmpty()) EMPTY else TextBuffer(text)
    }
}

/**
 * An offset range within a document.
 *
 * Inclusive start, exclusive end, so `start == end` is the collapsed caret.
 * Modelled explicitly rather than as a pair of ints because the collapsed case
 * is the one everything else hangs off.
 */
data class TextRange(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "start must be non-negative, was $start" }
        require(end >= start) { "end ($end) must not precede start ($start)" }
    }

    val isCollapsed: Boolean get() = start == end

    val length: Int get() = end - start

    /** Whether this range fully contains [other]. */
    fun contains(other: TextRange): Boolean =
        other.start >= start && other.end <= end

    fun intersects(other: TextRange): Boolean =
        start < other.end && other.start < end

    /** This range shifted right by [delta], clamped to [limit]. */
    fun shifted(delta: Int, limit: Int): TextRange {
        val newStart = (start + delta).coerceIn(0, limit)
        val newEnd = (end + delta).coerceIn(0, limit)
        return TextRange(minOf(newStart, newEnd), maxOf(newStart, newEnd))
    }

    companion object {
        val ZERO: TextRange = TextRange(0, 0)

        /** A collapsed caret at [offset]. */
        fun collapsed(offset: Int): TextRange = TextRange(offset, offset)
    }
}
