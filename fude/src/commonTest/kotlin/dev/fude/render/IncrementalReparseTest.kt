package dev.fude.render

import dev.fude.core.Edit
import dev.fude.core.Insert
import dev.fude.core.TextBuffer
import dev.fude.core.TextRange
import dev.fude.markdown.IncrementalMarkdownParser
import dev.fude.markdown.ParagraphNode
import dev.fude.markdown.ReparseCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Incremental reparse, asserted on a counter.
 *
 * The ticket asks for a counter rather than a timing on purpose. A timing
 * assertion fails on a slow machine and passes on a fast one while the code is
 * equally wrong in both; a count is exact. What is being asserted is that an
 * edit reparses one block rather than five thousand, which is the property that
 * makes the whole design viable.
 */
class IncrementalReparseTest {

    /**
     * Blank-line-separated paragraphs, so the document really has [lines] blocks.
     *
     * Consecutive non-blank lines are a *single* Markdown paragraph — lazy
     * continuation — so a newline-separated fixture would be one block, and an
     * incremental-reparse test over it would assert nothing.
     */
    private fun document(lines: Int = 5_000): String =
        (0 until lines).joinToString("\n\n") { "Paragraph $it with **bold** text." }

    @Test
    fun aNewlineInsertedAtTheTopReparsesOnlyTheAffectedBlock() {
        val text = document()
        val counter = ReparseCounter()
        val parser = IncrementalMarkdownParser(counter = counter)

        val initial = parser.parse(text)
        assertEquals(1, counter.fullParses)
        assertEquals(5_000, initial.blocks.size)

        // One character typed at the very start. Everything below shifts by one,
        // but only the block it landed in can have changed.
        val edited = "# ${text}"
        val edit = Insert(0, "# ")
        val reparsed = parser.reparse(edited, edit)

        // Exactly one block was re-parsed. A full reparse would be 5,000.
        assertEquals(1, counter.blockParses, "one edit reparses one block")
        assertEquals(1, counter.fullParses, "and does not fall back to a full parse")

        assertEquals(5_000, reparsed.blocks.size)
        assertEquals("# Paragraph 0 with **bold** text.", reparsed.blocks[0].let {
            edited.substring(it.range.start, it.range.end)
        })
    }

    @Test
    fun anEditDeepInTheDocumentAlsoReparsesOneBlock() {
        val text = document()
        val counter = ReparseCounter()
        val parser = IncrementalMarkdownParser(counter = counter)
        parser.parse(text)

        val target = text.indexOf("Paragraph 4000")
        val edited = text.substring(0, target) + "X" + text.substring(target)
        val reparsed = parser.reparse(edited, Insert(target, "X"))

        assertEquals(1, counter.blockParses, "an edit mid-document is still one block")
        assertEquals(5_000, reparsed.blocks.size)
    }

    @Test
    fun blockRangesStayAlignedWithTheSourceAfterAReparse() {
        val text = document(500)
        val parser = IncrementalMarkdownParser()
        parser.parse(text)

        // Insert mid-word rather than at the block start, so the assertion below
        // that blocks start with "Paragraph" is meaningful.
        val target = text.indexOf("Paragraph 250") + 11
        val edited = text.substring(0, target) + "!!" + text.substring(target)
        val reparsed = parser.reparse(edited, Insert(target, "!!"))

        // Every block's range must match the source it covers, or caret mapping
        // silently drifts and nothing fails until a user notices.
        for (block in reparsed.blocks) {
            val covered = edited.substring(block.range.start, block.range.end)
            assertTrue(
                covered.startsWith("Paragraph"),
                "block [${block.range.start}..${block.range.end}) covers [$covered]",
            )
        }
    }

    @Test
    fun aStructuralEditThatSplitsAParagraphIsHandled() {
        val text = "one\n\ntwo\n\nthree"
        val parser = IncrementalMarkdownParser()
        assertEquals(3, parser.parse(text).blocks.size)

        // Turn the blank line between "one" and "two" into a heading.
        val edited = "one\n\n# two\n\nthree"
        val reparsed = parser.reparse(edited, dev.fude.core.replace(TextBuffer.of(text), TextRange(4, 5), "\n# "))

        assertEquals(3, reparsed.blocks.size)
        val kinds = reparsed.blocks.map { it.kind }
        assertEquals(
            listOf(
                dev.fude.markdown.BlockKind.PARAGRAPH,
                dev.fude.markdown.BlockKind.HEADING,
                dev.fude.markdown.BlockKind.PARAGRAPH,
            ),
            kinds,
        )
    }

