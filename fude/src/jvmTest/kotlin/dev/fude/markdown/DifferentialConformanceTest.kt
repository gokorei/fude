package dev.fude.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A differential check against a CommonMark parser.
 *
 * `ConformanceCorpusTest` pins every decision in `docs/conformance.md`. That proves
 * Fude has not changed its mind. It cannot prove the mind was right, because every
 * expectation in it was written by the same person who wrote the parser — a shared
 * misreading of CommonMark would be pinned just as firmly as a correct one.
 *
 * This closes that gap from the outside. Where the two agree, Fude's reading is
 * corroborated by an independent implementation. Where they disagree, the case is
 * listed in `docs/conformance.md` and asserted here, so each divergence is a
 * recorded decision rather than an accident.
 *
 * **The divergences are the point.** A parser that agreed with the reference on
 * everything would be either a thin wrapper or wrong. What matters is that every
 * place they differ is written down, with a reason, and that the list has not grown
 * without anyone noticing.
 *
 * Runs on JVM only: the reference parser publishes no common klib, so it cannot be
 * on the classpath of a source set every target compiles. That is a fine constraint
 * — this is a development-time check, not something a user's editor needs.
 */
class DifferentialConformanceTest {

    /**
     * The reference parser's top-level block types, as Fude's own kind names.
     *
     * The reference emits whitespace as sibling nodes and prefixes every type with
     * `Markdown:`, so this drops EOLs and normalises the names. That mapping is the
     * whole risk in a differential test: a wrong mapping would show up as a
     * disagreement on constructs both parsers handle identically. It is why the
     * first tests assert agreement on ordinary prose before anything divergent is
     * claimed.
     */
    private fun reference(text: String, gfm: Boolean = false): List<String> {
        val flavour = if (gfm) GFMFlavourDescriptor() else CommonMarkFlavourDescriptor()
        return MarkdownParser(flavour)
            .buildMarkdownTreeFromString(text)
            .children
            .mapNotNull { node ->
                val name = node.type.toString().removePrefix("Markdown:")
                when {
                    name == "EOL" || name == "WHITE_SPACE" -> null
                    name.startsWith("ATX_") -> "HEADING"
                    name.startsWith("SETEXT_") -> "HEADING"
                    name == "UNORDERED_LIST" || name == "ORDERED_LIST" -> "LIST"
                    name == "HORIZONTAL_RULE" -> "THEMATIC_BREAK"
                    name == "BLOCK_QUOTE" -> "BLOCK_QUOTE"
                    name == "CODE_FENCE" -> "CODE_FENCE"
                    name == "TABLE" -> "TABLE"
                    name == "PARAGRAPH" -> "PARAGRAPH"
                    // An unrecognised node type contributes nothing rather than being
                    // labelled a paragraph, so a surprise shows up as a difference to
                    // investigate instead of passing silently.
                    else -> null
                }
            }
    }

    private fun fude(text: String): List<String> =
        IncrementalMarkdownParser().parse(text).blocks.map { it.kind.name }

    // ------------------------------------------------- constructs that agree

    @Test
    fun ordinaryProseAgrees() {
        assertEquals(listOf("HEADING", "PARAGRAPH"), reference("# Title\n\nbody\n"))
        assertEquals(listOf("HEADING", "PARAGRAPH"), fude("# Title\n\nbody\n"))
    }

    /** The CommonMark cases most likely to be got wrong, checked from outside. */
    @Test
    fun theSurprisingOnesAreConfirmedByTheReference() {
        // ATX needs the space.
        assertEquals(listOf("PARAGRAPH"), reference("#Title\n"), "no space after #")
        assertEquals(listOf("PARAGRAPH"), fude("#Title\n"))

        // Seven hashes is not a heading.
        assertEquals(listOf("PARAGRAPH"), reference("####### s\n"), "beyond level six")
        assertEquals(listOf("PARAGRAPH"), fude("####### s\n"))

        // Both ordered markers work.
        assertEquals(listOf("LIST"), reference("1) one\n"), "')' is a marker")
        assertEquals(listOf("LIST"), fude("1) one\n"))

        // A blank line makes one loose list, not two.
        assertEquals(listOf("LIST"), reference("- a\n\n- b\n"), "loose list")
        assertEquals(listOf("LIST"), fude("- a\n\n- b\n"))
    }

