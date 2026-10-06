package dev.fude.core

/**
 * A change to the document, as a value.
 *
 * Every change in the editor is one of these, and undo history is a list of
 * them. That is the point: a second mutation path is how undo silently rots, so
 * there is exactly one shape of change and exactly one way to apply it.
 *
 * Each edit remembers both what it removed and what it put there, so its inverse
 * is derivable without a document snapshot. Snapshots would mean an O(n) copy of
 * a 5,000-line note on every keystroke.
 */
public sealed interface Edit {

    /** The source range this edit replaced. Collapsed for a pure insertion. */
    val affectedRange: TextRange

    /** The text this edit put in place of [affectedRange]. */
    val replacement: String

    /** Whether this edit would leave [buffer] unchanged. */
    fun isNoOp(buffer: TextBuffer): Boolean

    /** The edit that reverses this one. */
    fun inverse(): Edit

    /** This edit applied to [text]. */
    fun applyTo(text: String): String

    /**
     * Where a caret at [offset] ends up.
     *
     * [affinity] decides which side of newly inserted text the caret prefers, and
     * is the difference between a caret that follows what you type and one that
     * gets left behind it.
     */
    fun mapOffset(offset: Int, affinity: OffsetAffinity = OffsetAffinity.DOWNSTREAM): Int

    /** A short description, for diagnostics and tests. */
    val describe: String
}

/**
 * Which side of an edit a position sticks to when the two collapse.
 *
 * Typing "x" at offset 5 with the caret already at 5: downstream puts the caret
 * at 6, after the character just typed.
 */
enum class OffsetAffinity {
    /** Prefer the position after inserted text. */
    DOWNSTREAM,

    /** Prefer the position before inserted text. */
    UPSTREAM,
}

/** Inserts text at an offset, replacing nothing. */
data class Insert(
    val offset: Int,
    val text: String,
) : Edit {
    init {
        require(offset >= 0) { "offset must be non-negative, was $offset" }
    }

    override val affectedRange: TextRange = TextRange(offset, offset)

    override val replacement: String = text

    override fun isNoOp(buffer: TextBuffer): Boolean = text.isEmpty()

    override fun inverse(): Edit = Replace(
        range = TextRange(offset, offset + text.length),
        removed = text,
        inserted = "",
    )

    override fun applyTo(text: String): String {
        val at = offset.coerceIn(0, text.length)
        return text.substring(0, at) + this.text + text.substring(at)
    }

    override fun mapOffset(offset: Int, affinity: OffsetAffinity): Int = when {
        offset < this.offset -> offset
        offset == this.offset && affinity == OffsetAffinity.UPSTREAM -> this.offset
        // Downstream at the insertion point, or anywhere after it.
        else -> offset + text.length
    }

    override val describe: String get() = "insert($offset, ${text.length} chars)"
}

/** Removes a range, remembering what was there so the edit can be undone. */
data class Delete(
    override val affectedRange: TextRange,
    val removed: String,
) : Edit {
    override val replacement: String get() = ""

    override fun isNoOp(buffer: TextBuffer): Boolean = affectedRange.isCollapsed

    override fun inverse(): Edit =
        if (removed.isEmpty()) Delete(affectedRange, "") else Insert(affectedRange.start, removed)

    override fun applyTo(text: String): String {
        val start = affectedRange.start.coerceIn(0, text.length)
        val end = affectedRange.end.coerceIn(start, text.length)
        return text.substring(0, start) + text.substring(end)
    }

    override fun mapOffset(offset: Int, affinity: OffsetAffinity): Int = when {
        offset <= affectedRange.start -> offset
        offset >= affectedRange.end -> offset - (affectedRange.end - affectedRange.start)
        // Inside the deleted range the caret cannot stay where it was, because
        // there is nothing there any more. It collapses to the deletion start.
        else -> affectedRange.start
    }

    override val describe: String get() = "delete(${affectedRange.start}..${affectedRange.end})"
}

/**
 * Replaces a range with new text.
 *
 * Carries the removed text explicitly so [inverse] needs no access to a buffer.
 * Construct it through [replace], which fills that in from the buffer.
 */
data class Replace(
    val range: TextRange,
    val removed: String,
    val inserted: String,
) : Edit {
    override val affectedRange: TextRange = range

    override val replacement: String = inserted

    override fun isNoOp(buffer: TextBuffer): Boolean = removed == inserted

    /**
     * The inverse reuses the start offset but *not* the end offset.
     *
     * This is the bug that made the first version of this fail its round-trip
     * test: replacing 5 characters with 7 leaves the result 2 characters longer,
     * so the old end offset now points 2 characters past the text this edit
     * actually wrote. Undoing then deletes the tail of the replacement and
     * leaves the head of the document corrupted.
     */
    override fun inverse(): Edit = Replace(
        range = TextRange(range.start, range.start + inserted.length),
        removed = inserted,
        inserted = removed,
    )

    override fun applyTo(text: String): String {
        val start = range.start.coerceIn(0, text.length)
        val end = range.end.coerceIn(start, text.length)
        return text.substring(0, start) + inserted + text.substring(end)
    }

    override fun mapOffset(offset: Int, affinity: OffsetAffinity): Int {
        val delta = inserted.length - removed.length
        return when {
            offset <= range.start -> offset
            offset >= range.end -> offset + delta
            // Inside the replaced range: go to whichever end the affinity prefers.
            else -> if (affinity == OffsetAffinity.DOWNSTREAM) range.start + inserted.length else range.start
        }
    }

    override val describe: String
        get() = "replace(${range.start}..${range.end}, ${inserted.length} chars)"
}

/**
 * Builds the right [Edit] for replacing [range] with [text], capturing the
 * removed text from [buffer] so the result is independently invertible.
 *
 * Every mutation in the editor goes through here. Specialising to
 * [Insert]/[Delete] where the general form would do keeps descriptions and
 * equality meaningful — "did the document change?" should be answerable.
 */
fun replace(buffer: TextBuffer, range: TextRange, text: String): Edit {
    val start = range.start.coerceIn(0, buffer.length)
    val end = range.end.coerceIn(start.coerceAtMost(buffer.length), buffer.length)
    val clamped = TextRange(start, end)
    val removed = buffer.substring(start, end)
    return when {
        clamped.isCollapsed && text.isEmpty() -> Delete(clamped, "")
        clamped.isCollapsed -> Insert(start, text)
        text.isEmpty() -> Delete(clamped, removed)
        else -> Replace(clamped, removed, text)
    }
}

/** Builds the [Edit] that replaces the current [selection]. */
fun replaceSelection(buffer: TextBuffer, selection: TextRange, text: String): Edit =
    replace(buffer, selection, text)

/**
 * Applies [edit] to this range, returning where the range ends up.
 *
 * A collapsed caret moves as one unit, downstream, so that typing puts it after
 * what was typed rather than stranding it before. A real range keeps its edges
 * apart — the start sticks before text inserted inside it, the end after — so
 * selecting across an insertion keeps the selection covering what it covered.
 */
fun TextRange.mapThrough(edit: Edit): TextRange {
    if (isCollapsed) {
        val moved = edit.mapOffset(start, OffsetAffinity.DOWNSTREAM)
        return TextRange(moved, moved)
    }
    val start = edit.mapOffset(start, OffsetAffinity.UPSTREAM)
    val end = edit.mapOffset(end, OffsetAffinity.DOWNSTREAM)
    return TextRange(minOf(start, end), maxOf(start, end))
}