    @Test
    fun aDeleteThatJoinsTwoBlocksIsHandled() {
        val text = "alpha\n\nbeta"
        val parser = IncrementalMarkdownParser()
        parser.parse(text)

        val joined = "alphabeta"
        val edit = dev.fude.core.Delete(TextRange(5, 7), "\n\n")
        val reparsed = parser.reparse(joined, edit)

        assertEquals(1, reparsed.blocks.size, "deleting the newline joins the paragraphs")
        assertEquals("alphabeta", joined.substring(reparsed.blocks[0].range.start, reparsed.blocks[0].range.end))
    }

    @Test
    fun anUnchangedTextReturnsTheCachedParseWithoutReparsing() {
        val text = document(100)
        val counter = ReparseCounter()
        val parser = IncrementalMarkdownParser(counter = counter)
        val first = parser.parse(text)

        val again = parser.reparse(text, Insert(0, ""))
        assertTrue(first === again, "an empty edit must not invalidate anything")
        assertEquals(0, counter.blockParses)
        assertEquals(1, counter.fullParses)
    }

    @Test
    fun blockIdentityIsStableAcrossAnEditBelowIt() {
        val text = document(50)
        val parser = IncrementalMarkdownParser()
        val before = parser.parse(text)

        val firstParagraphText = before.blocks.first().let {
            text.substring(it.range.start, it.range.end)
        }

        val target = text.length
        val edited = text + "extra"
        parser.reparse(edited, Insert(target, "extra"))

        val after = parser.parse(edited)
        val afterFirst = after.blocks.first().let { edited.substring(it.range.start, it.range.end) }
        assertEquals(firstParagraphText, afterFirst)
    }

    @Test
    fun theSpikeFixtureReparsesOneBlockPerKeystroke() {
        val text = dev.fude.spike.SpikeDocument.full
        val counter = ReparseCounter()
        val parser = IncrementalMarkdownParser(counter = counter)
        val initial = parser.parse(text)
        assertTrue(initial.blocks.size > 100, "the fixture is a real document")

        val offset = dev.fude.spike.SpikeDocument.caretOffset
        val edited = text.substring(0, offset) + "x" + text.substring(offset)
        parser.reparse(edited, Insert(offset, "x"))

        assertEquals(
            1,
            counter.blockParses,
            "a keystroke in a ${dev.fude.spike.SpikeDocument.lineCount}-line note reparses one block",
        )
    }

    /**
     * The invariant every incremental path has to keep: whatever route it took, the
     * result must be the parse a full parse would have produced.
     *
     * This is the assertion that matters, and it is stronger than either "no stale
     * ranges" or "the tail is covered" on its own. A tree can be in range and still
     * be missing half the document; a tree can cover everything and still carry a
     * range from the old text. Comparing against `parse` pins both down without
     * having to know in advance which one the incremental path got wrong.
     */
    @Test
    fun everyReparseAgreesWithAFullParseOfTheSameText() {
        val original = realisticDocument()
        val deletionAt = original.indexOf("bold") + 4
        val append = "\n\nAppended paragraph with `code`.\n"

        val cases = listOf(
            // What MarkdownEditor computes on a full replacement: the common prefix
            // as the edit offset, and an insertion of nothing because it only knows
            // where the two texts diverge.
            "whole document replaced by a much shorter one" to
                (REPLACEMENT to Insert(2, "")),
            "whole document replaced by a much longer one" to
                (original + original to Insert(2, "")),
            "replaced by a single word" to
                ("word\n" to Insert(0, "")),
            "replaced by empty" to
                ("" to Insert(0, "")),
            "emptied then refilled" to
                (original to Insert(0, "")),
            // The ordinary cases, which must keep agreeing too — a fix that always
            // falls back to a full parse would pass a stale-range test and break the
            // design. These assert the equality; the counter tests above assert speed.
            "one character typed at the top" to
                ("# $original" to Insert(0, "# ")),
            "one character deleted in the middle" to
                (original.replaceRange(deletionAt, deletionAt + 1, "") to
                    dev.fude.core.Delete(TextRange(deletionAt, deletionAt + 1), "")),
            "a whole block appended" to
                (original + append to Insert(original.length, append)),
        )

        for ((label, case) in cases) {
            val (edited, edit) = case

            val incremental = IncrementalMarkdownParser().let { parser ->
                parser.parse(original)
                parser.reparse(edited, edit)
            }
            val fromScratch = IncrementalMarkdownParser().parse(edited)

            assertEquals(
                describe(fromScratch),
                describe(incremental),
                "reparse must equal a full parse: $label",
            )
        }
    }

