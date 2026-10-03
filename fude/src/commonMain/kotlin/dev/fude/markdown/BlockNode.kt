package dev.fude.markdown

import dev.fude.core.InlineRange

/**
 * A block of the document, as a tree.
 *
 * Every node carries the [range] of source it occupies, including its markers.
 * The renderer, the caret mapping and the source/rendered toggle all need to
 * know which document offsets a block covers, and deriving that after the fact
 * is where off-by-one caret bugs come from.
 *
 * [children] is always blocks. A paragraph's inline content lives in [inlines],
 * not here, so "descend to a child block" and "descend to an inline run" stay
 * distinct — conflating them is how a renderer ends up trying to lay out a text
 * node as if it were a container.
 */
public sealed interface BlockNode {
    /** The source this block occupies, markers included. */
    public val range: InlineRange

    /** The block's kind, for the renderer to switch on. */
    public val kind: BlockKind

    /** Nested blocks, e.g. list items within a list. */
    public val children: List<BlockNode>
        get() = emptyList()

    /** The block's inline content. Empty for blocks that hold only children. */
    public val inlines: List<InlineNode>
        get() = emptyList()
}

public enum class BlockKind {
    PARAGRAPH,
    HEADING,
    LIST,
    LIST_ITEM,
    BLOCK_QUOTE,
    CODE_FENCE,
    TABLE,
    TABLE_ROW,
    TABLE_CELL,
    THEMATIC_BREAK,

    /** A block supplied by a host extension. Fude has no built-in knowledge of it. */
    HOST_DEFINED,
}

/** An inline run: text, emphasis, code, a link, an image, or a host-defined span. */
public sealed interface InlineNode {
    public val range: InlineRange
}

public data class TextNode(override val range: InlineRange, val text: String) : InlineNode

public data class EmphasisNode(
    override val range: InlineRange,
    val children: List<InlineNode>,
    /** True for `**bold**`, false for `*italic*` and `~~strikethrough~~`. */
    val strong: Boolean,
) : InlineNode

public data class CodeSpanNode(override val range: InlineRange, val code: String) : InlineNode

/**
 * A link.
 *
 * [labelRange] is separate from [range] because the label and the destination are
 * different lengths, and the caret has to land in the right one. This is the
 * "display text differs from the target" case: when rendering shows the label,
 * a caret position inside the link must map to the label's source, not the
 * destination's.
 */
public data class LinkNode(
    override val range: InlineRange,
    val labelRange: InlineRange,
    val destination: String,
) : InlineNode

public data class ImageNode(
    override val range: InlineRange,
    val altText: String,
    val destination: String,
) : InlineNode

/** An inline span a host extension recognised. Fude does not know what it means. */
public data class HostInlineNode(
    override val range: InlineRange,
    val extensionId: String,
) : InlineNode

public data class ParagraphNode(
    override val range: InlineRange,
    override val inlines: List<InlineNode>,
) : BlockNode {
    override val kind: BlockKind get() = BlockKind.PARAGRAPH
}

public data class HeadingNode(
    override val range: InlineRange,
    val level: Int,
    override val inlines: List<InlineNode>,
) : BlockNode {
    override val kind: BlockKind get() = BlockKind.HEADING
}

public data class ListNode(
    override val range: InlineRange,
    val ordered: Boolean,
    override val children: List<BlockNode>,
) : BlockNode {
    override val kind: BlockKind get() = BlockKind.LIST
}

public data class ListItemNode(
    override val range: InlineRange,
    override val children: List<BlockNode>,
    override val inlines: List<InlineNode>,
    val indent: Int,
) : BlockNode {
    override val kind: BlockKind get() = BlockKind.LIST_ITEM
}

public data class BlockQuoteNode(
    override val range: InlineRange,
    override val children: List<BlockNode>,
) : BlockNode {
    override val kind: BlockKind get() = BlockKind.BLOCK_QUOTE
}

/**
 * A fenced code block.
 *
 * [contentRange] excludes the fence markers, and its contents are opaque: nothing
 * inside is parsed as Markdown. A fence containing a fence marker, a wikilink, or
 * a heading must stay literal — this is the classic live-preview bug, and it is
 * why the content is kept as a string rather than as inline nodes.
 */
public data class CodeFenceNode(
    override val range: InlineRange,
    val contentRange: InlineRange,
    val info: String,
) : BlockNode {
    override val kind: BlockKind get() = BlockKind.CODE_FENCE

    /** The fence's contents as written. Never parsed, never decorated as Markdown. */
    public fun content(text: CharSequence): String =
        if (contentRange.start < contentRange.end) text.subSequence(contentRange.start, contentRange.end).toString() else ""
}

public data class TableNode(
    override val range: InlineRange,
    override val children: List<BlockNode>,
    val hasHeader: Boolean,
) : BlockNode {
    override val kind: BlockKind get() = BlockKind.TABLE
}

public data class TableRowNode(
    override val range: InlineRange,
    val cells: List<TableCellNode>,
) : BlockNode {
    override val kind: BlockKind get() = BlockKind.TABLE_ROW
    override val children: List<BlockNode> get() = cells
}

public data class TableCellNode(
    override val range: InlineRange,
    override val inlines: List<InlineNode>,
) : BlockNode {
    override val kind: BlockKind get() = BlockKind.TABLE_CELL
}

public data class ThematicBreakNode(override val range: InlineRange) : BlockNode {
    override val kind: BlockKind get() = BlockKind.THEMATIC_BREAK
}

/** A block a host extension claimed. Fude does not know what it means. */
public data class HostBlockNode(
    override val range: InlineRange,
    override val inlines: List<InlineNode>,
    val extensionId: String,
    /**
     * Whether this construct's closing delimiter is inside [range].
     *
     * Mirrored from [dev.fude.syntax.BlockMatch.ownsTerminator] so a host, a
     * decorator or a diagnostic can see the decision without holding on to the
     * extension that made it.
     */
    val ownsTerminator: Boolean = true,
) : BlockNode {
    override val kind: BlockKind get() = BlockKind.HOST_DEFINED
}

/** Every node in the tree, in document order. */
public fun List<BlockNode>.flattenBlocks(): List<BlockNode> = buildList {
    fun walk(nodes: List<BlockNode>) {
        for (node in nodes) {
            add(node)
            walk(node.children)
        }
    }
    walk(this@flattenBlocks)
}
