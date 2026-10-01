package dev.fude.markdown

import dev.fude.core.InlineRange
import dev.fude.syntax.SyntaxExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarkdownParserTest {
    private fun parse(text: String, extensions: List<SyntaxExtension> = emptyList()): ParsedDocument =
        IncrementalMarkdownParser(extensions).parse(text)

    @Test
    fun aHeadingIsParsedWithItsLevel() {
        val doc = parse("# Title\n## Sub")
        val heading = assertIs<HeadingNode>(doc.blocks[0])
        assertEquals(1, heading.level)
        assertEquals("# Title", doc.text.substring(heading.range.start, heading.range.end))
    }

    @Test
    fun headingContentExcludesTheMarkers() {
        val doc = parse("# Title")
        val heading = assertIs<HeadingNode>(doc.blocks[0])
        val text = heading.inlines.filterIsInstance<TextNode>().joinToString("") { it.text }
        assertEquals("Title", text)
    }

    @Test
    fun strongAndEmphasisAreDistinct() {
        val doc = parse("**bold** and *italic*")
        val inlines = (doc.blocks[0] as ParagraphNode).inlines
        val strong = inlines.filterIsInstance<EmphasisNode>().first { it.strong }
        val emphasis = inlines.filterIsInstance<EmphasisNode>().first { !it.strong }
        assertEquals("bold", strong.children.filterIsInstance<TextNode>().single().text)
        assertEquals("italic", emphasis.children.filterIsInstance<TextNode>().single().text)
    }

    @Test
    fun codeSpansAreParsed() {
        val doc = parse("run `code` now")
        val span = (doc.blocks[0] as ParagraphNode).inlines.filterIsInstance<CodeSpanNode>().single()
        assertEquals("code", span.code)
    }

    @Test
    fun aLinkSeparatesItsLabelFromItsDestination() {
        val doc = parse("see [the docs](https://example.com/very/long/path)")
        val link = (doc.blocks[0] as ParagraphNode).inlines.filterIsInstance<LinkNode>().single()
        assertEquals("https://example.com/very/long/path", link.destination)
        assertEquals("the docs", doc.text.substring(link.labelRange.start, link.labelRange.end))
        assertTrue(link.labelRange.length < link.range.length, "label and destination differ in length")
    }

    @Test
    fun anImageIsParsedWithItsAltText() {
        val doc = parse("![alt text](img.png)")
        val image = (doc.blocks[0] as ParagraphNode).inlines.filterIsInstance<ImageNode>().single()
        assertEquals("alt text", image.altText)
        assertEquals("img.png", image.destination)
    }

    @Test
    fun nestedListsAreParsedToTheirDepth() {
        val doc = parse("- one\n    - two\n        - three")
        val level1 = assertIs<ListNode>(doc.blocks[0])
        assertEquals(1, level1.children.size, "one item at the top level")

        val item1 = assertIs<ListItemNode>(level1.children[0])
        val level2 = item1.children.filterIsInstance<ListNode>().single()
        assertEquals(1, level2.children.size)

        val item2 = assertIs<ListItemNode>(level2.children[0])
        val level3 = item2.children.filterIsInstance<ListNode>().single()
        assertEquals(1, level3.children.size, "three levels deep")

        val item3 = assertIs<ListItemNode>(level3.children[0])
        assertEquals(
            "three",
            item3.children.filterIsInstance<ParagraphNode>().single().inlines
                .filterIsInstance<TextNode>().joinToString("") { it.text },
        )
    }

    @Test
    fun eachListLevelOccupiesItsOwnSourceRange() {
        val doc = parse("- one\n    - two\n        - three")
        val level1 = doc.blocks[0] as ListNode
        val level2 = (level1.children[0] as ListItemNode).children.filterIsInstance<ListNode>().single()
        val level3 = (level2.children[0] as ListItemNode).children.filterIsInstance<ListNode>().single()
        assertTrue(level1.range.overlaps(level2.range), "a nested list sits inside its parent")
        assertTrue(level2.range.overlaps(level3.range))
        assertEquals(31, level1.range.end)
        assertEquals(31, level3.range.end)
    }

    @Test
    fun orderedAndUnorderedListsAreDistinguished() {
        val doc = parse("1. one\n2. two")
        assertTrue(assertIs<ListNode>(doc.blocks[0]).ordered)

        val bullet = parse("- one")
        assertTrue(!assertIs<ListNode>(bullet.blocks[0]).ordered)
    }

    @Test
    fun aBlockQuoteIsParsed() {
        val doc = parse("> quoted text")
        val quote = assertIs<BlockQuoteNode>(doc.blocks[0])
        val inner = (quote.children[0] as ParagraphNode).inlines
            .filterIsInstance<TextNode>().joinToString("") { it.text }
        assertEquals("quoted text", inner.trim())
    }

    @Test
    fun aTableIsParsedWithItsRows() {
        val doc = parse("| a | b |\n|---|---|\n| 1 | 2 |")
        val table = assertIs<TableNode>(doc.blocks[0])
        assertTrue(table.hasHeader)
        assertEquals(2, table.children.size, "header plus one row")
    }

    @Test
    fun aTableHeaderCellHoldsInlineContent() {
        val doc = parse("| **head** | b |\n|---|---|\n| 1 | 2 |")
        val table = assertIs<TableNode>(doc.blocks[0])
        val header = assertIs<TableRowNode>(table.children[0])
        val cell = assertIs<TableCellNode>(header.cells[0])
        assertTrue(cell.inlines.any { it is EmphasisNode }, "inline emphasis inside a header cell")
    }

    @Test
    fun aThematicBreakIsParsed() {
        val doc = parse("---")
        assertIs<ThematicBreakNode>(doc.blocks[0])
    }

    @Test
    fun aFenceKeepsItsContentsOpaque() {
        val text = "```markdown\n# not a heading\n[[not a link]]\n```"
        val doc = parse(text)
        val fence = assertIs<CodeFenceNode>(doc.blocks[0])
        val content = fence.content(doc.text)
        assertEquals("# not a heading\n[[not a link]]\n", content)
        assertEquals(1, doc.blocks.size, "nothing inside the fence became a block")
        assertTrue(fence.inlines.isEmpty(), "and nothing inside became an inline node")
    }

    @Test
    fun aFenceContainingAFenceStaysLiteral() {
        val text = "````\n```\ninner\n```\n````"
        val doc = parse(text)
        val fence = assertIs<CodeFenceNode>(doc.blocks[0])
        assertTrue(fence.content(doc.text).contains("```"))
        assertEquals(1, doc.blocks.size)
    }

    @Test
    fun anUnclosedFenceRunsToTheEndOfTheDocument() {
        val doc = parse("```\nstill code")
        val fence = assertIs<CodeFenceNode>(doc.blocks[0])
        assertTrue(fence.content(doc.text).contains("still code"))
    }

    @Test
    fun anEmptyDocumentParsesToNoBlocks() {
        val doc = parse("")
        assertEquals(emptyList(), doc.blocks)
    }

    @Test
    fun aSingleNewlineDocumentDoesNotCollapse() {
        val doc = parse("\n")
        assertEquals(1, doc.blocks.size, "a lone newline is one empty block")
        val paragraph = assertIs<ParagraphNode>(doc.blocks[0])
        assertTrue(paragraph.inlines.isEmpty())
    }

    @Test
    fun aDocumentOfOnlyWhitespaceDoesNotCrash() {
        val doc = parse("   \n\n  \n")
        assertTrue(doc.blocks.all { it.range.end <= doc.text.length })
    }

    @Test
    fun aSingleNewlineDoesNotCrashTheRenderer() {
        val doc = parse("\n")
        assertNotNull(doc.blockAt(0))
        assertEquals(0, doc.blockStartAtOrBefore(0))
    }

    @Test
    fun everyNodeCarriesASourceRange() {
        val text = "# H\n\npara with **bold**\n\n- a\n- b\n\n> q\n\n| a |\n|---|\n| 1 |\n\n---\n\n```\nc\n```"
        val doc = parse(text)
        for (block in doc.allBlocks) {
            assertTrue(block.range.start >= 0, "${block.kind} start")
            assertTrue(block.range.end <= text.length, "${block.kind} end")
            assertTrue(block.range.end >= block.range.start, "${block.kind} ordering")
            assertEquals(
                block.range.end - block.range.start,
                text.substring(block.range.start, block.range.end).length,
                "${block.kind} range must match the source it covers",
            )
        }
    }

    @Test
    fun nestedChildrenStayWithinTheirParent() {
        val doc = parse("- item one\n    - nested\n- item two")
        val list = assertIs<ListNode>(doc.blocks[0])
        for (child in list.children) {
            assertTrue(
                child.range.start >= list.range.start && child.range.end <= list.range.end,
                "a child must sit inside its parent",
            )
        }
    }

    @Test
    fun inlineRangesSitInsideTheirBlock() {
        val doc = parse("text with **bold** inside")
        val paragraph = assertIs<ParagraphNode>(doc.blocks[0])
        for (inline in paragraph.inlines) {
            assertTrue(inline.range.start >= paragraph.range.start)
            assertTrue(inline.range.end <= paragraph.range.end)
        }
    }

    @Test
    fun aHostExtensionCanClaimInlineSyntaxTheLibraryHasNeverHeardOf() {
        val extension = object : SyntaxExtension {
            override val id = "fake"
            override fun recogniseInline(context: dev.fude.syntax.BlockContext) =
                listOf(InlineRange(context.range.start, context.range.start + 4))
        }
        val doc = IncrementalMarkdownParser(listOf(extension)).parse("abcd rest")
        val host = (doc.blocks[0] as ParagraphNode).inlines.filterIsInstance<HostInlineNode>()
        assertEquals(1, host.size)
        assertEquals("fake", host.single().extensionId)
    }
}
