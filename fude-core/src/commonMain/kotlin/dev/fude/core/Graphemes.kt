package dev.fude.core

/**
 * Grapheme cluster boundaries.
 *
 * ## What this is for
 *
 * One call site:
 *
 * - [EditorState.deleteBackward] / [deleteForward] removing one cluster
 *
 * That is a thin justification for a file this size, and it is stated plainly rather
 * than padded. It held two call sites until the `input/` package was deleted: a
 * `KeyHandler` that moved the caret one grapheme left or right. Horizontal arrows are
 * Compose's now — it issues `MoveCursorCommand` — so that leg is gone. What remains is
 * the model-level delete, and it remains because Compose's cluster-aware deletion
 * (`DeleteSurroundingTextInCodePointsCommand`) applies to the text field, not to
 * `EditorState`.
 *
 * It is **not** here because Compose lacks grapheme segmentation — Compose has it,
 * and uses it: `BasicTextField` issues `DeleteSurroundingTextInCodePointsCommand`
 * to the platform, which segments with `BreakIterator`. The caret a user actually
 * drags is therefore already cluster-correct without any of this. What Compose does
 * not do is segment a *model-level* delete, which is why the first of those two
 * remains ours.
 *
 * ## A third call site was removed, and the reason generalises
 *
 * This file used to be justified partly by [editBetween], which snapped a recovered
 * edit's edges outward to cluster boundaries so an edit reported mid-cluster would
 * undo as a whole cluster. That turned out to be incompatible with `editBetween`'s
 * own round-trip contract: a common prefix and a common suffix already account for
 * every unchanged character, so widening a boundary past them deletes a character the
 * report said was unchanged and no adjustment of the other end can restore it. A
 * report that removed one carriage return of a CRLF produced an edit claiming to
 * remove two, whose inverse re-inserted a line ending nobody typed.
 *
 * The general lesson is the one worth keeping: **a function with a hard contract
 * about reproducing its input cannot also adjust that input.** `editBetween` must
 * report what the platform reported; anything cleverer is a different function. It no
 * longer imports this file, which is why the list above has two entries and not three.
 *
 * ## Why it is hand-written
 *
 * The state model runs in `commonMain` on every target, and the architecture check
 * forbids `java.*` imports there — so `java.text.BreakIterator` is unavailable even
 * though it would otherwise be the obvious answer. It also has to be code-point
 * aware, because almost every interesting cluster begins above the BMP and
 * therefore occupies two UTF-16 units; a naive code-unit scan splits every emoji.
 *
 * ## Scope, stated so it is not mistaken for UAX#29
 *
 * The implemented rules are the ones that produce a visible break if missed:
 * surrogate pairs, combining marks, ZWJ sequences, regional indicator pairs,
 * skin-tone modifiers, variation selectors, CRLF, emoji tag characters, and Hangul
 * jamo composition.
 *
 * This is deliberately not a complete UAX#29 implementation. The aim is that no
 * cluster a user can see is split in half — not conformance. Indic conjuncts (GB9c)
 * are the known remaining gap and are recorded in `docs/conformance.md`.
 *
 * A narrower claim is also a testable one: every rule here has a case in
 * `GraphemesTest`, and a rule added without one is a rule nobody checked.
 */
internal object Graphemes {

    private const val ZERO_WIDTH_JOINER: Int = 0x200D
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
    private const val CARRIAGE_RETURN = 0x000D
    private const val LINE_FEED = 0x000A

