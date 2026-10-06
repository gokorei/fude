package dev.fude.markdown

import dev.fude.core.InlineRange
import dev.fude.syntax.SyntaxExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
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
        assertTrue(level1.range.intersects(level2.range), "a nested list sits inside its parent")
        assertTrue(level2.range.intersects(level3.range))
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
    fun aFenceContainingEveryTroublingStringStaysLiteral() {
        // The three things that break live-preview renderers: a nested fence marker,
        // something that looks like a host dialect, and an image.
        val text = "````markdown\n```\n[[Wikilink]] and {{mention}} and ![img](x.png)\n````"
        val doc = parse(text)
        val fence = assertIs<CodeFenceNode>(doc.blocks[0])
        val content = fence.content(doc.text)

        assertEquals(1, doc.blocks.size, "nothing inside the fence became a block")
        assertTrue(fence.inlines.isEmpty(), "and nothing became an inline node")
        assertTrue(content.contains("```"), "the nested fence marker is literal")
        assertTrue(content.contains("[[Wikilink]]"), "the wikilink-looking string is literal")
        assertTrue(content.contains("{{mention}}"), "the host-dialect string is literal")
        assertTrue(content.contains("![img](x.png)"), "the image is literal")
    }

    @Test
    fun aFenceWithAnInfoStringIsStillAFence() {
        // "```markdown" opens a fence. Rejecting it because the language name is not
        // blank turns a fenced block into a paragraph followed by a heading.
        val doc = parse("```kotlin\nval x = 1\n```")
        assertEquals(1, doc.blocks.size)
        val fence = assertIs<CodeFenceNode>(doc.blocks[0])
        assertEquals("kotlin", fence.info)
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

    // --------------------------------------------------------- the `---` family

    /**
     * The three constructs that share the character sequence `---`, and the one that
     * makes this worth testing carefully.
     *
     * 1. A table's header separator row, `|---|---|`. Table syntax, always.
     * 2. A thematic break on its own. A horizontal rule.
     * 3. A setext heading underline. **Not implemented** — see
     *    [aDashRunAfterAParagraphIsAThematicBreakAndNotASetextHeading].
     *
     * The bug this pins down was a bare `---` satisfying the table delimiter test,
     * because that test never required a pipe. So a thematic break following a table
     * was eaten as one more table row, and the table's range grew to cover it.
     */
    @Test
    fun aThematicBreakAfterATableIsNotAbsorbedIntoTheTable() {
        val text = """
            | a | b |
            |---|---|
            | 1 | 2 |

            ---

            after
        """.trimIndent()

        val doc = parse(text)
        assertEquals(
            listOf(BlockKind.TABLE, BlockKind.THEMATIC_BREAK, BlockKind.PARAGRAPH),
            doc.blocks.map { it.kind },
            "table, then a horizontal rule, then a paragraph",
        )

        val table = assertIs<TableNode>(doc.blocks[0])
        assertEquals(
            "| a | b |\n|---|---|\n| 1 | 2 |",
            text.substring(table.range.start, table.range.end),
            "the table must cover its own rows and nothing else — not even its " +
                "trailing newline, which belongs to the gap between blocks",
        )
        assertTrue(
            table.range.end <= text.indexOf("---", table.range.end),
            "the table must end before the thematic break",
        )

        val rule = assertIs<ThematicBreakNode>(doc.blocks[1])
        assertEquals("---", text.substring(rule.range.start, rule.range.end))
    }

    @Test
    fun aThematicBreakOnItsOwnStillParses() {
        val doc = parse("before\n\n---\n\nafter")
        assertTrue(
            doc.blocks.any { it is ThematicBreakNode },
            "a bare --- is a horizontal rule; saw ${doc.blocks.map { it.kind }}",
        )
    }

    @Test
    fun aTableHeaderSeparatorRowIsStillTableSyntax() {
        val doc = parse("| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals(listOf(BlockKind.TABLE), doc.blocks.map { it.kind })
        assertIs<TableNode>(doc.blocks.single())
    }

    /**
     * Setext headings are not implemented, so `---` after a paragraph is a
     * thematic break and the paragraph ends.
     *
     * Stated as a test rather than left implicit, because it is the ambiguity a
     * careless fix walks into: reading "a table followed by `---` should stop
     * absorbing the break" and concluding that `---` belongs to the paragraph
     * above it is setext heading semantics, which Fude does not have. If that ever
     * changes, this is where it changes.
     */
    @Test
    fun aDashRunAfterAParagraphIsAThematicBreakAndNotASetextHeading() {
        val doc = parse("a paragraph\n---\n")
        assertEquals(
            listOf(BlockKind.PARAGRAPH, BlockKind.THEMATIC_BREAK),
            doc.blocks.map { it.kind },
        )
    }

    /**
     * A delimiter row is a row of *cells*, so it must contain a pipe.
     *
     * Pinned directly because it is the root cause and it is invisible from the
     * table's own tests: with the pipe requirement dropped, `---`, `--` and `- - -`
     * all pass as delimiter rows and swallow whatever follows a table.
     */
    @Test
    fun aDelimiterRowMustContainAPipe() {
        for (text in listOf("---", "--", "- - -", "  ---  ")) {
            val blocks = parse("| a |\n${text}\n").blocks
            assertTrue(
                blocks.none { it is TableNode },
                "'$text' has no pipe, so it is not a table and must not become a TableNode; " +
                    "saw ${blocks.map { it.kind }}",
            )
        }
        // And the ones long enough to be a rule are rules rather than paragraphs.
        // `- - -` was left out of this loop for a long time with a comment blaming
        // `isThematicBreak` for "requiring every character to be a dash". No such check
        // existed: the cause was set equality over a set that included spaces. Fixed,
        // and `- - -` is a rule — see aSpacedThematicBreakIsNotAListItem.
        for (text in listOf("---", "  ---  ", "- - -")) {
            assertTrue(
                parse("| a |\n${text}\n").blocks.any { it is ThematicBreakNode },
                "'$text' is three dashes or more and so is a horizontal rule",
            )
        }
        // Two dashes are not: CommonMark requires at least three, and treating a
        // short run as a rule would turn a paragraph of dashes into one.
        assertEquals(
            BlockKind.PARAGRAPH,
            parse("| a |\n--\n").blocks.last().kind,
        )
    }

    @Test
    fun aTableDoesNotClaimTheBlankLinesAfterIt() {
        //            0123456 7890...
        val text = "| a |\n|---|\n| 1 |\n\n\nnext"
        val table = assertIs<TableNode>(parse(text).blocks.first())
        assertEquals(17, table.range.end, "the table ends at its last row's content")
        assertEquals("| a |\n|---|\n| 1 |", text.substring(0, table.range.end))
    }

    /**
     * The same over-extension, asked of every block type that scans forward for a
     * terminator. A table was found this way, and the underlying class of bug is
     * "the scanner consumed blank lines on its way to giving up", so each of these
     * is asked directly whether it can reach past its own content.
     *
     * An **unclosed** fence is deliberately absent: it runs to the end of the
     * document, which is the correct reading of unterminated Markdown and is already
     * pinned by `anUnclosedFenceRunsToTheEndOfTheDocument`. It was in this list
     * while the table bug was being diagnosed and failed for the right reason.
     */
    @Test
    fun noBlockTypeAbsorbsTheBlankLinesAfterIt() {
        val cases = mapOf(
            "a table" to "| a |\n|---|\n| 1 |",
            "a fence" to "```\ncode\n```",
            "a block quote" to "> quoted",
            "a list" to "- one\n- two",
            "a thematic break" to "---",
        )
        for ((label, block) in cases) {
            val text = "$block\n\n\ntail"
            val owner = parse(text).blocks.first()
            assertTrue(
                owner.range.end <= text.indexOf("tail") - 2,
                "$label claimed [${owner.range.start}, ${owner.range.end}) of " +
                    "\"${text.take(owner.range.end)}\", reaching past its own content",
            )
        }
    }

    /**
     * `blockAt` is half-open like `InlineRange.contains`: a block `[s, e)` owns `s`
     * and not `e`, so abutting blocks partition the document exactly once and a
     * gap offset (terminator, blank line) belongs to no block. An over-extended
     * range would silently misroute every offset inside it to the wrong block.
     */
    @Test
    fun blockAtResolvesCorrectlyAroundATableFollowedByABreak() {
        val text = "| a | b |\n|---|---|\n| 1 | 2 |\n\n---\n\nafter"
        val doc = parse(text)

        for (offset in 0..text.length) {
            val expected = doc.blocks.firstOrNull { offset >= it.range.start && offset < it.range.end }?.kind
            assertEquals(
                expected,
                doc.blockAt(offset)?.kind,
                "offset $offset resolved to the wrong block",
            )
        }
val ruleOffset = text.lastIndexOf("---")   // not the table's own separator row
        assertTrue(
            doc.blockAt(ruleOffset) is ThematicBreakNode,
            "an offset on the break must resolve to the break, not the table",
        )
    }

    // ------------------------------------------------ block-level host extensions

    /**
     * The wiring test, and the only one that matters for this feature.
     *
     * `SyntaxExtensionTest` in `:fude-core` calls `recogniseBlocks` directly and
     * passes — which was true for as long as the parser never called it at all. A
     * tested unit with untested wiring: the suite was green and the feature was
     * dead. So this goes through `IncrementalMarkdownParser`, which is the only
     * place the question "does anything invoke it?" can be asked.
     */
    @Test
    fun aHostExtensionCanClaimABlockTheLibraryWouldOtherwiseReadAsMarkdown() {
        val text = "> [!note]\n> Careful here.\n\nA plain paragraph.\n"
        val withoutExtension = parse(text).blocks
        assertEquals(
            BlockKind.BLOCK_QUOTE,
            withoutExtension.first().kind,
            "with no extension the library reads it as a block quote, as it always did",
        )

        val withExtension = parse(text, listOf(CalloutSyntax())).blocks
        val host = assertIs<HostBlockNode>(withExtension.first())
        assertEquals("callout", host.extensionId)
        assertEquals(
            "> [!note]\n> Careful here.\n",
            text.substring(host.range.start, host.range.end),
            "the extension's range is the block's extent",
        )
        assertEquals(
            listOf(BlockKind.HOST_DEFINED, BlockKind.PARAGRAPH),
            withExtension.map { it.kind },
            "the construct is claimed, and the paragraph after it is still Markdown",
        )
    }

    @Test
    fun anExtensionMatchWinsOverTheBuiltInRule() {
        // A callout is a block quote to Markdown. If a built-in rule won, no host
        // could ever redefine anything the library already understands, and the
        // extension point would be limited to syntax the library has never seen.
        val text = "> [!warning]\n> Careful.\n"
        assertEquals(
            listOf(BlockKind.HOST_DEFINED),
            parse(text, listOf(CalloutSyntax())).blocks.map { it.kind },
        )
        // And an extension that declines leaves the built-in reading alone.
        assertEquals(
            listOf(BlockKind.BLOCK_QUOTE),
            parse("> an ordinary quote\n", listOf(CalloutSyntax())).blocks.map { it.kind },
        )
    }

    /**
     * `ownsTerminator` decides whether the closing delimiter is inside the block.
     *
     * When an extension reports content only and says so, the library resumes past
     * the end of that line so the delimiter is consumed rather than rescanned — which
     * is what stops a construct whose own terminator it excluded from matching itself
     * again on the next iteration.
     */
    @Test
    fun ownsTerminatorIsHonouredInBothDirections() {
        val owning = ":::warning\nbe careful\n:::\n\nafter\n"
        val owningBlocks = parse(owning, listOf(FencedSyntax(ownsTerminator = true))).blocks
        assertEquals(BlockKind.HOST_DEFINED, owningBlocks.first().kind)
        assertEquals(
            ":::warning\nbe careful\n:::",
            owning.substring(0, owningBlocks.first().range.end),
            "owning the terminator puts the closing fence inside the block",
        )
        assertEquals(
            BlockKind.PARAGRAPH,
            owningBlocks[1].kind,
            "the closing fence was consumed, so the next block is the paragraph",
        )

        val disowning = ":::warning\nbe careful\n:::\n\nafter\n"
        val disowningBlocks = parse(disowning, listOf(FencedSyntax(ownsTerminator = false))).blocks
        val host = assertIs<HostBlockNode>(disowningBlocks.first())
        assertEquals(
            ":::warning\nbe careful",
            disowning.substring(host.range.start, host.range.end),
            "the range covers content only",
        )
        assertFalse(
            host.ownsTerminator,
            "and the node says so, rather than the caller having to remember",
        )
        assertEquals(
            BlockKind.PARAGRAPH,
            disowningBlocks[1].kind,
            "the unowned terminator must not be rescanned into the same construct",
        )
    }

    /**
     * The incremental path has to behave like the full one, or a callout survives
     * being loaded and then vanishes or doubles on the next keystroke somewhere else
     * in the document.
     */
    @Test
    fun anExtensionMatchSurvivesAnEditElsewhereAndIsRerecognisedWhenItselfEdited() {
        val text = "> [!note]\n> Careful.\n\nFirst paragraph.\n\nSecond paragraph.\n"
        val parser = IncrementalMarkdownParser(listOf(CalloutSyntax()))
        parser.parse(text)
        assertEquals(BlockKind.HOST_DEFINED, parser.parse(text).blocks.first().kind)

        // An edit far below: the callout must survive untouched.
        val tail = text.indexOf("Second paragraph")
        val editedBelow = text.substring(0, tail) + "Second paragraph changed.\n"
        val afterBelow = parser.reparse(editedBelow, dev.fude.core.Insert(tail, ""))
        assertEquals(
            BlockKind.HOST_DEFINED,
            afterBelow.blocks.first().kind,
            "an edit elsewhere must not cost the callout",
        )

        // An edit inside it: it must be recognised again, not dropped.
        val inside = text.indexOf("Careful")
        val editedInside = text.substring(0, inside) + "Careful indeed.\n" + text.substring(text.indexOf(".\n", inside) + 2)
        val afterInside = parser.reparse(editedInside, dev.fude.core.Insert(inside, " indeed"))
        assertEquals(
            BlockKind.HOST_DEFINED,
            afterInside.blocks.first().kind,
            "editing the callout's body must re-recognise it",
        )
        assertEquals(
            IncrementalMarkdownParser(listOf(CalloutSyntax())).parse(editedInside).blocks.map { it.kind },
            afterInside.blocks.map { it.kind },
            "and the incremental result must match a full parse",
        )
    }

    @Test
    fun noExtensionRegisteredMeansNoHostDefinedBlocks() {
        val blocks = parse("> [!note]\n> Careful.\n").blocks
        assertTrue(
            blocks.none { it is HostBlockNode },
            "the boundary that gives the extension point its meaning: with nothing " +
                "registered the library knows nothing host-specific",
        )
    }

    /** `> [!note]` and its body, which is what the demo ships. */
    private class CalloutSyntax : SyntaxExtension {
        override val id: String = "callout"

        override fun recogniseBlocks(context: dev.fude.syntax.BlockContext): List<dev.fude.syntax.BlockMatch> {
            val text = context.content.toString()
            if (!text.startsWith("> [!")) return emptyList()
            return listOf(
                dev.fude.syntax.BlockMatch(
                    range = InlineRange(context.range.start, context.range.end),
                    ownsTerminator = false,
                ),
            )
        }
    }

    /** A `:::`-fenced construct, for pinning [dev.fude.syntax.BlockMatch.ownsTerminator]. */
    private class FencedSyntax(private val ownsTerminator: Boolean) : SyntaxExtension {
        override val id: String = "fenced"

        override fun recogniseBlocks(context: dev.fude.syntax.BlockContext): List<dev.fude.syntax.BlockMatch> {
            val text = context.content.toString()
            if (!text.startsWith(":::")) return emptyList()
            val closing = text.lastIndexOf(":::")
            if (closing <= 0) return emptyList()
            val end = if (ownsTerminator) {
                context.range.start + closing + 3
            } else {
                context.range.start + text.lastIndexOf('\n', closing)
            }
            return listOf(dev.fude.syntax.BlockMatch(InlineRange(context.range.start, end), ownsTerminator))
        }
    }

    // ------------------------------------------- unbracketed tables (GFM)

    /**
     * A table does not need its outer pipes.
     *
     * Both the header and the delimiter row are accepted without them, which is what
     * GFM specifies. `DifferentialConformanceTest` found this was missing and the
     * fixtures here pin it, including the parts that were easy to get wrong: cell
     * ranges must still index the author's own text, with no column padding inside.
     */
    @Test
    fun aTableNeedsNoOuterPipes() {
        val text = "A | B\n--- | ---\n1 | 2\n"
        val table = assertIs<TableNode>(parse(text).blocks.single())
        assertTrue(table.hasHeader)
        assertEquals(2, table.children.size, "header plus one row")

        val cells = table.children
            .filterIsInstance<TableRowNode>()
            .flatMap { row -> row.cells }
            .map { cell -> text.substring(cell.range.start, cell.range.end) }
        assertEquals(listOf("A", "B", "1", "2"), cells, "no column padding inside a cell range")
    }

    @Test
    fun aTableNeedsNoOuterPipesAndSupportsAlignmentColons() {
        val text = "a | b | c\n:-- | :-: | --:\n1 | 2 | 3\n"
        val table = assertIs<TableNode>(parse(text).blocks.single())
        assertEquals(2, table.children.size)
        assertEquals(
            listOf("a", "b", "c", "1", "2", "3"),
            table.children
                .filterIsInstance<TableRowNode>()
                .flatMap { row -> row.cells }
                .map { cell -> text.substring(cell.range.start, cell.range.end) },
        )
    }

    /** GFM allows up to three leading spaces before a table row. */
    @Test
    fun anIndentedTableIsStillATable() {
        val text = "  A | B  \n  --- | ---  \n  1 | 2  \n"
        val table = assertIs<TableNode>(parse(text).blocks.single())
        assertEquals(
            listOf("A", "B", "1", "2"),
            table.children
                .filterIsInstance<TableRowNode>()
                .flatMap { row -> row.cells }
                .map { cell -> text.substring(cell.range.start, cell.range.end) },
            "indentation is not part of any cell",
        )
    }

    /**
     * The delimiter row is what decides, so prose with a pipe in it stays prose.
     *
     * A fixed threshold of pipes would turn this into a table, which is the failure
     * mode the column-count comparison exists to prevent.
     */
    @Test
    fun aPipeInProseWithoutADelimiterRowIsStillProse() {
        assertEquals(listOf(BlockKind.PARAGRAPH), parse("just prose with a | pipe\nsecond line\n").blocks.map { it.kind })
    }

    @Test
    fun mismatchedColumnCountsAreNotATable() {
        assertEquals(
            listOf(BlockKind.PARAGRAPH),
            parse("a | b\n--- | --- | ---\n").blocks.map { it.kind },
            "two header columns, three delimiter cells",
        )
        assertEquals(
            listOf(BlockKind.PARAGRAPH),
            parse("a | b | c\n--- | ---\n").blocks.map { it.kind },
            "three header columns, two delimiter cells",
        )
    }

    /** An escaped pipe is content, so it does not make a column. */
    @Test
    fun anEscapedPipeDoesNotBecomeACellBoundary() {
        val text = "escaped \\| pipe\n--- | ---\n1 | 2\n"
        assertEquals(
            listOf(BlockKind.PARAGRAPH),
            parse(text).blocks.map { it.kind },
            "zero real pipes in the header against two in the delimiter",
        )
    }

    // -------------------------------------------------- block quotes

    /**
     * A blank line ends a block quote.
     *
     * `parseBlockQuote` used to step over the line terminator with a skip that consumed
     * *every* consecutive newline, so the blank line was swallowed and `> a` / `> b`
     * became one quote — a paragraph break that visibly did nothing. Found by
     * `DifferentialConformanceTest` against the reference parser.
     */
    @Test
    fun aBlankLineEndsABlockQuote() {
        assertEquals(
            listOf(BlockKind.BLOCK_QUOTE, BlockKind.BLOCK_QUOTE),
            parse("> a\n\n> b\n").blocks.map { it.kind },
        )
    }

    /** Without a blank line they are one quote, and must stay one. */
    @Test
    fun consecutiveQuoteLinesAreOneBlock() {
        assertEquals(listOf(BlockKind.BLOCK_QUOTE), parse("> a\n> b\n").blocks.map { it.kind })
    }

    /** A quote must not claim the gap that follows it, like every other block. */
    @Test
    fun aQuoteDoesNotClaimTheBlankLineAfterIt() {
        val text = "> quoted\n\nnext paragraph\n"
        val blocks = parse(text).blocks
        val quote = assertIs<BlockQuoteNode>(blocks.first())
        assertEquals("> quoted", text.substring(quote.range.start, quote.range.end))
        assertTrue(
            blocks.last().range.start >= quote.range.end,
            "the gap between blocks belongs to neither",
        )
    }

    /**
     * A block quote's marker is `>` and one *optional* space. The space is optional.
     *
     * `parseBlockQuote` computed its content start as a hardcoded `cursor + 2`, which
     * is right for `> x` and wrong for every quote written without the space — the form
     * a hand-typed quote often takes. `isBlockQuote` has always accepted it, so `>quote`
     * parsed as content `"uote"`: the first character silently gone from the tree, so
     * the caret could not reach it and decoration never touched it. Nothing threw.
     *
     * `contentStart` is now measured from the line. Leading indentation counts too,
     * because it is a document offset and an indented quote's content does not start at
     * column two.
     */
    @Test
    fun aQuoteWithoutASpaceAfterTheMarkerKeepsEveryCharacter() {
        for (text in listOf(">quote", ">no space here", "  >indented")) {
            assertEquals(
                text.trimStart().removePrefix(">").trim(),
                quoteText(parse(text)),
                "every character after the marker survives, for \"$text\"",
            )
        }
        // Two markers open a *nested* quote, which is the correct CommonMark reading, so
        // this one is a structure case rather than a content case. Pinned here because it
        // is the input where a marker-width fix that stopped too eagerly would look fine.
        assertIs<BlockQuoteNode>(parse(">>double").blocks.single())
        assertEquals(
            "double",
            contentOf(assertIs<BlockQuoteNode>(parse(">>double").blocks.single()), ">>double"),
        )
    }

    /**
     * `> quote` is unchanged, because the fix measured the marker rather than replacing it.
     *
     * Stated explicitly: a fix for the unspaced form that dropped the space from the
     * spaced one would be a different bug, and the padded form is far more common.
     */
    @Test
    fun aQuoteWithASpaceAfterTheMarkerIsUnchanged() {
        val doc = parse("> quote")
        assertEquals("quote", quoteText(doc), "the single space is padding, not content")
        assertEquals("quote", quoteText(parse(">  quote")), "two spaces: one padding, one content")
        assertEquals(
            "> quote",
            doc.text.substring(doc.blocks[0].range.start, doc.blocks[0].range.end),
            "and the block's own range still covers the marker",
        )
    }

    /**
     * Only the **outer** marker is stripped, so a nested quote survives.
     *
     * `"> > nested"` yields `" > nested"`, which is itself a quote line, and that is
     * what makes the nesting real. A marker-width calculation that looped — or that
     * trimmed the whole line — would strip both and flatten `"> > nested"` into the
     * bare text `"nested"`, which is a different document. Pinned because it is the
     * case a careless version of this fix walks straight into.
     */
    @Test
    fun nestedQuotesKeepTheirInnerMarker() {
        val text = "> > nested"
        val outer = assertIs<BlockQuoteNode>(parse(text).blocks.single())
        val inner = assertIs<BlockQuoteNode>(outer.children.single())
        assertEquals(
            "nested",
            contentOf(inner, text),
            "the inner marker is stripped by the inner quote, not eaten by the outer one",
        )
        // And three deep still works.
        val deep = "> > > deep"
        val outer3 = assertIs<BlockQuoteNode>(parse(deep).blocks.single())
        val mid = assertIs<BlockQuoteNode>(outer3.children.single())
        val inner3 = assertIs<BlockQuoteNode>(mid.children.single())
        assertEquals("deep", contentOf(inner3, deep))
    }

    /** An indented quote's content starts after its own marker, not at a fixed column. */
    @Test
    fun anIndentedQuoteMeasuresFromItsOwnMarker() {
        for (lead in listOf("", " ", "  ", "   ")) {
            val text = "${lead}>quoted"
            val quote = assertIs<BlockQuoteNode>(parse(text).blocks.single())
            assertEquals(
                "quoted",
                quoteText(parse(text)),
                "${lead.length} spaces of indent must not push the content start",
            )
            assertTrue(
                quote.children.single().let { it.range.start >= quote.range.start },
                "content must begin inside the quote",
            )
        }
    }

    /** Every character of a quote's content, read back out of the document by offset. */
    private fun quoteText(doc: ParsedDocument): String =
        contentOf(assertIs<BlockQuoteNode>(doc.blocks.single()), doc.text)

    /**
     * The text one quote node ultimately holds, descending through nested quotes.
     *
     * Descends because `">>x"` is a quote whose content is *another quote*, so the text
     * lives at the bottom of the nesting rather than one level down.
     */
    private fun contentOf(quote: BlockQuoteNode, text: String): String {
        val nested = quote.children.filterIsInstance<BlockQuoteNode>().singleOrNull()
        if (nested != null) return contentOf(nested, text)
        val inner = quote.children.filterIsInstance<ParagraphNode>().single()
        return text.substring(inner.range.start, inner.range.end).trim()
    }

    // ------------------------------------------ spaced thematic breaks

    /**
     * `- - -` is a thematic break, not a list item.
     *
     * `isThematicBreak` compared `line.trim().toSet()` against `setOf('-')`, and the set
     * includes spaces, so the set for `"- - -"` is `{'-', ' '}` and the test failed. The
     * next clause of the same predicate — `all { … || it == ' ' }` — shows spaces were
     * meant to be allowed, so the set test was silently forbidding what the character
     * test permitted.
     *
     * The consequence was not merely unstyled: it was the wrong **shape**. A list of
     * bullets with empty items instead of a horizontal rule.
     */
    @Test
    fun aSpacedThematicBreakIsNotAListItem() {
        for (text in listOf("- - -", "* * *", "_ _ _", "-  -  -", "- - - - -")) {
            assertEquals(
                listOf(BlockKind.THEMATIC_BREAK),
                parse(text).blocks.map { it.kind },
                "\"$text\" is three or more matching markers with spaces between them",
            )
        }
    }

    /**
     * CommonMark: where a thematic break and a list item are both possible, the break
     * wins. Asserted rather than assumed — the two predicates both accept `"- - -"`, so
     * the outcome is decided entirely by branch order in `parseBlockAt`, and reversing
     * that order later would silently turn every spaced rule back into a list.
     */
    @Test
    fun aThematicBreakTakesPrecedenceOverAListItem() {
        for (text in listOf("- - -", "* * *")) {
            assertTrue(
                parse(text).blocks.none { it is ListNode },
                "\"$text\" is ambiguous between the two and must not become a list; " +
                    "saw ${parse(text).blocks.map { it.kind }}",
            )
        }
        // A genuine list item is still a list item: the fix must not over-match.
        for (text in listOf("- one", "* one", "- one\n- two", "1. one", "- [ ] todo")) {
            assertEquals(
                listOf(BlockKind.LIST),
                parse(text).blocks.map { it.kind },
                "\"$text\" is a list and must stay one",
            )
        }
    }

    /**
     * Tabs may separate the markers too, which CommonMark allows and the old predicate
     * rejected twice over: `trimmed.length < 3` counted a tab as one character, and the
     * character test did not allow it at all.
     */
    @Test
    fun aTabSeparatedThematicBreakIsStillABreak() {
        for (text in listOf("-\t-\t-", "*\t*\t*", "_\t_\t_", "-\t- -")) {
            assertEquals(
                listOf(BlockKind.THEMATIC_BREAK),
                parse(text).blocks.map { it.kind },
                "\"$text\" is a break under CommonMark, which treats a tab as whitespace here",
            )
        }
    }

    /** The unspaced forms are untouched, and a short run is still not a rule. */
    @Test
    fun theUnspacedBreaksAreUnchangedAndShortRunsAreStillProse() {
        for (text in listOf("---", "***", "___", "-----", "  ---  ")) {
            assertEquals(
                listOf(BlockKind.THEMATIC_BREAK),
                parse(text).blocks.map { it.kind },
                "\"$text\" behaves as before",
            )
        }
        for (text in listOf("--", "**", "__", "a - - b", "x - y -", "a- - -", "**bold**", "-- x")) {
            assertEquals(
                listOf(BlockKind.PARAGRAPH),
                parse(text).blocks.map { it.kind },
                "\"$text\" is not three matching markers, so it is prose",
            )
        }
        // A real list item is still a list item, and emphasis is still emphasis. Both
        // are strings the new predicate could plausibly have started matching.
        assertEquals(listOf(BlockKind.LIST), parse("- one").blocks.map { it.kind })
        assertEquals(listOf(BlockKind.LIST), parse("- x -").blocks.map { it.kind }, "'- x -' is one item")
        assertEquals(
            listOf(BlockKind.PARAGRAPH),
            parse("***bold***").blocks.map { it.kind },
            "emphasis is not a thematic break: 'bold' is not a marker",
        )
    }

    // -------------------------------------------------- tab-indented lists

    /**
     * A tab-indented list item's inline content starts after its marker, in characters.
     *
     * `indentOf` counts a tab as four columns, which is correct — CommonMark measures
     * indentation in columns, and that is what decides whether a line continues a list
     * item. It is not correct as a character offset. The two were used interchangeably
     * at `contentStart = cursor + indent + markerLength(line)`, which overshoots by three
     * per tab, so `"\t- item"` put its content at offset 6 of a 7-character item: one
     * past the end, holding `"m"`.
     *
     * Every invariant in `BlockRangePropertyTest` held while that was true. The ranges
     * stayed disjoint, stayed inside the document and ended on no terminator — they just
     * covered the wrong text. So this asserts on **content**, not on ranges.
     */
    @Test
    fun aTabIndentedListItemCoversExactlyItsOwnText() {
        val text = "\t- item\n"
        val list = assertIs<ListNode>(parse(text).blocks.single())
        val item = assertIs<ListItemNode>(list.children.single())

        assertEquals("item", itemText(parse(text), item), "the content is the text after the marker")
        assertEquals(
            InlineRange(3, 7),
            item.inlines.single().range,
            "offset 3 is the 'i' of \"item\", not one past the end of the item",
        )
        assertEquals(
            "item",
            text.substring(item.inlines.single().range.start, item.inlines.single().range.end),
            "and the range indexes the author's own characters",
        )
    }

    /** The column count is still what `indent` reports, and it is still 4 for a tab. */
    @Test
    fun aTabIsStillFourColumnsForNestingEvenThoughItIsOneCharacterForOffsets() {
        val item = assertIs<ListItemNode>(
            assertIs<ListNode>(parse("\t- item\n").blocks.single()).children.single(),
        )
        assertEquals(4, item.indent, "indent is a column count, and a tab is four columns")
        assertEquals(
            "\t- item",
            "\t- item".substring(item.range.start, item.range.end),
            "and the item's own range still covers its marker",
        )
    }

    /** A tab-indented item nested inside another list, where the offsets compound. */
    @Test
    fun aTabIndentedListItemNestedInAListAlsoCoversItsOwnText() {
        val text = "- outer\n\t- inner\n"
        val outer = assertIs<ListItemNode>(
            assertIs<ListNode>(parse(text).blocks.single()).children.first(),
        )
        val innerList = outer.children.filterIsInstance<ListNode>().single()
        val inner = assertIs<ListItemNode>(innerList.children.single())
        assertEquals("inner", itemText(parse(text), inner), "the nested item's content is intact")
        assertEquals(11, inner.inlines.single().range.start, "offset 11 is the 'i' of \"inner\"")
    }

    /** A space-indented list is unchanged, so the split did not disturb the common case. */
    @Test
    fun spaceIndentedListItemsAreUnchanged() {
        val text = "  - item\n"
        val item = assertIs<ListItemNode>(
            assertIs<ListNode>(parse(text).blocks.single()).children.single(),
        )
        assertEquals("item", itemText(parse(text), item))
        assertEquals(4, item.inlines.single().range.start)
    }

    private fun itemText(doc: ParsedDocument, item: ListItemNode): String =
        doc.text.substring(item.inlines.first().range.start, item.inlines.last().range.end)

    // ------------------------------------------------- code span delimiters

    /**
     * A code span's closing run must match the opening run.
     *
     * The search was `text.indexOf('`', cursor + 1)` — the next backtick of *any* length.
     * So `` `` a ` b `` `` paired the first two backticks as an empty span and read the
     * interior as text, giving three `CodeSpanNode`s and three text runs where CommonMark
     * gives one span. The two spans carried visible backgrounds computed from ranges that
     * were wrong, so the renderer showed two stray highlighted empty regions.
     *
     * `runLengthAt` / `findClosingRun` already implemented exactly this rule for `*`, `_`
     * and `~`. Code spans predate them and were never migrated; this removes the
     * hand-rolled special case rather than adding a second rule beside it.
     */
    @Test
    fun aCodeSpanIsClosedByAMatchingBacktickRun() {
        val text = "``a ` b``"
        val paragraph = assertIs<ParagraphNode>(parse(text).blocks.single())
        val spans = paragraph.inlines.filterIsInstance<CodeSpanNode>()

        assertEquals(1, spans.size, "one delimiter pair, one code span; saw ${paragraph.inlines.map { it::class.simpleName }}")
        assertEquals("a ` b", spans.single().code, "the content includes the inner backtick")
        assertEquals(
            InlineRange(0, text.length),
            spans.single().range,
            "and the span covers the delimiters, which the decorator needs",
        )
    }

    /** An unmatched run stays literal text rather than pairing with a shorter run. */
    @Test
    fun anUnmatchedBacktickRunStaysLiteralText() {
        for (text in listOf("`` a", "a ` b", "``", "text with ` one backtick")) {
            val paragraph = assertIs<ParagraphNode>(parse(text).blocks.single())
            assertTrue(
                paragraph.inlines.none { it is CodeSpanNode },
                "\"$text\" has no matching closing run, so nothing may become a code span",
            )
            assertEquals(
                text,
                text.substring(paragraph.range.start, paragraph.range.end),
                "and the text survives untouched",
            )
        }
    }

    /** A single-backtick span is unchanged, which is the overwhelmingly common case. */
    @Test
    fun singleBacktickCodeSpansAreUnchanged() {
        val text = "run `code` now"
        val span = (parse(text).blocks[0] as ParagraphNode).inlines.filterIsInstance<CodeSpanNode>().single()
        assertEquals("code", span.code)
        assertEquals(InlineRange(4, 10), span.range)

        // A backtick inside a single-backtick span has no escape in CommonMark either:
        // the run is matched by length, so this pairs the outer two.
        val two = assertIs<ParagraphNode>(parse("`a` and `b`").blocks.single())
        assertEquals(2, two.inlines.filterIsInstance<CodeSpanNode>().size)
    }
}
