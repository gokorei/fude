package dev.fude.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The conformance corpus, pinning every row of `docs/conformance.md`.
 *
 * The document is the stance; this is the enforcement. Without it the two drift, and
 * a stance nobody checks is a comment that happens to be long.
 *
 * Each case carries the verdict from the document — supported, deliberately
 * unsupported, or a known gap — so a reader can tell an intentional position from an
 * oversight without opening the prose. The corpus is deliberately small and readable:
 * every line is a construct somebody had to decide about.
 *
 * Known gaps are asserted too. When one is fixed, this test fails and the fix has to
 * say which document row it corresponds to, which is the only way that count can
 * reliably go down.
 */
class ConformanceCorpusTest {

    private fun kinds(text: String): List<BlockKind> =
        IncrementalMarkdownParser().parse(text).blocks.map { it.kind }

    // ------------------------------------------------ correct but surprising

    @Test
    fun atxHeadingEdgeCases() {
        // CommonMark, not accidents. See "The surprising ones" in the document.
        assertEquals(listOf(BlockKind.PARAGRAPH), kinds("#Title"), "no space after #")
        assertEquals(listOf(BlockKind.PARAGRAPH), kinds("####### s"), "seven hashes is not a heading")
        assertEquals(listOf(BlockKind.HEADING), kinds("#"), "a bare # is an empty heading")
        assertEquals(listOf(BlockKind.HEADING), kinds("###### s"), "six hashes is a heading")
        assertEquals(listOf(BlockKind.HEADING), kinds("## Title ##"), "closing hashes are allowed")
    }

    @Test
    fun listEdgeCases() {
        assertEquals(listOf(BlockKind.LIST), kinds("- a\n\n- b\n"), "a blank line makes a list loose, not two lists")
        assertEquals(listOf(BlockKind.LIST), kinds("1) one"), "')' is an ordered marker as well as '.'")
        assertEquals(listOf(BlockKind.LIST), kinds("- [ ] todo\n- [x] done"), "task items are list items")
    }

    // ------------------------------------------- deliberately unsupported

    @Test
    fun setextHeadingsAreUnsupportedAndDashIsAlwaysAThematicBreak() {
        assertEquals(listOf(BlockKind.PARAGRAPH), kinds("Title\n=====\n"), "setext H1 unsupported")
        // The consequence other work depends on: `---` after a paragraph is a rule,
        // never a setext H2. Block-extent logic and windowing both rely on this.
        assertEquals(
            listOf(BlockKind.PARAGRAPH, BlockKind.THEMATIC_BREAK),
            kinds("Title\n-----\n"),
            "setext H2 unsupported, and --- stays a thematic break",
        )
    }

    @Test
    fun autolinksAreUnsupported() {
        val paragraph = assertIs<ParagraphNode>(
            IncrementalMarkdownParser().parse("<https://example.com>").blocks.single(),
        )
        assertEquals(
            emptyList(),
            paragraph.inlines.filterIsInstance<LinkNode>(),
            "no autolink detection: <https://…> stays literal text",
        )
    }

    @Test
    fun htmlIsShippedLiterallyAndNeverRendered() {
        // Deliberate, and argued in the document: rendering host-supplied HTML would
        // make Fude evaluate arbitrary markup in a component whose job is Markdown.
        val block = IncrementalMarkdownParser().parse("<div>\nhi\n</div>").blocks.single()
        val text = block.let { "<div>\nhi\n</div>".substring(it.range.start, it.range.end) }
        assertEquals("<div>\nhi\n</div>", text, "the tags are content, not markup")

        val inlineText = "a <b>bold</b> word"
        val inlineBlock = IncrementalMarkdownParser().parse(inlineText).blocks.single()
        assertEquals(BlockKind.PARAGRAPH, inlineBlock.kind)
        assertEquals(
            inlineText,
            inlineText.substring(inlineBlock.range.start, inlineBlock.range.end),
            "inline tags survive as text rather than being interpreted",
        )
    }

    // ------------------------------------------------------ known gaps

    @Test
    fun lazyBlockquoteContinuationIsAKnownGap() {
        // KNOWN GAP, and an inconsistency rather than a missing feature: lazy
        // continuation *is* implemented for paragraphs, so this is an omission
        // against Fude's own rule.
        assertEquals(
            listOf(BlockKind.BLOCK_QUOTE, BlockKind.PARAGRAPH),
            kinds("> a\nb\n"),
            "KNOWN GAP: 'b' should stay inside the block quote.",
        )
        // The paragraph case it should match.
        assertEquals(
            listOf(BlockKind.PARAGRAPH),
            kinds("one\ntwo\n"),
            "lazy continuation works for paragraphs",
        )
    }

    // ------------------------------------------------------- supported

    /**
     * Spaced thematic breaks — moved here from the known gaps.
     *
     * This was filed under "Known gaps that are neither supported nor deliberately so",
     * which `docs/conformance.md` describes as "unimplemented and unexamined. They are
     * bugs, not positions." It was not unexamined: `isThematicBreak` compared
     * `line.trim().toSet()` against `setOf('-')`, and that set contains spaces, so
     * `"- - -"` produced `{'-', ' '}` and failed — while the very next clause of the same
     * predicate, `all { … || it == ' ' }`, shows spaces were meant to be allowed.
     *
     * So the recorded cause in the document ("`isThematicBreak` requires every character
     * to be a dash") described a check that does not exist, and the miscategorisation is
     * what made a one-line predicate bug look like a decision. The row has moved to
     * `## Supported` in the document; this assertion is the enforcement.
     */
    @Test
    fun spacedThematicBreaksAreSupported() {
        for (text in listOf("- - -", "* * *", "_ _ _", "-  -  -", "-\t-\t-")) {
            assertEquals(
                listOf(BlockKind.THEMATIC_BREAK),
                kinds(text),
                "\"$text\" is three or more matching markers with whitespace between them",
            )
        }
        // The unspaced forms, which were always right and are why the gap is easy to miss.
        for (text in listOf("---", "***", "___")) {
            assertEquals(listOf(BlockKind.THEMATIC_BREAK), kinds(text))
        }
        // And a break still wins over a list item, which is CommonMark's own precedence
        // rule rather than an accident of branch order.
        assertEquals(listOf(BlockKind.THEMATIC_BREAK), kinds("- - -"))
        assertEquals(listOf(BlockKind.LIST), kinds("- one"), "and a real item is still an item")
    }

