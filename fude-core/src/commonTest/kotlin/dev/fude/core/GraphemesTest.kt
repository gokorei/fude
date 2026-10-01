package dev.fude.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Grapheme-cluster deletion.
 *
 * Every case here is one where naive UTF-16 code-unit deletion corrupts text:
 * deleting half a surrogate pair, or a base character without its combining mark,
 * leaves visible garbage rather than removing a character.
 *
 * The fixtures use explicit escapes rather than literal characters. A literal
 * `é` is indistinguishable in source from `e` + combining acute depending on how
 * the file was written, and a test that cannot tell those apart is not testing
 * what it claims to.
 */
class GraphemesTest {
    private fun bufferOf(text: String) = TextBuffer.of(text)

    private val emoji = "\uD83D\uDE00" // U+1F600, above the BMP
    private val combining = "e\u0301" // e + U+0301, deliberately decomposed
    private val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
    private val flag = "\uD83C\uDDEF\uD83C\uDDF5" // two regional indicators
    private val waving = "\uD83D\uDC4B\uD83C\uDFFD" // base emoji plus a skin tone modifier
    private val variation = "\u2764\uFE0E" // heart plus VS15

    @Test
    fun asciiDeletesOneCharacter() {
        val buffer = bufferOf("abc")
        assertEquals(2, buffer.previousGraphemeBoundary(3))
        assertEquals(1, buffer.nextGraphemeBoundary(0))
    }

    @Test
    fun surrogatePairIsOneCluster() {
        val buffer = bufferOf("a${emoji}b")
        assertEquals(4, buffer.length, "a, the emoji's two code units, and b")
        assertEquals(3, buffer.nextGraphemeBoundary(1), "the whole emoji is one cluster")
        assertEquals(1, buffer.previousGraphemeBoundary(3), "backspace from after it takes all of it")
    }

    @Test
    fun combiningMarkStaysWithItsBase() {
        val buffer = bufferOf("x${combining}y")
        assertEquals(4, buffer.length, "x, e, combining acute, y")
        assertEquals(3, buffer.nextGraphemeBoundary(1), "e plus combining acute is one cluster")
        assertEquals(1, buffer.previousGraphemeBoundary(3))
        assertEquals(1, buffer.previousGraphemeBoundary(2), "the mark alone is not a separate cluster")
    }

    @Test
    fun precomposedAndDecomposedAccentAreEachOneCluster() {
        // The same accented character written two ways occupies a different number
        // of code units but is one cluster either way.
        val precomposed = "\u00E9"
        assertEquals(1, precomposed.length, "precomposed e-acute is one code unit")
        assertEquals(2, combining.length, "decomposed e-acute is two code units")
        assertEquals(precomposed.length, TextBuffer.of(precomposed).nextGraphemeBoundary(0))
        assertEquals(combining.length, TextBuffer.of(combining).nextGraphemeBoundary(0))
    }

    @Test
    fun zwjSequenceIsOneCluster() {
        val buffer = bufferOf("a${family}b")
        val after = 1 + family.length
        assertEquals(after, buffer.nextGraphemeBoundary(1), "a ZWJ sequence is one cluster")
        assertEquals(1, buffer.previousGraphemeBoundary(after))
    }

    @Test
    fun flagIsOneCluster() {
        val buffer = bufferOf(flag)
        assertEquals(flag.length, buffer.nextGraphemeBoundary(0))
        assertEquals(0, buffer.previousGraphemeBoundary(flag.length))
    }

    @Test
    fun consecutiveFlagsPairUpAsTwoClusters() {
        val buffer = bufferOf("$flag$flag")
        val first = flag.length
        assertEquals(first, buffer.nextGraphemeBoundary(0), "the first flag is one cluster")
        assertEquals(first * 2, buffer.nextGraphemeBoundary(first), "the second is another")
        assertEquals(0, buffer.previousGraphemeBoundary(first))
    }

    @Test
    fun skinToneModifierStaysWithItsBase() {
        val buffer = bufferOf("a${waving}b")
        val after = 1 + waving.length
        assertEquals(after, buffer.nextGraphemeBoundary(1))
        assertEquals(1, buffer.previousGraphemeBoundary(after))
    }