    @Test
    fun structuralConstructsAgree() {
        for (text in listOf(
            "- a\n- b\n",
            "> quoted\n",
            "```kotlin\nval x = 1\n```\n",
            "para one\n\n---\n\npara two\n",
        )) {
            assertEquals(
                reference(text),
                fude(text),
                "both parsers disagree on ${text.replace("\n", "\\n")}",
            )
        }
    }

    // ------------------------------------------- recorded divergences

    /**
     * Setext headings: Fude does not implement them, and `---` is always a thematic
     * break.
     *
     * A deliberate choice rather than an oversight. It resolves an ambiguity that
     * blocked other work — a `---` after a paragraph means one thing, not two — at
     * the cost of a construct most notes do not use. `docs/conformance.md` states
     * the consequence; this pins the divergence so it stays a decision.
     */
    @Test
    fun setextHeadingsAreADeliberateDivergence() {
        assertEquals(listOf("HEADING"), reference("Title\n=====\n"), "the reference reads a setext H1")
        assertEquals(listOf("PARAGRAPH"), fude("Title\n=====\n"), "Fude deliberately does not")

        // The flip side, which is the part that matters in practice.
        assertEquals(
            listOf("HEADING"),
            reference("para\n-----\n"),
            "in CommonMark this is a setext H2",
        )
        assertEquals(
            listOf("PARAGRAPH", "THEMATIC_BREAK"),
            fude("para\n-----\n"),
            "Fude reads a thematic break, and says so in the docs",
        )
    }

    /**
     * A blank line ends a block quote, in Fude as in CommonMark.
     *
     * This one used to be a recorded gap. `parseBlockQuote` stepped over the line
     * terminator with a skip that consumed *every* consecutive newline, so the blank
     * line between `> a` and `> b` was swallowed and both quotes became one — a
     * paragraph break that visibly did nothing.
     *
     * Found by this test rather than by reading the spec, which is the argument for
     * asserting it from the outside.
     */
    @Test
    fun aBlankLineEndsABlockQuote() {
        assertEquals(
            listOf("BLOCK_QUOTE", "BLOCK_QUOTE"),
            reference("> a\n\n> b\n"),
        )
        assertEquals(
            listOf("BLOCK_QUOTE", "BLOCK_QUOTE"),
            fude("> a\n\n> b\n"),
            "a blank line must end the quote, so these are two",
        )
    }

    /** Lazy continuation inside one quote is a single block and must stay one. */
    @Test
    fun aQuoteWithoutABlankLineIsStillOneBlock() {
        assertEquals(listOf("BLOCK_QUOTE"), reference("> a\n> b\n"))
        assertEquals(listOf("BLOCK_QUOTE"), fude("> a\n> b\n"))
    }

    /**
     * A quote's range must stop at its own content, not reach into the gap.
     *
     * The fix was to step over one terminator rather than all of them, which is the
     * invariant every other block already obeys: a range never ends on a terminator
     * and never claims the blank line that follows. `LineEndingAndBlockExtentTest`
     * pins this for the other blocks; this pins it for the one that was wrong.
     */
    @Test
    fun aQuoteDoesNotClaimTheBlankLineAfterIt() {
        val text = "> quoted\n\nnext paragraph\n"
        val blocks = IncrementalMarkdownParser().parse(text).blocks
        val quote = blocks.filterIsInstance<BlockQuoteNode>().single()
        // The range starts at the `>` marker, which the user typed and which the
        // decorator needs in order to style the whole quote.
        assertEquals("> quoted", text.substring(quote.range.start, quote.range.end))

        val next = blocks.last()
        assertTrue(
            next.range.start >= quote.range.end,
            "the paragraph starts at ${next.range.start} but the quote claims up to " +
                "${quote.range.end}, so the gap belongs to the quote",
        )
    }

