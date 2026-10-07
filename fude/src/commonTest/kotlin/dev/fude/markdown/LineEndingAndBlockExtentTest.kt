package dev.fude.markdown

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Line endings, byte-order marks, and where a block's range ends.
 *
 * These are the tests for GCG9KK8W (CRLF, BOM, lone CR) and DJN6YR9V (list and
 * fence extents). They assert the *desired* behaviour, so they fail today — which
 * is the point: each failure names a defect found by probing the parser and covered
 * by no other suite.
 *
 * The measurements these encode came from a throwaway probe whose output would
 * otherwise have been lost. The cases are preserved here rather than paraphrased,
 * so a regression shows up as a failing assertion instead of being re-derived.
 *
 * ## The offset-space contract these tests assume
 *
 * **Node ranges index into the text they were parsed from, unchanged.** Nothing is
 * stripped, rewritten or normalised on the way in. `\r\n` stays two characters, and
 * a BOM stays one. That is a deliberate constraint, not an accident: the editor's
 * model is that `text` is the source of truth and the host's caret, selection and
 * undo all index into it. A fix that normalised on entry would shift every offset
 * after the first CRLF and silently invalidate the host's caret.
 *
 * So the correct outcome for a CRLF document is not "the same ranges as LF" — it
 * cannot be, the strings differ in length. It is "the same blocks, over the same
 * content, with ranges that stop at the content".
 */
class LineEndingAndBlockExtentTest {
    private val parser = IncrementalMarkdownParser()

    // ------------------------------------------------------------------ helpers

    /** Block kinds in document order, ignoring ranges. */
    private fun kinds(text: String): List<BlockKind> =
        parser.parse(text).allBlocks.map { it.kind }

    /**
     * Block contents with line terminators removed, so a CRLF document and its LF
     * twin are comparable even though their offsets differ.
     */
    private fun contents(text: String): List<String> =
        parser.parse(text).allBlocks.map { block ->
            text.substring(block.range.start, block.range.end)
                .replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')
        }

    private fun show(s: String): String = s.replace("\r", "\\r").replace("\n", "\\n")

    /** No block's range may end on a line terminator. */
    private fun assertNoBlockEndsOnTerminator(text: String) {
        for (block in parser.parse(text).allBlocks) {
            assertTrue(
                block.range.start >= 0 && block.range.end <= text.length,
                "block ${block.kind} range ${block.range} escapes a ${text.length}-char document",
            )
            if (block.range.end > block.range.start) {
                val slice = text.substring(block.range.start, block.range.end)
                assertFalse(
                    slice.last() == '\n' || slice.last() == '\r',
                    "block ${block.kind} range ${block.range} ends on a terminator: '${show(slice)}'",
                )
            }
        }
    }

    /**
     * Top-level sibling blocks must not overlap.
     *
     * Only the top level, deliberately: a `LIST` legitimately contains its
     * `LIST_ITEM`s and a `TABLE` its rows, so a parent overlapping its own child is
     * the containment the tree is built on, not a defect. Checking the flattened
     * list would flag every nested construct in the library as a failure.
     */
    private fun assertNoSiblingOverlap(text: String) {
        val blocks = parser.parse(text).blocks
        for ((a, b) in blocks.zipWithNext()) {
            assertTrue(
                a.range.end <= b.range.start,
                "top-level ${a.kind} [${a.range.start},${a.range.end}] overlaps " +
                    "${b.kind} [${b.range.start},${b.range.end}] in '${show(text)}'",
            )
        }
    }

    // ------------------------------------------------------------ byte order mark

    @Test
    fun aLeadingByteOrderMarkDoesNotChangeTheFirstBlocksType() {
        // DEFECT: the BOM is read as content, so '# Title' no longer starts its
        // line and the heading is silently reclassified as a paragraph.
        assertEquals(
            listOf(BlockKind.HEADING, BlockKind.PARAGRAPH),
            kinds("\uFEFF# Title\n\ntext\n"),
            "a BOM must not stop the first line being a heading",
        )
    }

