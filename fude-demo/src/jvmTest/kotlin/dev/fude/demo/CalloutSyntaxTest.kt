package dev.fude.demo

import dev.fude.markdown.HostBlockNode
import dev.fude.markdown.IncrementalMarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The demo's half of the extension point, driven through the real parser.
 *
 * This exists because `CalloutSyntax` shipped implementing `recogniseBlocks`, which
 * no production code called, and the demo's own README called the demo "the worked
 * example the extension-point ticket asks for". The callouts did not work. Nothing
 * caught it, because `SyntaxExtensionTest` calls the method directly and passes —
 * a tested unit with untested wiring.
 *
 * So the assertion here is deliberately end-to-end: the sample document the demo
 * actually opens, through the parser the editor actually uses.
 */
class CalloutSyntaxTest {

    private val parser = IncrementalMarkdownParser(listOf(CalloutSyntax()))

    @Test
    fun theSampleDocumentTheDemoOpensContainsACallout() {
        val blocks = parser.parse(sampleDocument).blocks
        val callouts = blocks.filterIsInstance<HostBlockNode>().filter { it.extensionId == "callout" }

        assertTrue(
            callouts.isNotEmpty(),
            "the sample document must exercise the callout the demo registers; " +
                "saw ${blocks.map { it.kind }}",
        )
    }

    @Test
    fun aCalloutIsClaimedRatherThanReadAsABlockQuote() {
        val text = "> [!note]\n> This callout came from the host, not the library.\n"
        val blocks = parser.parse(text).blocks

        val callout = assertIs<HostBlockNode>(blocks.single())
        assertEquals("callout", callout.extensionId)
        assertEquals(
            text,
            text.substring(callout.range.start, callout.range.end),
            "the claim covers the marker, its body and the line ending that ends it",
        )
    }

    /**
     * The inverse is the boundary that makes the extension point mean anything: an
     * ordinary quote is still a quote, so the extension is not simply shadowing
     * Markdown wholesale.
     */
    @Test
    fun anOrdinaryQuoteIsStillABlockQuote() {
        val blocks = parser.parse("> just a quote\n").blocks
        assertEquals(
            listOf(dev.fude.markdown.BlockKind.BLOCK_QUOTE),
            blocks.map { it.kind },
        )
    }

    /**
     * The incremental path has to agree, or a callout disappears the moment the user
     * types anywhere in the document.
     */
    @Test
    fun theCalloutSurvivesAnEditElsewhereInTheDocument() {
        val text = "> [!note]\n> Careful.\n\nA paragraph.\n"
        parser.parse(text)

        val at = text.indexOf("A paragraph")
        val edited = text.substring(0, at) + "An edited paragraph.\n"
        val after = parser.reparse(edited, dev.fude.core.Insert(at, ""))

        assertEquals(
            HostBlockNode::class,
            after.blocks.first()::class,
            "an edit below the callout must not cost it",
        )
        assertEquals(
            IncrementalMarkdownParser(listOf(CalloutSyntax())).parse(edited).blocks.map { it.kind },
            after.blocks.map { it.kind },
            "and the incremental result must match a full parse",
        )
    }
}