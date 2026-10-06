package dev.fude.syntax

import dev.fude.core.InlineRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The extension point, tested against a dialect the library has never heard of.
 *
 * If these tests needed a real wikilink implementation they would prove nothing:
 * what matters is that Fude can host syntax it does not know, resolve it without
 * the library knowing what it resolves to, and stay deterministic when two
 * extensions overlap.
 */
class SyntaxExtensionTest {

    private fun contextFor(text: String, range: InlineRange = InlineRange(0, text.length)) =
        BlockContext(text = text, range = range)

    @Test
    fun anInlineExtensionRecognisesItsOwnSyntax() {
        val mention = MentionSyntax()
        val text = "hello {{alice}} and {{bob}}"
        val matches = mention.recogniseInline(contextFor(text))
        assertEquals(2, matches.size)
        assertEquals("{{alice}}", text.substring(matches[0].start, matches[0].end))
        assertEquals("{{bob}}", text.substring(matches[1].start, matches[1].end))
    }

    @Test
    fun recognitionRangesAreAbsoluteDocumentOffsets() {
        val mention = MentionSyntax()
        val text = "prefix line\nthen {{carol}} here"
        // Ask about only the second line, as an incremental reparse would.
        val lineStart = text.indexOf("then")
        val context = contextFor(text, InlineRange(lineStart, text.length))
        val matches = mention.recogniseInline(context)
        assertEquals(1, matches.size)
        assertEquals("{{carol}}", text.substring(matches[0].start, matches[0].end))
    }

    @Test
    fun anEmptyMentionIsNotAMention() {
        val mention = MentionSyntax()
        assertEquals(emptyList(), mention.recogniseInline(contextFor("a {{}} b")), "an empty mention is not a mention")
    }

    @Test
    fun anUnclosedMentionIsNotAMention() {
        val mention = MentionSyntax()
        assertEquals(emptyList(), mention.recogniseInline(contextFor("hello {{alice")), "an unclosed mention is not a mention")
    }

    @Test
    fun recognitionIsPureAndRepeatable() {
        val mention = MentionSyntax()
        val text = "{{a}} {{b}} {{c}}"
        val first = mention.recogniseInline(contextFor(text))
        val second = mention.recogniseInline(contextFor(text))
        assertEquals(first, second, "the same text must yield the same matches")
        assertEquals(2, mention.recognitionCount, "two calls, so two recognitions")
    }

    @Test
    fun resolutionIsTheHostsCallbackAndTheLibraryStaysOutOfIt() {
        val mention = MentionSyntax()
        val text = "{{alice}}"
        val match = mention.recogniseInline(contextFor(text)).single()
        assertEquals("alice", mention.resolve(match, text))
        assertEquals(listOf("alice"), mention.resolved)
        // Nothing about DocId, a vault, or a network call appears here. The library
        // cannot resolve anything because it has no idea what a mention means.
    }

    @Test
    fun aHostCanAddABlockConstructTheLibraryHasNeverHeardOf() {
        val fence = FenceBlockSyntax()
        val text = ":::warning\nbe careful\n:::"
        val matches = fence.recogniseBlocks(contextFor(text))
        assertEquals(1, matches.size)
        assertEquals(text, text.substring(matches[0].range.start, matches[0].range.end))
        assertTrue(matches[0].ownsTerminator, "the closing delimiter belongs to the block")
    }

    @Test
    fun anUnterminatedBlockConstructIsNotABlock() {
        val fence = FenceBlockSyntax()
        assertEquals(emptyList(), fence.recogniseBlocks(contextFor(":::warning\nbe careful")))
    }

    @Test
    fun aBlockConstructWithoutATerminatorReportsThat() {
        val fence = FenceBlockSyntax()
        val text = ":::note\nbody"
        // No closing delimiter, so there is nothing to own.
        assertEquals(emptyList(), fence.recogniseBlocks(contextFor(text)))
    }

