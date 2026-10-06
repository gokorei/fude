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
    private val crlf = "\r\n" // CR x LF, one cluster under UAX#29 GB3

    // U+1F3F4 waving black flag, then the six tag characters of "gbeng" and the cancel
    // tag U+E007F. Every tag character is a supplementary code point, so the sequence
    // is 14 UTF-16 units -- the subdivision flags introduced in Unicode 11.
    private val scotland =
        "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"

    // L + V + T jamo, which compose into the single syllable ga. All in the BMP, so
    // three code units that a user reads as one character.
    private val hangulJamo = "\u1100\u1161\u11A8"

    /**
     * The document the round-trip properties below walk over.
     *
     * Shared deliberately. Those two tests are exhaustive over whatever this holds,
     * so a cluster family that is added here and not handled by `Graphemes` shows up
     * as a count mismatch rather than as a case somebody forgot to write down.
     */
    private val fixture = "a${emoji}${combining}${family}${flag}${waving}${crlf}${scotland}${hangulJamo}b"

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
    fun crlfIsOneCluster() {
        // UAX#29 GB3: CR x LF. Both code units belong to one cluster, so the caret
        // has no boundary between them to stop at.
        val buffer = bufferOf("a\r\nb")
        assertEquals(4, buffer.length)
        assertEquals(3, buffer.nextGraphemeBoundary(1), "the line ending is one cluster")
        assertEquals(1, buffer.previousGraphemeBoundary(3), "backspace from after it takes both")
        assertEquals(1, buffer.previousGraphemeBoundary(2), "there is no boundary inside CRLF")
    }

    @Test
    fun backspaceOverACarriageReturnLineFeedRemovesBoth() {
        var state = EditorState.of("a\r\nb", caret = 3)
        state = state.deleteBackward()
        assertEquals("ab", state.text.text, "a lone carriage return left behind is invisible and corrupts every offset after it")
    }

    @Test
    fun deleteForwardOverACarriageReturnLineFeedRemovesBoth() {
        var state = EditorState.of("a\r\nb", caret = 1)
        state = state.deleteForward()
        assertEquals("ab", state.text.text)
    }

    @Test
    fun aLoneCarriageReturnIsOneCluster() {
        // GB4 breaks after Control, so a CR that is not followed by LF is its own
        // cluster -- and deleting it must remove it whole.
        val buffer = bufferOf("a\rb")
        assertEquals(2, buffer.nextGraphemeBoundary(1))
        assertEquals(1, buffer.previousGraphemeBoundary(2))

        var state = EditorState.of("a\rb", caret = 2)
        state = state.deleteBackward()
        assertEquals("ab", state.text.text)
    }

    @Test
    fun twoConsecutiveLineEndingsAreTwoClusters() {
        val buffer = bufferOf("\r\n\r\n")
        assertEquals(2, buffer.nextGraphemeBoundary(0), "the first CRLF is one cluster")
        assertEquals(4, buffer.nextGraphemeBoundary(2), "the second is another")
        assertEquals(0, buffer.previousGraphemeBoundary(2))
        assertEquals(2, buffer.previousGraphemeBoundary(4))
    }

    @Test
    fun aLineEndingEditReportReproducesExactlyWhatWasReported() {
        // `editBetween` used to snap its boundaries outward to cluster boundaries, on the
        // reasoning that a report landing inside a CRLF should take the whole pair. That
        // is what produced Delete(1..3, "\r\n") for the report below, which removed one
        // character — so the edit claimed to remove two, and its inverse re-inserted a
        // terminator nobody typed.
        //
        // Both cases are asserted here because they are genuinely different and the old
        // code could not tell them apart.
        assertEquals(
            Delete(TextRange(1, 3), removed = "\r\n"),
            editBetween("a\r\nb", "ab"),
            "a line ending removed whole is a range of two, because the report said so",
        )
        assertEquals(
            Delete(TextRange(1, 2), removed = "\r"),
            editBetween("a\r\nb", "a\nb"),
            "a report that dropped only the carriage return removes only the carriage return",
        )
        // The insertion side carries its extent in the payload rather than the range --
        // `Insert.affectedRange` is deliberately collapsed.
        assertEquals(Insert(1, "\r\n"), editBetween("ab", "a\r\nb"))
    }

    @Test
    fun undoingAWholeLineEndingRemovalRestoresItExactly() {
        val edit = editBetween("a\r\nb", "ab")!!
        assertEquals("ab", edit.applyTo("a\r\nb"))
        assertEquals("a\r\nb", edit.inverse().applyTo("ab"))

        val stack = UndoStack()
        val start = EditorState.of("a\r\nb")
        val end = start.apply(edit)
        stack.record(start, end, edit)
        assertEquals("a\r\nb", stack.undo(end)!!.text.text)
    }

    @Test
    fun undoingAPartiallyRemovedLineEndingRestoresItExactly() {
        // The counterpart to `undoingAWholeLineEndingRemovalRestoresItExactly` for the
        // report that removes only one half of the pair. This was the recorded gap: the
        // inverse used to re-insert a second terminator, so undoing one keystroke in a
        // CRLF document corrupted the line ending. `EditDiffTest`'s round-trip property
        // now covers every such report, and this keeps the undo path itself honest.
        val edit = editBetween("a\r\nb", "a\nb")!!
        assertEquals("a\nb", edit.applyTo("a\r\nb"), "the edit must reproduce the report")
        assertEquals("a\r\nb", edit.inverse().applyTo("a\nb"), "and the inverse must restore the CR")

        val stack = UndoStack()
        val start = EditorState.of("a\r\nb")
        val end = start.apply(edit)
        stack.record(start, end, edit)
        assertEquals("a\r\nb", stack.undo(end)!!.text.text, "undo restores one line ending, not two")
    }

    @Test
    fun anEmojiTagSequenceIsOneCluster() {
        assertEquals(14, scotland.length, "one base plus six tag characters, two units each")
        val buffer = bufferOf("a${scotland}b")
        assertEquals(15, buffer.nextGraphemeBoundary(1), "the whole subdivision flag is one cluster")
        assertEquals(1, buffer.previousGraphemeBoundary(15), "backspace from after it takes all of it")
    }

    @Test
    fun backspaceOverAnEmojiTagSequenceRemovesTheWholeCluster() {
        var state = EditorState.of("hi ${scotland}")
        state = state.deleteBackward()
        assertEquals("hi ", state.text.text, "removing one tag character leaves a broken flag")
    }

    @Test
    fun deleteForwardOverAnEmojiTagSequenceRemovesTheWholeCluster() {
        var state = EditorState.of("${scotland}x", caret = 0)
        state = state.deleteForward()
        assertEquals("x", state.text.text)
    }

    @Test
    fun twoConsecutiveTagSequencesAreTwoClusters() {
        val buffer = bufferOf("${scotland}${scotland}")
        assertEquals(14, buffer.nextGraphemeBoundary(0))
        assertEquals(28, buffer.nextGraphemeBoundary(14))
        assertEquals(0, buffer.previousGraphemeBoundary(14))
    }

    @Test
    fun aRegionalIndicatorFlagIsUnaffectedByTheTagRule() {
        // The tag characters and the regional indicators are different mechanisms, and
        // the RI pairing rule must still count two-at-a-time after both are present.
        val buffer = bufferOf("${scotland}${flag}")
        assertEquals(14, buffer.nextGraphemeBoundary(0), "the tag sequence is its own cluster")
        assertEquals(14 + flag.length, buffer.nextGraphemeBoundary(14), "the flag still pairs")
    }

    @Test
    fun hangulJamoComposeIntoOneSyllable() {
        val buffer = bufferOf("x${hangulJamo}y")
        assertEquals(5, buffer.length, "x, three jamo, y")
        assertEquals(4, buffer.nextGraphemeBoundary(1), "L + V + T is one syllable, not three clusters")
        assertEquals(1, buffer.previousGraphemeBoundary(4), "backspace from after it takes all three")
    }

    @Test
    fun backspaceOverConjoinedHangulJamoRemovesTheWholeSyllable() {
        var state = EditorState.of("ab${hangulJamo}")
        state = state.deleteBackward()
        assertEquals("ab", state.text.text)
    }

    @Test
    fun hangulGB6JoinsALeadingConsonantToAFollowingOne() {
        // L x (L | V | LV | LVT)
        assertEquals(2, bufferOf("\u1100\u1100").nextGraphemeBoundary(0), "L x L")
        assertEquals(2, bufferOf("\u1100\uAC00").nextGraphemeBoundary(0), "L x LV")
        assertEquals(2, bufferOf("\u1100\uAC01").nextGraphemeBoundary(0), "L x LVT")
    }

    @Test
    fun hangulGB7JoinsAVowelToAFollowingVowelOrTrailingConsonant() {
        // (LV | V) x (V | T)
        assertEquals(2, bufferOf("\uAC00\u1161").nextGraphemeBoundary(0), "LV x V")
        assertEquals(2, bufferOf("\uAC00\u11A8").nextGraphemeBoundary(0), "LV x T")
        assertEquals(2, bufferOf("\u1161\u1161").nextGraphemeBoundary(0), "V x V")
        assertEquals(2, bufferOf("\u1161\u11A8").nextGraphemeBoundary(0), "V x T")
    }

    @Test
    fun hangulGB8JoinsATrailingConsonantToAFollowingOne() {
        // (LVT | T) x T
        assertEquals(2, bufferOf("\uAC01\u11A8").nextGraphemeBoundary(0), "LVT x T")
        assertEquals(2, bufferOf("\u11A8\u11A8").nextGraphemeBoundary(0), "T x T")
    }

    @Test
    fun hangulJamoThatDoNotComposeStaySeparate() {
        // The negative cases, which matter as much as the positive ones: an arbitrary
        // pair of jamo is not a syllable, and gluing them anyway would be worse than the
        // gap. V cannot be followed by L, and T cannot be followed by V.
        assertEquals(1, bufferOf("\u1161\u1100").nextGraphemeBoundary(0), "V x L does not compose")
        assertEquals(1, bufferOf("\u11A8\u1161").nextGraphemeBoundary(0), "T x V does not compose")
        assertEquals(2, bufferOf("\uAC01\u1100").nextGraphemeBoundary(1), "LVT x L does not compose")
    }

    @Test
    fun aPrecomposedHangulSyllableIsUnchangedByThis() {
        // Precomposed syllables are a single code point and were already one cluster.
        // Nothing here may change that, and the LV/LVT arithmetic has to agree.
        val buffer = bufferOf("\uAC00\uAC01\uAC02")
        assertEquals(1, buffer.nextGraphemeBoundary(0))
        assertEquals(2, buffer.nextGraphemeBoundary(1))
        assertEquals(3, buffer.nextGraphemeBoundary(2))
    }

    @Test
    fun theHangulAndCombiningMarkMechanismsDoNotInterfere() {
        // The jamo blocks, the tag block and the combining-mark ranges are all disjoint, so
        // neither new rule may change what the existing one does. Checked rather than
        // assumed, since both new checks sit ahead of `isCombiningMark` in the same
        // function.
        assertEquals(2, bufferOf("\u1100\u1161").nextGraphemeBoundary(0), "jamo still compose")
        assertEquals(2, bufferOf("\u0915\u094D").nextGraphemeBoundary(0), "a virama still joins its consonant")
        assertEquals(1, bufferOf("\u0915").nextGraphemeBoundary(0), "a bare consonant is still alone")
        assertEquals(2, bufferOf("e\u0301").nextGraphemeBoundary(0), "and a combining mark still joins")
    }

    @Test
    fun aDevanagariConjunctStillSplitsAfterTheViramaAndThatIsRecorded() {
        // UAX#29 GB9c (Indic_Conjunct_Break) is not implemented. Recorded as a known gap
        // in docs/conformance.md, and pinned here so it is a decision rather than an
        // oversight.
        //
        // The half of it that does work is GB9: U+094D DEVANAGARI SIGN VIRAMA falls inside
        // the existing 0x093A..0x094F combining range, so a virama joins the consonant it
        // follows. What is missing is the consonant *after* the virama, which GB9c glues
        // to the conjunct -- so `ka + virama + ssa` breaks at its last character and
        // backspace at the end of it removes the final consonant alone, leaving the
        // consonant and virama on screen.
        //
        // Left unimplemented deliberately: no document in the corpus contains Devanagari,
        // unlike Korean, which `SpikeDocument.syntheticBody` generates.
        val conjunct = "\u0915\u094D\u0937"
        val buffer = bufferOf(conjunct)
        assertEquals(3, buffer.length, "three code units")
        assertEquals(2, buffer.nextGraphemeBoundary(0), "GB9 joins the virama to its consonant")
        assertEquals(2, buffer.previousGraphemeBoundary(3), "but GB9c does not join the final consonant")

        var state = EditorState.of(conjunct, caret = 3)
        state = state.deleteBackward()
        assertEquals("\u0915\u094D", state.text.text, "the half conjunct is left behind")
    }

    @Test
    fun aCaretInsideALineEndingDeletesOneCharacterNotTheWholeCluster() {
        // Offset 2 in "a\r\nb" is between the CR and the LF. It is now strictly inside
        // one cluster, so it is not a position the caret can be moved to: `previousGrapheme`
        // and `nextGrapheme` step by whole clusters, and Home and End snap to one.
        //
        // Pinned because the cluster is now wider than the range between those two
        // offsets. Both directions delete exactly one cluster, the one on their own side,
        // which is what `deleteBackward` and `deleteForward` have always promised --
        // changing this to delete the whole *containing* cluster would be a change to
        // every cluster kind, and it would still be one character for `deleteForward`.
        assertEquals(3, bufferOf("a\r\nb").graphemeEndOf(2))
        assertEquals(1, bufferOf("a\r\nb").graphemeStartOf(2), "the containing cluster starts at 1")

        var back = EditorState.of("a\r\nb", caret = 2)
        back = back.deleteBackward()
        assertEquals("a\nb", back.text.text)

        var forward = EditorState.of("a\r\nb", caret = 2)
        forward = forward.deleteForward()
        assertEquals("a\rb", forward.text.text)

        // Whereas the caret position a user can actually reach -- the start of the next
        // line -- removes the terminator whole.
        var reachable = EditorState.of("a\r\nb", caret = 3)
        reachable = reachable.deleteBackward()
        assertEquals("ab", reachable.text.text)
    }

    @Test
    fun everyOffsetInAFixtureResolvesToAStableBoundary() {
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
        assertEquals(
            10,
            visited.size,
            "ten clusters: ascii, emoji, accent, family, flag, waving, crlf, scotland, hangul, ascii",
        )
    }

    @Test
    fun walkingBackwardsUndoesWalkingForwards() {
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