    @Test
    fun aByteOrderMarkIsNotOwnedByAnyNode() {
        val text = "\uFEFF# Title\n"
        for (block in parser.parse(text).allBlocks) {
            assertFalse(
                block.range.start == 0,
                "block ${block.kind} swallowed the BOM: range ${block.range}",
            )
        }
    }

    @Test
    fun aByteOrderMarkOnlyMattersOnce() {
        // A BOM elsewhere in the document is literal content, not a mark.
        assertEquals(
            listOf(BlockKind.PARAGRAPH, BlockKind.PARAGRAPH),
            kinds("text\n\n\uFEFF# Title\n"),
            "a BOM mid-document is content and must not start a heading",
        )
    }

    @Test
    fun aDocumentThatIsOnlyAByteOrderMarkDoesNotCrash() {
        val parsed = parser.parse("\uFEFF")
        assertTrue(parsed.allBlocks.size <= 1, "a BOM-only document must not explode")
    }

    // ------------------------------------------------------------------------ CRLF

    @Test
    fun crlfBlocksDoNotOwnTheirLineTerminator() {
        // DEFECT: lineEndAt stops at '\n', so every CRLF block's range includes
        // the '\r' that belongs to the terminator rather than to the content.
        assertNoBlockEndsOnTerminator("# Title\r\n\r\nA paragraph.\r\n")
        assertNoBlockEndsOnTerminator("## H2\r\ntext\r\n### H3\r\n")
        assertNoBlockEndsOnTerminator("- one\r\n- two\r\n")
        assertNoBlockEndsOnTerminator("> quoted\r\n\r\npara\r\n")
    }

    @Test
    fun crlfProducesTheSameBlocksAsItsLfTwin() {
        val cases = listOf(
            "# Title\n\nA paragraph.\n" to "# Title\r\n\r\nA paragraph.\r\n",
            "## H2\ntext\n### H3\n" to "## H2\r\ntext\r\n### H3\r\n",
            "- one\n- two\n" to "- one\r\n- two\r\n",
            "1. one\n2. two\n" to "1. one\r\n2. two\r\n",
            "> quoted\n" to "> quoted\r\n",
            "```kotlin\nval x = 1\n```\n" to "```kotlin\r\nval x = 1\r\n```\r\n",
            "| a | b |\n|---|---|\n| 1 | 2 |\n" to "| a | b |\r\n|---|---|\r\n| 1 | 2 |\r\n",
            "before\n\n---\n\nafter\n" to "before\r\n\r\n---\r\n\r\nafter\r\n",
            "- a\n  - b\n\nafter\n" to "- a\r\n  - b\r\n\r\nafter\r\n",
        )
        for ((lf, crlf) in cases) {
            assertEquals(kinds(lf), kinds(crlf), "block kinds differ for LF '${show(lf)}'")
            assertEquals(
                contents(lf),
                contents(crlf),
                "block contents differ for LF '${show(lf)}'",
            )
        }
    }

    @Test
    fun aCrlfTableRowDoesNotGainAPhantomCell() {
        // DEFECT: the row's trailing '\r' is read as a cell, so a two-column row
        // produces three cells and the last contains only a carriage return.
        val crlf = parser.parse("| a | b |\r\n|---|---|\r\n| 1 | 2 |\r\n")
        val lf = parser.parse("| a | b |\n|---|---|\n| 1 | 2 |\n")

        assertEquals(
            lf.allBlocks.filterIsInstance<TableCellNode>().size,
            crlf.allBlocks.filterIsInstance<TableCellNode>().size,
            "a two-column table must have the same cell count under CRLF as under LF",
        )
        for (cell in crlf.allBlocks.filterIsInstance<TableCellNode>()) {
            val slice = crlf.text.substring(cell.range.start, cell.range.end)
            assertTrue(slice.isNotBlank(), "cell contains only whitespace: '${show(slice)}'")
        }
    }

