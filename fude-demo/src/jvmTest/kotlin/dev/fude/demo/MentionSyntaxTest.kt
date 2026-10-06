package dev.fude.demo

import dev.fude.editor.EditorState
import dev.fude.markdown.HostInlineNode
import dev.fude.markdown.IncrementalMarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The demo's half of the mention contract, driven through the real parser.
 *
 * `App` used to resolve a clicked mention against a `lastText` cache fed by
 * `onChange`. That cache starts empty and the editor skips `onChange` for
 * host-driven loads, so the first click before any edit resolved a real range
 * against `""` and threw — and after a document swap it resolved against the
 * previous note. The handler now reads `state.text`, so these tests simulate a
 * fresh load: recognise through the parser, resolve against the freshly loaded
 * state's text, with no edit and no `onChange` having fired.
 *
 * Note the document used here, not [sampleDocument]: the sample's mention is
 * `{{alice}}`, covered by [theSampleDocumentItselfContainsARecognisedMention]
 * below. A dedicated doc pins the resolution path with a mention the syntax
 * actually recognises.
 */
class MentionSyntaxTest {

    private val mention = MentionSyntax()
    private val parser = IncrementalMarkdownParser(listOf(mention))

    private val docWithMention = "Mention someone with {{alice}} here.\n"

    private fun mentionRanges(text: String): List<dev.fude.core.InlineRange> =
        parser.parse(text).allBlocks
            .flatMap { it.inlines }
            .filterIsInstance<HostInlineNode>()
            .filter { it.extensionId == "mention" }
            .map { it.range }

    @Test
    fun aMentionIsRecognisedThroughTheRealParser() {
        val ranges = mentionRanges(docWithMention)

        assertTrue(
            ranges.isNotEmpty(),
            "the mention syntax must fire through the parser the editor uses",
        )
        assertEquals(
            "{{alice}}",
            docWithMention.substring(ranges.first().start, ranges.first().end),
        )
    }

    /**
     * The sample document is the worked example, so it must work: it used to
     * showcase `{{double braces}}`, which [MentionSyntax] deliberately rejects
     * (a handle is one token, no whitespace), so the demo's own example was
     * never recognised, styled, or clickable. The sample now showcases
     * `{{alice}}`; this test fails if anyone reintroduces a showcase the
     * syntax rejects.
     */
    @Test
    fun theSampleDocumentItselfContainsARecognisedMention() {
        val ranges = mentionRanges(sampleDocument)
        assertTrue(
            ranges.isNotEmpty(),
            "the demo opens on this document; its showcase mention must be recognised",
        )
        assertEquals(
            "{{alice}}",
            sampleDocument.substring(ranges.first().start, ranges.first().end),
        )
    }

    @Test
    fun aMentionResolvesOnFreshLoadWithNoEdit() {
        val ranges = mentionRanges(docWithMention)
        assertTrue(ranges.isNotEmpty())

        // What the click handler reads: the freshly loaded state's own text.
        val stateText = EditorState.of(docWithMention).text

        assertEquals(
            "alice",
            mention.resolve(ranges.first(), stateText),
            "clicking the mention before typing anything must resolve",
        )
    }

    /**
     * Pins the reported crash rather than just the fix: the old `lastText`
     * cache started as `""`, and resolving a real mention range against it
     * threw. If this stops throwing, the test above is asserting less than it
     * claims.
     */
    @Test
    fun resolvingARealRangeAgainstAnEmptyCacheThrows() {
        val ranges = mentionRanges(docWithMention)
        assertTrue(ranges.isNotEmpty())

        assertFails(
            "a mention range resolved against an empty onChange cache must fail loudly, " +
                "which is the crash the state.text fix removes",
        ) {
            mention.resolve(ranges.first(), "")
        }
    }

    /**
     * The contract the fix rests on: a range is only valid against the text it
     * was recognised in. Resolving the old document's range against swapped-in
     * text fails loudly rather than misresolving — which is safe because the
     * handler takes both the range (from the current frame's decorations) and
     * the text (`state.text`) from the same document. The old cache broke this
     * by pairing a new range with old text.
     */
    @Test
    fun aStaleRangeAgainstSwappedTextFailsLoudly() {
        val ranges = mentionRanges(docWithMention)
        assertTrue(ranges.isNotEmpty())

        val swapped = EditorState.of("Nothing to resolve here.\n").text
        assertTrue(mentionRanges(swapped).isEmpty())

        assertFails(
            "the previous note's range means nothing against the swapped text",
        ) {
            mention.resolve(ranges.first(), swapped)
        }
    }
}