    @Test
    fun variationSelectorStaysWithItsBase() {
        val buffer = bufferOf(variation)
        assertEquals(variation.length, buffer.nextGraphemeBoundary(0))
    }

    @Test
    fun backspaceOverAFamilyEmojiRemovesTheWholeCluster() {
        var state = EditorState.of("hi $family")
        state = state.deleteBackward()
        assertEquals("hi ", state.text.text, "no half-emoji may be left behind")
    }

    @Test
    fun backspaceOverADecomposedAccentRemovesTheWholeCluster() {
        var state = EditorState.of("caf${combining}")
        state = state.deleteBackward()
        assertEquals("caf", state.text.text)
    }

    @Test
    fun deleteForwardOverAFlagRemovesBothRegionalIndicators() {
        // Caret at the start, because "delete forward" from the end is a no-op.
        var state = EditorState.of("${flag}x", caret = 0)
        state = state.deleteForward()
        assertEquals("x", state.text.text)
    }

    @Test
    fun backspaceOverASkinTonedEmojiRemovesTheModifierToo() {
        var state = EditorState.of("hi ${waving}")
        state = state.deleteBackward()
        assertEquals("hi ", state.text.text)
    }

    @Test
    fun backspaceOverADoubleWidthCharacterRemovesItWhole() {
        var state = EditorState.of("a日本語")
        state = state.deleteBackward()
        assertEquals("a日本", state.text.text)
    }

    @Test
    fun boundariesAtTheDocumentEdgesAreSafe() {
        val buffer = bufferOf("abc")
        assertEquals(0, buffer.previousGraphemeBoundary(0))
        assertEquals(3, buffer.nextGraphemeBoundary(3))
        // Out-of-range offsets clamp rather than throwing or wrapping.
        assertEquals(2, buffer.previousGraphemeBoundary(99))
        assertEquals(3, buffer.nextGraphemeBoundary(99))
        assertEquals(0, buffer.previousGraphemeBoundary(-5))
    }

    @Test
    fun everyOffsetInAFixtureResolvesToAStableBoundary() {
        val fixture = "a${emoji}${combining}${family}${flag}${waving}b"
        val buffer = TextBuffer.of(fixture)
        for (offset in 0..buffer.length) {
            val start = buffer.previousGraphemeBoundary(offset)
            val end = buffer.nextGraphemeBoundary(start)
            assertEquals(start, buffer.previousGraphemeBoundary(offset), "start must be stable at $offset")
            assertEquals(end, buffer.graphemeEndOf(start), "end must follow start at $offset")
            assertEquals(start, buffer.graphemeStartOf(end), "round trip at $offset")
            assertEquals(start, buffer.graphemeStartOf(offset), "start is idempotent at $offset")
        }
    }

    @Test
    fun walkingForwardsCoversTheWholeDocumentExactlyOnce() {
        val fixture = "a${emoji}${combining}${family}${flag}${waving}b"
        val buffer = TextBuffer.of(fixture)
        val visited = mutableListOf<Int>()
        var offset = 0
        while (offset < buffer.length) {
            visited += offset
            val next = buffer.nextGraphemeBoundary(offset)
            assertEquals(true, next > offset, "the walk must advance at $offset")
            offset = next
        }
        assertEquals(buffer.length, offset, "the walk must land exactly on the end")
        assertEquals(7, visited.size, "seven clusters: ascii, emoji, accent, family, flag, waving, ascii")
    }

    @Test
    fun walkingBackwardsUndoesWalkingForwards() {
        val fixture = "a${emoji}${combining}${family}${flag}${waving}b"
        val buffer = TextBuffer.of(fixture)
        val forwards = mutableListOf<Int>()
        var offset = 0
        while (offset < buffer.length) {
            forwards += offset
            offset = buffer.nextGraphemeBoundary(offset)
        }
        val backwards = mutableListOf<Int>()
        var back = buffer.length
        while (back > 0) {
            back = buffer.previousGraphemeBoundary(back)
            backwards += back
        }
        assertEquals(forwards.reversed(), backwards)
    }
}