    @Test
    fun anExtensionThatOnlyDoesInlineNeedsNoBlockImplementation() {
        // FixedRangeSyntax does not override recogniseBlocks, so the default must be empty.
        val inlineOnly = FixedRangeSyntax("inline-only", 0, listOf(InlineRange(0, 1)))
        assertEquals(emptyList(), inlineOnly.recogniseBlocks(contextFor("abc")))
    }

    @Test
    fun longerMatchesWinOverShorterOnes() {
        val short = FixedRangeSyntax("short", 0, listOf(InlineRange(0, 3)))
        val long = FixedRangeSyntax("long", 0, listOf(InlineRange(0, 7)))
        val resolved = resolveMatches(listOf(short to InlineRange(0, 3), long to InlineRange(0, 7)))
        assertEquals(1, resolved.size)
        assertEquals("long", resolved[0].extensionId, "the more specific match wins")
    }

    @Test
    fun onATieHigherPriorityWins() {
        val low = FixedRangeSyntax("low", 0, listOf(InlineRange(0, 4)))
        val high = FixedRangeSyntax("high", 5, listOf(InlineRange(0, 4)))
        val resolved = resolveMatches(listOf(low to InlineRange(0, 4), high to InlineRange(0, 4)))
        assertEquals(1, resolved.size)
        assertEquals("high", resolved[0].extensionId)
    }

    @Test
    fun onAFullTieTheIdBreaksItDeterministically() {
        val b = FixedRangeSyntax("bbb", 0, listOf(InlineRange(0, 4)))
        val a = FixedRangeSyntax("aaa", 0, listOf(InlineRange(0, 4)))
        // Order in the input must not change the outcome.
        val first = resolveMatches(listOf(b to InlineRange(0, 4), a to InlineRange(0, 4)))
        val second = resolveMatches(listOf(a to InlineRange(0, 4), b to InlineRange(0, 4)))
        assertEquals("aaa", first[0].extensionId)
        assertEquals(first, second, "registration order must not affect rendering")
    }

    @Test
    fun nonOverlappingMatchesAllSurvive() {
        val a = FixedRangeSyntax("a", 0, listOf(InlineRange(0, 2), InlineRange(4, 6)))
        val candidates = a.recogniseInline(contextFor("aabbcc")).map { a to it }
        val resolved = resolveMatches(candidates)
        assertEquals(2, resolved.size)
        assertEquals(InlineRange(0, 2), resolved[0].range)
        assertEquals(InlineRange(4, 6), resolved[1].range)
    }

    @Test
    fun resolvedMatchesComeBackInDocumentOrder() {
        val extension = FixedRangeSyntax("any", 0, listOf(InlineRange(10, 12), InlineRange(0, 2), InlineRange(5, 6)))
        val candidates = extension.recogniseInline(contextFor("x".repeat(20))).map { extension to it }
        val resolved = resolveMatches(candidates)
        assertEquals(listOf(0, 5, 10), resolved.map { it.range.start })
    }

    @Test
    fun twoDialectsCanCoexistWithoutCorruptingEachOther() {
        val mention = MentionSyntax()
        val text = "{{alice}} wrote about ::: fences"
        val context = contextFor(text)
        val mentionMatches = mention.recogniseInline(context)
        val fence = FenceBlockSyntax()
        val blockMatches = fence.recogniseBlocks(context)

        assertEquals(1, mentionMatches.size)
        assertEquals(0, blockMatches.size, "an unterminated fence is not a block")
        assertEquals("{{alice}}", text.substring(mentionMatches[0].start, mentionMatches[0].end))
    }

    @Test
    fun extensionRangesMapBackToCorrectSourceOffsets() {
        // The mapping problem: a decorated range must still select the source it
        // came from, or selecting a mention selects the wrong span.
        val mention = MentionSyntax()
        val text = "line one\nhi {{dave}} and {{erin}}\nline three"
        val lineStart = text.indexOf("hi")
        val context = contextFor(text, InlineRange(lineStart, text.indexOf("\n", lineStart)))
        val matches = mention.recogniseInline(context)
        assertEquals(2, matches.size)
        assertEquals("{{dave}}", text.substring(matches[0].start, matches[0].end))
        assertEquals("{{erin}}", text.substring(matches[1].start, matches[1].end))
    }
}