    /** The code point starting at [index], which must not split a surrogate pair. */
    private fun codePointAt(text: String, index: Int): Int {
        val high = text[index]
        if (high.code in 0xD800..0xDBFF && index + 1 < text.length && text[index + 1].code in 0xDC00..0xDFFF) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
        }
        return high.code
    }

    /** How many UTF-16 units the code point at [index] occupies. */
    internal fun codePointSizeAt(text: String, index: Int): Int =
        if (codePointAt(text, index) > 0xFFFF) 2 else 1

    /** The offset of the code point that ends at [index]. */
    internal fun codePointStartBefore(text: String, index: Int): Int {
        if (index <= 0) return 0
        if (index >= 2 && text[index - 1].code in 0xDC00..0xDFFF && text[index - 2].code in 0xD800..0xDBFF) {
            return index - 2
        }
        return index - 1
    }

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
     * Whether [codePoint] is an emoji tag character, `U+E0020..U+E007F`.
     *
     * The subdivision flags introduced in Unicode 11 are a base emoji followed by these
     * -- `U+1F3F4` plus the letters of the subdivision plus `U+E007F` to cancel. They are
     * what a regional indicator pair is not: a flag such as `U+1F1EF U+1F1F5` is two code
     * points by convention, while Scotland is seven. Deleting one tag character does not
     * merely lose an invisible character, it leaves a flag that renders as something
     * else.
     *
     * Checked before [isCombiningMark] so the rule that covers them stays readable at the
     * call site. The ranges are disjoint, so the order is for the reader rather than for
     * the behaviour.
     */
    private fun isEmojiTag(codePoint: Int): Boolean = codePoint in 0xE0020..0xE007F

    // Hangul syllable composition needs the jamo classes and the precomposed syllables.
    // The syllables are laid out in blocks of 28 per leading consonant -- one for the
    // bare vowel and 27 for each of the 27 trailing consonants -- so which of the two
    // composed classes a syllable belongs to is arithmetic on its offset, not a table.
    private const val HANGUL_SYLLABLE_FIRST = 0xAC00
    private const val HANGUL_SYLLABLE_LAST = 0xD7A3
    private const val HANGUL_SYLLABLE_BLOCK = 28

    /** UAX#29 class L: a leading consonant, which may start a syllable. */
    private fun isHangulLeadingConsonant(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115F || codePoint in 0xA960..0xA97C

    /** UAX#29 class V: a vowel, which continues one. */
    private fun isHangulVowel(codePoint: Int): Boolean =
        codePoint in 0x1160..0x11A7 || codePoint in 0xD7B0..0xD7C6

    /** UAX#29 class T: a trailing consonant, which may only end one. */
    private fun isHangulTrailingConsonant(codePoint: Int): Boolean =
        codePoint in 0x11A8..0x11FF || codePoint in 0xD7CB..0xD7FB

    /** UAX#29 class LV: a precomposed syllable with no trailing consonant. */
    private fun isHangulSyllableWithLeadingVowel(codePoint: Int): Boolean =
        codePoint in HANGUL_SYLLABLE_FIRST..HANGUL_SYLLABLE_LAST &&
            (codePoint - HANGUL_SYLLABLE_FIRST) % HANGUL_SYLLABLE_BLOCK == 0

    /** UAX#29 class LVT: a precomposed syllable that already ends in a consonant. */
    private fun isHangulSyllableWithTrailingConsonant(codePoint: Int): Boolean =
        codePoint in HANGUL_SYLLABLE_FIRST..HANGUL_SYLLABLE_LAST &&
            (codePoint - HANGUL_SYLLABLE_FIRST) % HANGUL_SYLLABLE_BLOCK != 0

    /**
     * Whether [current] continues the Hangul syllable [previous] began, under GB6-GB8.
     *
     * Three rules rather than one, because jamo compose in a fixed order and the middle
     * of that order is not symmetric:
     *
     * - GB6: L x (L | V | LV | LVT)
     * - GB7: (LV | V) x (V | T)
     * - GB8: (LVT | T) x T
     *
     * The order is what makes the negative cases fall out. A vowel cannot be followed by
     * a leading consonant and a trailing consonant cannot be followed by a vowel, so
     * arbitrary pairs of jamo stay separate clusters instead of being glued into a
     * syllable that is not there.
     */
    private fun hangulContinues(previous: Int, current: Int): Boolean = when {
        isHangulLeadingConsonant(previous) ->
            isHangulLeadingConsonant(current) ||
                isHangulVowel(current) ||
                current in HANGUL_SYLLABLE_FIRST..HANGUL_SYLLABLE_LAST

        isHangulSyllableWithLeadingVowel(previous) || isHangulVowel(previous) ->
            isHangulVowel(current) || isHangulTrailingConsonant(current)

        // GB8: (LVT | T) x T. Both halves are needed: an LVT syllable is a precomposed
        // code point outside the T jamo ranges, so testing only for a T jamo here would
        // join the jamo pairs and miss the syllables.
        else ->
            (isHangulSyllableWithTrailingConsonant(previous) || isHangulTrailingConsonant(previous)) &&
                isHangulTrailingConsonant(current)
    }

    /**
     * Whether the code point at [index] belongs to the cluster that starts before it.
     *
     * @param index the offset of the code point, which must not split a surrogate pair
     */
    fun continuesPreviousCluster(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return false
        val current = codePointAt(text, index)
        val previousIndex = codePointStartBefore(text, index)
        val previous = codePointAt(text, previousIndex)

        if (isEmojiTag(current)) return true

        // UAX#29 GB6-GB8: Hangul jamo compose into one syllable. Three UTF-16 units that
        // a user reads as one character, so backspace has to take all three. Fude's own
        // synthetic benchmark document generates Korean text, so this is not a
        // hypothetical input.
        if (hangulContinues(previous, current)) return true

        if (isCombiningMark(current)) return true

        // UAX#29 GB3: CR x LF. A CRLF line ending is a single cluster, so backspace at
        // the start of the next line removes the terminator whole rather than deleting
        // the LF and leaving a carriage return behind -- which is invisible in a
        // proportional font and leaves every later offset one out of step with what the
        // user sees. A CRLF is a single unit everywhere else in this library already
        // (see `isLineTerminatorChar` and `pastLineTerminator`); the caret walks through
        // here, so it has to agree.
        //
        // No clause is needed for a lone CR: GB4 breaks after every Control, and neither
        // CR nor LF is a combining mark, so one on its own already forms its own cluster.
        if (current == LINE_FEED && previous == CARRIAGE_RETURN) return true

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
fun TextBuffer.previousGraphemeBoundary(offset: Int): Int {
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
fun TextBuffer.nextGraphemeBoundary(offset: Int): Int {
    val start = offset.coerceIn(0, length)
    if (start >= length) return length
    var index = start + Graphemes.codePointSizeAt(text, start)
    while (index < length && Graphemes.continuesPreviousCluster(text, index)) {
        index += Graphemes.codePointSizeAt(text, index)
    }
    return index
}

/**
 * The offset of the grapheme boundary at or before [offset].
 *
 * Distinct from `editBetween`'s own leading-edge helper on purpose: that one
 * returns [offset] unchanged when it already sits on a boundary, because a reported
 * change usually starts exactly between two clusters and stepping would widen
 * every ordinary keystroke by a whole character. This one always steps, because a
 * caret asking "where is the cluster I am in" wants the containing cluster's edge.
 */
fun TextBuffer.graphemeStartOf(offset: Int): Int = previousGraphemeBoundary(offset.coerceIn(0, length))

/** The offset of the grapheme boundary at or after [offset]. See [graphemeStartOf]. */
fun TextBuffer.graphemeEndOf(offset: Int): Int = nextGraphemeBoundary(offset.coerceIn(0, length))
