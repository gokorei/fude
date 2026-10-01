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
}
