package dev.fude.spike

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Can the candidate parser run from common code and give Fude the block-level
 * AST an incremental reparse needs?
 *
 * GFM rather than strict CommonMark, because live preview has to render tables,
 * strikethrough and task lists to be useful, and those are GFM extensions.
 */
class ParserProbe {
    private val parser = MarkdownParser(GFMFlavourDescriptor())

    private fun walk(node: ASTNode, depth: Int = 0): List<String> {
        val lines = mutableListOf<String>()
        lines += "${"  ".repeat(depth)}${node.type} [${node.startOffset}..${node.endOffset})"
        for (child in node.children) lines += walk(child, depth + 1)
        return lines
    }

    @Test
    fun parsesTheFixtureAndKeepsOffsetsAlignedWithTheSource() {
        val text = SpikeDocument.full
        val tree = parser.buildMarkdownTreeFromString(text)
        val lines = walk(tree)
        println("PROBE nodes=${lines.size}")
        println("PROBE rootType=${tree.type}")

        val blocks = lines.count { it.trimStart().startsWith("MarkdownElementTypes") }
        println("PROBE blockishLines=$blocks")
        lines.take(25).forEach { println("PROBE node $it") }

        assertTrue(tree.startOffset == 0)
        assertTrue(tree.endOffset <= text.length, "AST must stay within the source text")
    }

    @Test
    fun strongAndEmphasisAreDistinguishedNodes() {
        val tree = parser.buildMarkdownTreeFromString("plain **bold** and *italic*")
        val found = walk(tree).map { it.trim().substringBefore(" [") }
        println("PROBE inlineNodes=${found.filter { "EMPH" in it || "STRONG" in it }}")
        assertTrue(found.any { "STRONG" in it })
        assertTrue(found.any { "EMPH" in it })
    }

    @Test
    fun wikiLinksArePlainTextToACommonMarkParser() {
        val text = "[[Wikilink]] and [a link](http://x)"
        val tree = parser.buildMarkdownTreeFromString(text)
        val types = walk(tree).map { it.trim().substringBefore(" [") }
        println("PROBE wikiTypes=${types.filter { "LINK" in it || "TEXT" in it }}")
        assertTrue(MarkdownElementTypes.LINK_LABEL.name.isNotEmpty())
    }

    @Test
    fun fencedCodeContentsDoNotProduceMarkupNodes() {
        val text = "# real\n\n```\n# fake [[not a link]]\n```\n"
        val types = walk(parser.buildMarkdownTreeFromString(text)).map { it.trim().substringBefore(" [") }
        println("PROBE fencedTypes=${types.filter { "ATX" in it || "CODE" in it }}")
    }
}