    /**
     * A table does not need its outer pipes, in Fude as in GFM.
     *
     * Also previously unrecognised, and the cause was not where it looked. The
     * delimiter-row test rejected `--- | ---` while accepting `|---|---|`, because it
     * split on `|` without trimming, so the padding spaces failed the "only dashes and
     * colons" check. The header was never the problem; its delimiter row was being
     * thrown away. Fixing that one check is what made the unbracketed form work, and
     * it was found by running the reference parser rather than by reading GFM.
     */
    @Test
    fun aTableWithoutOuterPipesIsRecognised() {
        for (text in listOf(
            "A | B\n--- | ---\n1 | 2\n",
            "A | B | C\n--- | --- | ---\n1 | 2 | 3\n",
            "| A | B |\n|---|---|\n| 1 | 2 |\n",
        )) {
            assertEquals(
                reference(text, gfm = true),
                fude(text),
                "disagreement on ${text.replace("\n", "\\n")}",
            )
            assertEquals(listOf("TABLE"), fude(text).take(1))
        }
    }

    /** Cell contents, which is where an offset bug would show up rather than the kind. */
    @Test
    fun unbracketedTableCellsHoldTheRightSourceText() {
        val text = "A | B\n--- | ---\n1 | 2\n"
        val table = IncrementalMarkdownParser().parse(text).blocks
            .filterIsInstance<TableNode>().single()

        val cells = table.children
            .filterIsInstance<TableRowNode>()
            .flatMap { row -> row.cells }
            .map { cell -> text.substring(cell.range.start, cell.range.end) }
        assertEquals(listOf("A", "B", "1", "2"), cells, "cell ranges index the author's own text")
    }

    /** Prose containing a pipe must not become a table just because it can. */
    @Test
    fun aPipeInOrdinaryProseIsStillProse() {
        val prose = "just prose with a | pipe\nsecond line\n"
        assertEquals(listOf("PARAGRAPH"), fude(prose), "no delimiter row, so no table")
        assertEquals(listOf("PARAGRAPH"), reference(prose))
    }

    /** Mismatched column counts are not a table, in either parser. */
    @Test
    fun mismatchedColumnCountsAreNotATable() {
        assertEquals(listOf("PARAGRAPH"), fude("a | b\n--- | --- | ---\n"), "two columns, three delimiters")
        assertEquals(listOf("PARAGRAPH"), fude("a | b | c\n--- | ---\n"), "three columns, two delimiters")
    }

/**
     * The full divergence list, so growth is visible.
     *
     * One cause, three cases: setext headings are a deliberate position. The point of asserting the whole list
     * rather than each case separately is that the day a fifth appears, this fails
     * and forces someone to say whether it is acceptable — which is the only thing
     * keeping the document honest.
     */
    @Test
    fun theDivergenceListHasNotGrown() {
        val cases = listOf(
            "Title\n=====\n",
            "Title\n-----\n",
            "para\n-----\n",
            "> a\n\n> b\n",
            "A | B\n--- | ---\n1 | 2\n",
            "# a\n## b\n### c\n",
            "- a\n\n- b\n",
            "1) one\n",
            "#Title\n",
            "####### s\n",
            "```\ncode\n```\n",
            "para one\n\n---\n\npara two\n",
            "*emph* and `code`\n",
            "[link](https://example.com)\n",
            // Newly added, and they must AGREE — each is a case that used to be a known
            // gap and is now pinned from outside as well as from the corpus.
            "- - -\n",
            "* * *\n",
            "_ _ _\n",
            ">quote\n",
            "> > nested\n",
        )

        val divergent = cases.filter { reference(it, gfm = true) != fude(it) }
        assertEquals(
            divergent,
            listOf(
                // Setext, both forms. `Title` and `para` are the same decision seen
                // twice; `---` after a paragraph is the case that actually comes up.
                "Title\n=====\n",
                "Title\n-----\n",
                "para\n-----\n",
            ),
            "the divergences changed. Each needs a row in docs/conformance.md saying " +
                "whether it is deliberate. Currently divergent: $divergent",
        )
    }

