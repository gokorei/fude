package dev.fude.core

/**
 * Where the caret is on screen, as distinct from where it is in the text.
 *
 * The distinction is the whole reason this file exists. A document offset says
 * nothing about which line the caret appears on once lines wrap, and nothing at
 * all about which *visual* line when a paragraph wraps across three of them.
 * Getting that wrong is what makes live-preview editors feel broken: the caret
 * is in the right place logically and the wrong place on screen.
 */
public data class TextPosition(
    val offset: Int,
    val line: Int,
    val column: Int,
    val visualLine: Int,
    val visualColumn: Int,
) {
    init {
        require(offset >= 0) { "offset must be non-negative, was $offset" }
        require(line >= 0) { "line must be non-negative, was $line" }
        require(column >= 0) { "column must be non-negative, was $column" }
        require(visualLine >= 0) { "visualLine must be non-negative, was $visualLine" }
        require(visualColumn >= 0) { "visualColumn must be non-negative, was $visualColumn" }
    }
}

/** A rectangle in a laid-out document, in pixels relative to the text origin. */
public data class VisualRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    public val width: Float get() = right - left
    public val height: Float get() = bottom - top
}

/**
 * Line start offsets for a document.
 *
 * Computed once per text version and then queried, because a naive scan from the
 * start of the document on every keystroke is O(n) per query and this runs per
 * frame.
 */
public class LineIndex private constructor(private val starts: IntArray, val length: Int) {

    public val lineCount: Int get() = starts.size

    /** The offset where [line] begins. */
    public fun lineStart(line: Int): Int {
        val index = line.coerceIn(0, starts.size - 1)
        return starts[index]
    }

    /**
     * The offset where [line] ends.
     *
     * By default the terminator is excluded, so the end of "abc\n" is 3. With
     * [endInclusive] the terminator is included, which is what a selection grown
     * to end-of-line wants.
     */
    public fun lineEnd(line: Int, endInclusive: Boolean = false): Int {
        val index = line.coerceIn(0, starts.size - 1)
        val isLast = index + 1 >= starts.size
        val nextStart = if (isLast) length else starts[index + 1]
        if (endInclusive) return nextStart.coerceAtMost(length)
        // Step back over the terminator. The last line has none, so subtracting
        // there would silently drop a real character — which is how an offset at
        // the very end of a document stopped round-tripping.
        return if (isLast) nextStart else nextStart - 1
    }

    /** The line containing [offset]. */
    public fun lineOf(offset: Int): Int {
        val target = offset.coerceIn(0, length)
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= target) low = mid else high = mid - 1
        }
        return low
    }

    /** The column of [offset] within its line, counted in code units. */
    public fun columnOf(offset: Int): Int = offset.coerceIn(0, length) - lineStart(lineOf(offset))

    /** The offset of [column] on [line], clamped to the line's length. */
    public fun offsetOf(line: Int, column: Int): Int {
        val start = lineStart(line)
        val end = lineEnd(line)
        return (start + column.coerceAtLeast(0)).coerceIn(start, end)
    }

    public companion object {
        /** Builds the index for [text], treating `\n` as the line terminator. */
        public fun of(text: String): LineIndex {
            val starts = ArrayList<Int>()
            starts += 0
            var index = 0
            while (index < text.length) {
                if (text[index] == '\n') starts += index + 1
                index++
            }
            return LineIndex(starts.toIntArray(), text.length)
        }
    }
}

/**
 * Maps document offsets to visual positions and back.
 *
 * This lives in the state model, not the renderer, because it is pure logic and
 * unit-testable without a UI toolkit — which is a large part of why the editor
 * is worth isolating as a library. The renderer supplies layout facts (line
 * heights, wrap points); this class turns them into positions.
 *
 * `visualLines` is how a logical line splits across wrapped display lines. An
 * empty list means the logical line is not wrapped, which is the common case and
 * worth not paying for.
 */
public class PositionMapper(
    private val index: LineIndex,
    private val visualLines: Map<Int, List<Int>> = emptyMap(),
    private val columnsPerVisualLine: Int = Int.MAX_VALUE,
) {
    public fun textPosition(offset: Int): TextPosition {
        val clamped = offset.coerceIn(0, index.length)
        val line = index.lineOf(clamped)
        val column = index.columnOf(clamped)
        val breaks = visualLines[line]
        if (breaks.isNullOrEmpty()) {
            return TextPosition(clamped, line, column, line, column)
        }
        // A wrap point is the offset at which the next display line begins, so an
        // offset sitting exactly on one has already wrapped.
        val visualIndex = breaks.count { it <= clamped }
        val visualStart = if (visualIndex == 0) 0 else breaks[visualIndex - 1]
        return TextPosition(clamped, line, column, line + visualIndex, clamped - visualStart)
    }

    /** The offset a visual position denotes. */
    public fun offsetOf(line: Int, column: Int): Int = index.offsetOf(line, column)

    public fun lineOf(offset: Int): Int = index.lineOf(offset)

    /** The line range a selection covers, inclusive of both endpoints' lines. */
    public fun lineRangeOf(selection: TextRange): IntRange =
        index.lineOf(selection.start)..index.lineOf(selection.end)

    public companion object {
        /** A mapper with no wrapping information: one visual line per logical line. */
        public fun unindexed(text: String): PositionMapper = PositionMapper(LineIndex.of(text))
    }
}