    /**
     * A decorated inline must not span a line terminator.
     *
     * Narrowed from an earlier version of this test, which asserted that *no* inline
     * range contains a carriage return. That is not achievable, and asking for it
     * contradicts how Fude models the document.
     *
     * In `"a **bold** word\r\nnext [link](…) line"` the two lines are a single
     * Markdown paragraph — lazy continuation — so the parser produces one
     * `TextNode [10,22)` covering `" word\r\nnext "`. That node is the unstyled source
     * between two decorated constructs, and the carriage return is in the user's own
     * text. "Source is the truth" is a stated design commitment: decoration is applied
     * on top of the buffer and never rewrites it, so a plain text run has to cover
     * the source verbatim. Splitting it at the terminator would add nodes to describe
     * characters that are already there.
     *
     * What *is* a real defect is a decorated run crossing a break, because bold or a
     * link underline straddling a soft line wrap is visibly wrong. That is what this
     * asserts now, and it holds.
     */
    @Test
    fun crlfInlineContentHasNoCarriageReturns() {
        val text = "a **bold** word\r\nnext [link](https://x.dev) line\r\n"
        for (node in parser.parse(text).allBlocks.flatMap { it.inlines }) {
            // A TextNode is the source itself and may legitimately span a soft break.
            if (node is TextNode) continue
            val slice = text.substring(node.range.start, node.range.end)
            assertFalse(
                slice.contains('\r'),
                "decorated inline ${node::class.simpleName} range ${node.range} " +
                    "contains a CR: '${show(slice)}'",
            )
        }
    }

    @Test
    fun aPlainTextRunMaySpanASoftLineBreakBecauseItIsTheSource() {
        // The counterpart to the test above, so the narrowing is pinned rather than
        // merely permitted: the carriage return is in the document, and Fude's job is
        // to describe the document, not to tidy it.
        val text = "a **bold** word\r\nnext [link](https://x.dev) line\r\n"
        val run = parser.parse(text).allBlocks
            .flatMap { it.inlines }
            .filterIsInstance<TextNode>()
            .first { it.range.start == 10 }
        assertEquals(" word\r\nnext ", text.substring(run.range.start, run.range.end))
    }

    // ------------------------------------------------------------------- lone CR

    @Test
    fun aLoneCarriageReturnIsALineBreak() {
        // DEFECT: '\r' is not a line break anywhere, so a classic-Mac document
        // collapses into a single block. Supporting lone CR costs nothing once
        // CRLF is handled, and not supporting it means a whole file becomes one
        // heading.
        assertEquals(
            listOf(BlockKind.HEADING, BlockKind.PARAGRAPH),
            kinds("# Title\rA paragraph.\r"),
            "a lone CR must separate blocks the way a newline does",
        )
    }

    @Test
    fun aLoneCarriageReturnDoesNotLeakIntoContent() {
        val text = "# Title\rA paragraph.\r"
        assertNoBlockEndsOnTerminator(text)
        for (node in parser.parse(text).allBlocks.flatMap { it.inlines }) {
            val slice = text.substring(node.range.start, node.range.end)
            assertFalse(slice.contains('\r'), "inline content must not contain a CR")
        }
    }

    @Test
    fun crlfIsNotMistakenForTwoLineBreaks() {
        // The point of treating '\r\n' as one terminator: an empty CRLF line must
        // be one blank line, not two, or every block gains a gap.
        assertEquals(
            listOf(BlockKind.PARAGRAPH, BlockKind.PARAGRAPH),
            kinds("first\r\n\r\nsecond\r\n"),
            "a CRLF blank line must be one blank line",
        )
    }

    // -------------------------------------------------------------------- extents

    @Test
    fun aListDoesNotClaimTheBlankLineAfterIt() {
        // DEFECT: endOfListItem advances past a blank line while deciding whether
        // the list continues, and can return that advanced offset as the item's end.
        val text = "- a\n\n---\n\nafter\n"
        val list = parser.parse(text).allBlocks.first { it.kind == BlockKind.LIST }
        assertEquals(
            "- a",
            text.substring(list.range.start, list.range.end),
            "the list's range must stop before the blank line",
        )
    }