    // ------------------------------------------------- formerly known gaps

    /**
     * A spaced thematic break, confirmed from outside.
     *
     * Fused read `- - -` as a list item: not merely unstyled but the wrong **shape**,
     * which is why `docs/conformance.md` called it the more serious of its two gaps. It
     * was filed as an unexamined gap because the recorded cause was wrong — the document
     * said `isThematicBreak` "requires every character to be a dash", and no such check
     * existed. The real cause was set equality over a set that included spaces.
     *
     * This also pins CommonMark's precedence rule, which is why the assertion is against
     * the reference rather than a hardcoded expectation: both a thematic break and a list
     * item are possible readings, and the reference is what settles which wins.
     */
    @Test
    fun aSpacedThematicBreakIsABreakAndWinsOverAList() {
        for (text in listOf("- - -\n", "* * *\n", "_ _ _\n", "-  -  -\n", "-\t-\t-\n")) {
            assertEquals(
                listOf("THEMATIC_BREAK"),
                reference(text),
                "the reference reads ${text.replace("\t", "\\t").replace("\n", "\\n")} as a rule",
            )
            assertEquals(
                listOf("THEMATIC_BREAK"),
                fude(text),
                "and Fude must agree; saw ${fude(text)}",
            )
        }
        // A genuine list item is untouched, so the fix did not over-match.
        assertEquals(listOf("LIST"), reference("- one\n"))
        assertEquals(listOf("LIST"), fude("- one\n"))
    }

    /**
     * A block quote with no space after `>`, confirmed from outside.
     *
     * `parseBlockQuote` hardcoded `cursor + 2` for the marker width, which is right for
     * `> quote` and wrong for `>quote` — the first character was deleted from the tree.
     * `isBlockQuote` has always accepted the unspaced form, so this was an internal
     * inconsistency before it was a conformance bug.
     */
    @Test
    fun aBlockQuoteMarkerMayOmitItsSpace() {
        assertEquals(listOf("BLOCK_QUOTE"), reference(">quote\n"), "the reference reads it as a quote")
        assertEquals(listOf("BLOCK_QUOTE"), fude(">quote\n"), "and Fude must agree")

        // The content, which is where the deleted character showed up. The block *kind*
        // was right before the fix even when the content was not.
        val inner = "> > nested"
        assertEquals(listOf("BLOCK_QUOTE"), reference(inner + "\n"))
        assertEquals(listOf("BLOCK_QUOTE"), fude(inner + "\n"), "nesting is preserved, not flattened")

        // And the padded form is unchanged, in both parsers.
        assertEquals(fude("> quote\n"), fude(">quote\n"), "the space is padding and the kind must not shift")
    }

