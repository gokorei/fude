package dev.fude.core

/**
 * Grapheme cluster boundaries.
 *
 * Deliberately hand-written rather than delegating to `java.text.BreakIterator`:
 * the state model runs in `commonMain` on every target, and the architecture
 * check forbids `java.*` imports there. This also has to be code-point aware,
 * because almost every interesting cluster begins with a character above the BMP
 * and therefore occupies two UTF-16 units — a naive code-unit scan splits every
 * emoji it meets.
 *
 * The rules implemented are the ones that actually break editors:
 * - surrogate pairs, so a character above the BMP is one unit
 * - combining marks, so `e` + U+0301 is one cluster
 * - ZWJ sequences, so a family emoji is one cluster
 * - regional indicator pairs, so a flag is one cluster
 * - skin-tone modifiers and variation selectors
 */
public object Graphemes {

    public const val ZERO_WIDTH_JOINER: Int = 0x200D
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3

    /** The code point starting at [index], which must not split a surrogate pair. */
    public fun codePointAt(text: String, index: Int): Int {
        val high = text[index]
        if (high.code in 0xD800..0xDBFF && index + 1 < text.length && text[index + 1].code in 0xDC00..0xDFFF) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
        }
        return high.code
    }

    /** How many UTF-16 units the code point at [index] occupies. */
    public fun codePointSizeAt(text: String, index: Int): Int =
        if (codePointAt(text, index) > 0xFFFF) 2 else 1

    /** The offset of the code point that ends at [index]. */
    public fun codePointStartBefore(text: String, index: Int): Int {
        if (index <= 0) return 0
        if (index >= 2 && text[index - 1].code in 0xDC00..0xDFFF && text[index - 2].code in 0xD800..0xDBFF) {
            return index - 2
        }
        return index - 1
    }

    /** Whether [offset] falls between the two halves of a surrogate pair. */
    public fun splitsSurrogatePair(text: String, offset: Int): Boolean =
        offset in 1 until text.length &&
            text[offset - 1].code in 0xD800..0xDBFF &&
            text[offset].code in 0xDC00..0xDFFF

    private fun isCombiningMark(codePoint: Int): Boolean = when (codePoint) {
        in 0x0300..0x036F -> true
        in 0x0483..0x0489 -> true
        in 0x0591..0x05BD -> true
        in 0x0610..0x061A -> true
        in 0x064B..0x065F -> true
        0x0670 -> true
        in 0x06D6..0x06DC -> true
        in 0x0730..0x074A -> true
        in 0x07A6..0x07B0 -> true
        in 0x0900..0x0903 -> true
        in 0x093A..0x094F -> true
        in 0x0951..0x0957 -> true
        in 0x0962..0x0963 -> true
        0x0E31 -> true
        in 0x0E34..0x0E3A -> true
        in 0x0E47..0x0E4E -> true
        in 0x1AB0..0x1AFF -> true
        in 0x1DC0..0x1DFF -> true
        in 0x20D0..0x20F0 -> true
        in 0xFE00..0xFE0F -> true
        in 0xFE20..0xFE2F -> true
        else -> false
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private fun isSkinToneModifier(codePoint: Int): Boolean = codePoint in 0x1F3FB..0x1F3FF

    /**
     * Whether the code point at [index] belongs to the cluster that starts before it.
     *
     * @param index the offset of the code point, which must not split a surrogate pair
     */
    public fun continuesPreviousCluster(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return false
        val current = codePointAt(text, index)
        val previousIndex = codePointStartBefore(text, index)
        val previous = codePointAt(text, previousIndex)

        if (isCombiningMark(current)) return true
        if (current == ZERO_WIDTH_JOINER) return true
        if (previous == ZERO_WIDTH_JOINER) return true
        if (isSkinToneModifier(current)) return true
        if (current == COMBINING_ENCLOSING_KEYCAP) return true

        if (isRegionalIndicator(current) && isRegionalIndicator(previous)) {
            // Regional indicators pair two at a time from the start of the run.
            // Counting the indicators in the run that ends at the previous one: an
            // odd count means that one is unpaired, so it opened a cluster and this
            // indicator joins it.
            return regionalIndicatorsEndingAt(text, previousIndex) % 2 == 1
        }

        return false
    }

    /**
     * How many regional indicators are in the consecutive run ending at [index],
     * counting [index] itself.
     *
     * Counted in code points, not code units: every regional indicator is above
     * the BMP and occupies two UTF-16 units, so counting units would pair the two
     * halves of one flag instead of two flags.
     */
    private fun regionalIndicatorsEndingAt(text: String, index: Int): Int {
        var count = 0
        var cursor = index
        while (cursor >= 0 && isRegionalIndicator(codePointAt(text, cursor))) {
            count++
            if (cursor == 0) break
            val start = codePointStartBefore(text, cursor)
            // Guard against a non-advancing walk at the start of the document.
            if (start >= cursor) break
            cursor = start
        }
        return count
    }
}

/** The start of the grapheme cluster containing or ending at [offset]. */
public fun TextBuffer.previousGraphemeBoundary(offset: Int): Int {
    var index = offset.coerceIn(0, length)
    if (index == 0) return 0

    // Normalise onto a code point boundary first. An offset that falls between the
    // halves of a surrogate pair is inside a cluster, not at its edge, so stepping
    // back to the pair's start is necessary but not sufficient — the walk below has
    // to continue from there to reach the real cluster start.
    index = Graphemes.codePointStartBefore(text, index)
    while (index > 0 && Graphemes.continuesPreviousCluster(text, index)) {
        index = Graphemes.codePointStartBefore(text, index)
    }
    return index
}

/** The end of the grapheme cluster starting at [offset]. */
public fun TextBuffer.nextGraphemeBoundary(offset: Int): Int {
    val start = offset.coerceIn(0, length)
    if (start >= length) return length
    var index = start + Graphemes.codePointSizeAt(text, start)
    while (index < length && Graphemes.continuesPreviousCluster(text, index)) {
        index += Graphemes.codePointSizeAt(text, index)
    }
    return index
}

/** The offset of the grapheme boundary at or before [offset]. */
public fun TextBuffer.graphemeStartOf(offset: Int): Int = previousGraphemeBoundary(offset.coerceIn(0, length))

/** The offset of the grapheme boundary at or after [offset]. */
public fun TextBuffer.graphemeEndOf(offset: Int): Int = nextGraphemeBoundary(offset.coerceIn(0, length))

/** Moves [offset] back by one grapheme cluster. */
public fun TextBuffer.previousGrapheme(offset: Int): Int = previousGraphemeBoundary(offset)

/** Moves [offset] forward by one grapheme cluster. */
public fun TextBuffer.nextGrapheme(offset: Int): Int = nextGraphemeBoundary(offset)