    @Test
    fun aListItemDoesNotClaimTheBlankLineAfterIt() {
        val text = "- one\n- two\n\nparagraph\n"
        val last = parser.parse(text).allBlocks.filterIsInstance<ListItemNode>().last()
        assertEquals(
            "- two",
            text.substring(last.range.start, last.range.end),
            "the last item's range must stop at its own content",
        )
    }

    @Test
    fun aCodeFenceDoesNotClaimTheBlankLineAfterIt() {
        val text = "```\ncode\n```\n\nparagraph\n"
        val fence = parser.parse(text).allBlocks.first { it.kind == BlockKind.CODE_FENCE }
        assertEquals(
            "```\ncode\n```",
            text.substring(fence.range.start, fence.range.end),
            "the fence's range must stop at its closing fence",
        )
    }

    @Test
    fun aFenceKeepsItsOpaqueContentsUnchanged() {
        // Guards the fix: excluding the trailing newline must not shrink
        // contentRange, which is what keeps fence contents opaque.
        val text = "```kotlin\nval heading = \"# not a heading\"\n```\n\nparagraph\n"
        val fence = parser.parse(text).allBlocks.filterIsInstance<CodeFenceNode>().single()
        assertEquals("kotlin", fence.info, "info string must survive")
        assertTrue(
            fence.content(text).contains("# not a heading"),
            "fence content must survive intact",
        )
    }

    @Test
    fun blockQuotesAlreadyStopAtTheirContent() {
        // A control, not a defect: block quotes were already correct, asserted here
        // so a fix for the others cannot regress it.
        val text = "> quoted\n\nparagraph\n"
        val quote = parser.parse(text).allBlocks.first { it.kind == BlockKind.BLOCK_QUOTE }
        assertEquals("> quoted", text.substring(quote.range.start, quote.range.end))
    }

    @Test
    fun topLevelBlocksNeverOverlap() {
        val cases = listOf(
            "# h\n\np\n",
            "- a\n\np\n",
            "> q\n\np\n",
            "```\nc\n```\n\np\n",
            "~~~\nc\n~~~\n\np\n",
            "| a |\n|---|\n| 1 |\n\np\n",
            "- a\n  - b\n\n> q\n",
            "***\n\n# h\n",
        )
        for (text in cases) {
            assertNoSiblingOverlap(text)
        }
    }

    @Test
    fun noLfBlockEndsOnALineTerminator() {
        val cases = listOf(
            "# h\n\np\n",
            "- a\n\np\n",
            "> q\n\np\n",
            "```\nc\n```\n\np\n",
            "~~~\nc\n~~~\n\np\n",
            "| a |\n|---|\n| 1 |\n\np\n",
            "***\n\n# h\n",
        )
        for (text in cases) {
            assertNoBlockEndsOnTerminator(text)
        }
    }

    @Test
    fun everyBlockKindStaysWithinTheDocument() {
        val cases = listOf(
            "# h\n\np\n\n- l\n\n> q\n\n```\nc\n```\n\n| a |\n|---|\n| 1 |\n\n---\n",
            "\uFEFF# h\r\n\r\np\r\n\r\n- l\r\n",
        )
        for (text in cases) {
            for (block in parser.parse(text).allBlocks) {
                assertTrue(
                    block.range.start >= 0 && block.range.end <= text.length,
                    "block ${block.kind} range ${block.range} escapes a " +
                        "${text.length}-char document",
                )
            }
        }
    }

    @Test
    fun degenerateDocumentsDoNotCrash() {
        val cases = listOf(
            "", "\n", "\r\n", "\r", "   \n\t\n  \n", "\r\r\r",
            "\uFEFF", "#", "#\n", "a", "#no-space\n",
        )
        for (text in cases) {
            for (block in parser.parse(text).allBlocks) {
                assertTrue(
                    block.range.start >= 0 &&
                        block.range.end >= block.range.start &&
                        block.range.end <= text.length,
                    "block ${block.kind} range ${block.range} invalid for '${show(text)}'",
                )
            }
        }
    }
}