    /**
     * Code spans are closed by a matching backtick run, confirmed from outside.
     *
     * The search was "the next backtick of any length", so ``` ``a ` b`` ``` became three
     * spans and three text runs instead of one. `org.jetbrains:markdown` implements the
     * run-length rule, so this is externally checkable.
     *
     * Asserted on the *count of spans* and on the content rather than on Fude's tree
     * shape, because the reference produces an AST in its own vocabulary and the thing
     * that was wrong was how many spans there were.
     */
    @Test
    fun aCodeSpanIsClosedByAMatchingBacktickRun() {
        for (text in listOf("``a ` b``\n", "a ``co`de`` word\n", "```x``` y``z``\n")) {
            val referenceSpans = referenceInlineCode(text)
            assertTrue(
                referenceSpans.isNotEmpty(),
                "the reference recognises a code span in ${text.replace("\n", "\\n")}",
            )
            assertEquals(
                referenceSpans.size,
                fudeCodeSpans(text).size,
                "span count differs on ${text.replace("\n", "\\n")}: reference $referenceSpans, " +
                    "Fude ${fudeCodeSpans(text)}",
            )
            assertEquals(
                referenceSpans,
                fudeCodeSpans(text),
                "span content differs on ${text.replace("\n", "\\n")}",
            )
        }
        // The single-backtick case is unchanged, and an unmatched run is literal in both.
        assertEquals(listOf("code"), fudeCodeSpans("run `code` now\n"))
        assertEquals(emptyList(), fudeCodeSpans("a ` stray backtick\n"), "no closing run, so no span")
    }

    /**
     * The source text of every CODE_SPAN node in the reference tree, delimiters stripped.
     *
     * Sliced by offset rather than read off the node, because `ASTNode` exposes `text`
     * only on its leaves. Stripped because the reference node spans the delimiters while
     * Fude's `CodeSpanNode.code` holds only the content, and comparing those directly
     * would report a difference that is only a vocabulary choice.
     */
    private fun referenceInlineCode(text: String): List<String> {
        fun walk(node: ASTNode): List<String> =
            buildList {
                if (node.type.toString().removePrefix("Markdown:") == "CODE_SPAN") {
                    add(text.substring(node.startOffset, node.endOffset))
                }
                node.children.forEach { addAll(walk(it)) }
            }
        return walk(
            MarkdownParser(CommonMarkFlavourDescriptor()).buildMarkdownTreeFromString(text),
        ).map { span -> span.trim().trim('`') }
    }

    /** The `code` of every [dev.fude.markdown.CodeSpanNode] Fude produces, trimmed. */
    private fun fudeCodeSpans(text: String): List<String> =
        IncrementalMarkdownParser().parse(text).allBlocks
            .flatMap { it.inlines }
            .filterIsInstance<dev.fude.markdown.CodeSpanNode>()
            .map { it.code.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /**
     * A link destination containing a balanced pair of parentheses.
     *
     * Recorded as a divergence rather than fixed. `parseLinkLike` finds the *first* `)`,
     * so `[a](b(c))` yields the destination `b(c` where CommonMark yields `b(c)`. The
     * bounded search this ticket asked for does not change that — it fixes the scan
     * running past the end of the block — so balanced-paren scanning is a separate
     * question, pinned here so it is known rather than unknown.
     */
    @Test
    fun aLinkDestinationWithABalancedParenthesisIsADivergence() {
        val text = "[a](b(c))\n"
        assertEquals("b(c)", referenceLinkDestination(text), "the reference balances the parens")
        assertEquals("b(c", fudeLinkDestination(text), "Fude takes the first ')', and says so here")
        // The ordinary forms agree, so the divergence is narrow.
        assertEquals("x.dev", fudeLinkDestination("[l](x.dev)\n"))
        assertEquals("x.dev", referenceLinkDestination("[l](x.dev)\n"))
    }

    private fun referenceLinkDestination(text: String): String {
        fun walk(node: ASTNode): String? {
            if (node.type.toString().removePrefix("Markdown:") == "LINK_DESTINATION") {
                return text.substring(node.startOffset, node.endOffset)
            }
            return node.children.firstNotNullOfOrNull { walk(it) }
        }
        return walk(
            MarkdownParser(CommonMarkFlavourDescriptor()).buildMarkdownTreeFromString(text),
        ).orEmpty()
    }

    private fun fudeLinkDestination(text: String): String =
        IncrementalMarkdownParser().parse(text).allBlocks
            .flatMap { it.inlines }
            .filterIsInstance<LinkNode>()
            .firstOrNull()
            ?.destination
            .orEmpty()
}
