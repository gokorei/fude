package dev.fude.markdown

import dev.fude.core.InlineRange

/**
 * Which side of the source/rendered toggle a block is showing.
 *
 * This is Obsidian's central idea and it is worth copying deliberately: a block
 * is either showing its syntax or showing its result, and the user toggles
 * between them. "Always rendered" hides the syntax; "always source" is not live
 * preview at all.
 */
public enum class RenderMode {
    /** The Markdown the user typed, undecorated. */
    SOURCE,

    /** The parsed result, decorated. */
    RENDERED,
}

/**
 * Which blocks are showing source, keyed by the block's start offset.
 *
 * Kept outside the document on purpose. Toggling is a *view* change, so it must
 * not touch the text and must not mark the document modified — a host has to be
 * able to flip every block in a note without the note appearing edited.
 *
 * Keyed by start offset rather than by a synthetic id because that is what
 * survives an edit without a renumbering pass. An edit above a block shifts its
 * key, so [remapAfter] moves them.
 */
public class BlockViewState(
    initial: Map<Int, RenderMode> = emptyMap(),
) {
    private var modes: Map<Int, RenderMode> = initial.toMap()

    /** The mode for the block starting at [blockStart]. Rendered by default. */
    public fun modeOf(blockStart: Int): RenderMode = modes[blockStart] ?: RenderMode.RENDERED

    public fun isSource(blockStart: Int): Boolean = modeOf(blockStart) == RenderMode.SOURCE

    /**
     * Flips the block starting at [blockStart] and returns its new mode.
     *
     * A view change only: the document is untouched.
     */
    public fun toggle(blockStart: Int): RenderMode {
        val next = if (isSource(blockStart)) RenderMode.RENDERED else RenderMode.SOURCE
        modes = modes + (blockStart to next)
        return next
    }

    public fun set(blockStart: Int, mode: RenderMode) {
        modes = modes + (blockStart to mode)
    }

    /** Every block currently showing source. */
    public fun sourceBlocks(): Set<Int> = modes.filterValues { it == RenderMode.SOURCE }.keys

    public val isDefault: Boolean get() = modes.isEmpty()

    public fun clear() {
        modes = emptyMap()
    }

    /**
     * Shifts keys after an edit at [offset] that grew the document by [delta].
     *
     * Only blocks at or after the edit move. The block containing the edit keeps
     * its key, so a toggle does not silently apply to a different block because
     * the user typed above it.
     */
    public fun remapAfter(offset: Int, delta: Int) {
        if (delta == 0) return
        modes = modes.mapKeys { (key, _) -> if (key >= offset) key + delta else key }
    }

    /** Drops keys for blocks that no longer exist. */
    public fun retainOnly(validStarts: Set<Int>) {
        modes = modes.filterKeys { it in validStarts }
    }

    public fun copy(): BlockViewState = BlockViewState(modes)

    override fun equals(other: Any?): Boolean = other is BlockViewState && other.modes == modes

    override fun hashCode(): Int = modes.hashCode()

    override fun toString(): String = "BlockViewState($modes)"
}

/**
 * Keeps [BlockViewState] consistent across edits.
 *
 * Two things have to hold and both are easy to get wrong: a toggle must not
 * touch the document, and after an edit the toggle must still apply to the same
 * block rather than to whatever block now starts at that offset.
 */
public object ToggleCoordinator {

    /** Block start offsets in a parsed document, in document order. */
    public fun blockStarts(blocks: List<BlockNode>): Set<Int> =
        blocks.flatMap { it.flatten().map { child -> child.range.start } }.toSet()

    /**
     * Applies an edit's effect to [view] and drops keys for blocks that are gone.
     *
     * @param offset where the edit happened
     * @param delta how much the document grew
     * @param validStarts the block starts in the *new* parse
     */
    public fun afterEdit(
        view: BlockViewState,
        offset: Int,
        delta: Int,
        validStarts: Set<Int>,
    ): BlockViewState {
        view.remapAfter(offset, delta)
        view.retainOnly(validStarts)
        return view
    }
}

/** This node and every descendant, in document order. */
public fun BlockNode.flatten(): List<BlockNode> = buildList {
    add(this@flatten)
    for (child in children) addAll(child.flatten())
}