    /**
     * The specific crash this suite found, kept as its own test because the failure
     * is a thrown exception in the renderer rather than a quietly wrong tree.
     *
     * `decorateBlock` hands every node range to `TextFieldBuffer.addStyle`, which
     * throws when the range runs past the end of the buffer. A node carried over
     * from a longer previous document therefore takes the editor down — and it did,
     * with a table's `**bold**` span reported against a 51-character replacement.
     */
    @Test
    fun noNodeOutlivesTheDocumentItCameFrom() {
        val parser = IncrementalMarkdownParser()
        parser.parse(realisticDocument())

        val next = parser.reparse(REPLACEMENT, Insert(2, ""))

        for (node in next.allBlocks) {
            assertTrue(
                node.range.start >= 0 && node.range.end <= REPLACEMENT.length,
                "${node::class.simpleName} at ${node.range} outlives a " +
                    "${REPLACEMENT.length}-char document",
            )
            for (inline in node.inlines) {
                assertTrue(
                    inline.range.end <= REPLACEMENT.length,
                    "${inline::class.simpleName} at ${inline.range} outlives the document",
                )
            }
        }
    }

    /**
     * A shrinking edit must not leave the document's tail unparsed.
     *
     * This is the other half of the same defect, and it is quieter than the crash:
     * nothing throws, the tree is entirely in range, and everything past the first
     * block simply stops being recognised. Blocks the user can see stop being
     * Markdown, which is the whole product failing at its one job.
     */
    @Test
    fun aShrinkingReplacementStillParsesTheWholeDocument() {
        val parser = IncrementalMarkdownParser()
        parser.parse(realisticDocument())

        val next = parser.reparse(REPLACEMENT, Insert(2, ""))

        assertEquals(
            IncrementalMarkdownParser().parse(REPLACEMENT).blocks.size,
            next.blocks.size,
            "the replacement must be parsed in full, not just its first block",
        )
        // And the recognisable syntax at the end of the replacement survives.
        val hasCodeSpan = next.allBlocks.any { block ->
            block.inlines.any { it is dev.fude.markdown.CodeSpanNode }
        }
        assertTrue(hasCodeSpan, "the replacement's own syntax must still be recognised")
    }

    /** A flat rendering of a block tree, for comparing two parses of the same text. */
    private fun describe(document: dev.fude.markdown.ParsedDocument): List<String> =
        document.allBlocks.map { node ->
            buildString {
                append(node.kind).append(node.range)
                append("|inlines=")
                append(node.inlines.map { "${it::class.simpleName}${it.range}" })
                append("|children=")
                append(node.children.map { "${it::class.simpleName}${it.range}" })
            }
        }

    /**
     * The shape of a real note: several block kinds, nesting, and a table — chosen
     * because a whole-document replacement has to carry all of them past the
     * boundary, which a wall of identical paragraphs would not exercise.
     */
    private fun realisticDocument(): String = """
        # Block coverage

        ## Heading level two

        A plain paragraph with **bold** text and `code`.

        - Unordered item
            - Nested level two
        - Second item with **bold**

        > A block quote.

        | Column A | Column B |
        |----------|----------|
        | `code`  | **bold** |
    """.trimIndent() + "\n"

    private companion object {
        /** Shorter than the original by a factor of nine. */
        const val REPLACEMENT = "# Replaced\n\nA **new** document with a `code span`.\n"
    }
}