    /**
     * A block quote's marker is `>` plus one *optional* space, so the unspaced form is
     * also supported.
     *
     * `parseBlockQuote` hardcoded `contentStart = cursor + 2`, which is right for `> x`
     * and wrong for `>quote` — a form `isBlockQuote` has always accepted and a hand-typed
     * quote often takes. The first character was silently deleted from the tree.
     */
    @Test
    fun aBlockQuoteMarkerMayOmitItsSpace() {
        for (text in listOf("> quote", ">quote", ">no space", "  >indented")) {
            val quote = assertIs<BlockQuoteNode>(
                IncrementalMarkdownParser().parse(text).blocks.single(),
                "parsed \"$text\"",
            )
            val content = quote.children.filterIsInstance<ParagraphNode>().single()
            assertEquals(
                text.trimStart().removePrefix(">").trim(),
                text.substring(content.range.start, content.range.end).trim(),
                "every character after the marker survives, for \"$text\"",
            )
        }
        // And nesting still works, which is what a marker-width calculation can break:
        // only the outer marker may be stripped.
        val outer = assertIs<BlockQuoteNode>(IncrementalMarkdownParser().parse("> > nested").blocks.single())
        assertEquals(
            "nested",
            (assertIs<BlockQuoteNode>(outer.children.single()).children.single() as ParagraphNode)
                .let { "> > nested".substring(it.range.start, it.range.end) },
            "the inner marker is left for the inner quote to strip",
        )
    }

    @Test
    fun supportedBlockConstructs() {
        assertEquals(listOf(BlockKind.HEADING), kinds("# Title"))
        assertEquals(listOf(BlockKind.CODE_FENCE), kinds("```kotlin\nval x = 1\n```"))
        assertEquals(listOf(BlockKind.CODE_FENCE), kinds("````\n```\n````"), "a nested fence")
        assertEquals(listOf(BlockKind.BLOCK_QUOTE), kinds("> quoted"))
        assertEquals(listOf(BlockKind.BLOCK_QUOTE), kinds(">quoted"), "the space after > is optional")
        assertEquals(listOf(BlockKind.TABLE), kinds("| a | b |\n|---|---|\n| 1 | 2 |"))
    }

    @Test
    fun supportedInlineConstructs() {
        fun inlines(text: String) =
            IncrementalMarkdownParser().parse(text).allBlocks.flatMap { it.inlines }

        assertEquals(1, inlines("a **bold** word").count { it is EmphasisNode && it.strong })
        assertEquals(1, inlines("a *italic* word").count { it is EmphasisNode && !it.strong })
        assertEquals(1, inlines("a _under_ word").count { it is EmphasisNode && !it.strong })
        assertEquals(1, inlines("a __dunder__ word").count { it is EmphasisNode && it.strong })
        assertEquals(1, inlines("a ~~gone~~ word").count { it is EmphasisNode })
        assertEquals(1, inlines("a `code` word").count { it is CodeSpanNode })
        assertEquals(1, inlines("a [link](https://x.dev) word").count { it is LinkNode })
        assertEquals(1, inlines("an ![alt](https://x.dev/p.png) word").count { it is ImageNode })

        // A code span's closing run must match its opening run, which is CommonMark and
        // was not implemented: the search was "the next backtick of any length".
        assertEquals(1, inlines("a ``co`de`` word").count { it is CodeSpanNode }, "one span, not three")
        assertEquals(
            "co`de",
            inlines("a ``co`de`` word").filterIsInstance<CodeSpanNode>().single().code,
        )
    }

    @Test
    fun lineEndingsAreAllLineBreaksAndNeverOwnedByABlock() {
        for (terminator in listOf("\n", "\r\n", "\r")) {
            val text = "# Title${terminator}${terminator}para${terminator}"
            assertEquals(
                listOf(BlockKind.HEADING, BlockKind.PARAGRAPH),
                kinds(text),
                "'${terminator.replace("\r", "\\r").replace("\n", "\\n")}' is a line break",
            )
            for (node in IncrementalMarkdownParser().parse(text).allBlocks) {
                val slice = text.substring(node.range.start, node.range.end)
                assertEquals(
                    false,
                    slice.endsWith("\n") || slice.endsWith("\r"),
                    "no block ends on a terminator",
                )
            }
        }
    }

    @Test
    fun aLeadingByteOrderMarkChangesNothing() {
        val plain = "# Title\n\ntext\n"
        val withBom = "\uFEFF$plain"
        assertEquals(kinds(plain), kinds(withBom), "a BOM must not reclassify the first block")
        for (node in IncrementalMarkdownParser().parse(withBom).allBlocks) {
            assertEquals(
                false,
                node.range.start == 0,
                "no node owns the BOM",
            )
        }
    }
}