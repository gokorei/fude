package dev.fude.markdown

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.fude.core.InlineRange

/**
 * Which side of the source/rendered toggle a block is showing.
 *
 * This is Obsidian's central idea and it is worth copying deliberately: a block
 * is either showing its syntax or showing its result, and the user toggles
 * between them. "Always rendered" hides the syntax; "always source" is not live
 * preview at all.
 */
enum class RenderMode {
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
@Stable
class BlockViewState(
    initial: Map<Int, RenderMode> = emptyMap(),
) {
    private var modes: Map<Int, RenderMode> by mutableStateOf(initial.toMap())

    /**
     * How many times a [remapAfter] shift sent two blocks to the same key, and one of
     * them had to be given up.
     *
     * Cumulative, and the only way a host learns that a mode was lost rather than
     * deliberately dropped. It should stay at zero: a non-zero value means two blocks
     * collided and the resolution below decided which one was real.
     */
    var collisions: Int = 0
        private set

    /** The mode for the block starting at [blockStart]. Rendered by default. */
    fun modeOf(blockStart: Int): RenderMode = modes[blockStart] ?: RenderMode.RENDERED

    fun isSource(blockStart: Int): Boolean = modeOf(blockStart) == RenderMode.SOURCE

    /**
     * Flips the block starting at [blockStart] and returns its new mode.
     *
     * A view change only: the document is untouched.
     */
    fun toggle(blockStart: Int): RenderMode {
        val next = if (isSource(blockStart)) RenderMode.RENDERED else RenderMode.SOURCE
        modes = modes + (blockStart to next)
        return next
    }

    fun set(blockStart: Int, mode: RenderMode) {
        modes = modes + (blockStart to mode)
    }

    /** Every block currently showing source. */
    fun sourceBlocks(): Set<Int> = modes.filterValues { it == RenderMode.SOURCE }.keys

    val isDefault: Boolean get() = modes.isEmpty()

    fun clear() {
        modes = emptyMap()
    }

    /**
     * Shifts keys after an edit at [offset] that grew the document by [delta].
     *
     * Only blocks at or after the edit move. The block containing the edit keeps
     * its key, so a toggle does not silently apply to a different block because
     * the user typed above it.
     *
     * **When two keys land on one, the block that did not move wins.**
     *
     * Two blocks cannot share a key — [modeOf] would have to answer for both — so a
     * contest has to be resolved rather than avoided. `Map.mapKeys`, which this used to
     * be, resolved it by keeping the last entry and discarding the rest with no comment
     * at all, and the entry it kept was the *moving* one. That is the wrong one, and it
     * is wrong by necessity rather than by tie-break:
     *
     * A collision needs some key at or after [offset] to land on a key before it, which
     * for a single edit means [delta] is negative and the deleted span runs from
     * [offset] *past* the moving block's start. So the moving block's leading characters
     * are inside the deletion, and whatever survives of it begins at [offset] rather than
     * at the contested key. Meanwhile everything before [offset] is byte-identical, so the
     * only block that can begin at the contested offset is the one that did not move —
     * possibly truncated, possibly grown, but still the same block at the same offset.
     *
     * The stationary block is therefore the real survivor and the moving block's mode is
     * genuinely stale. Keeping it would put a deleted block's mode on a live one, which is
     * how a block that the user never touched ends up showing source. Every contest is
     * counted in [collisions] rather than settled in silence.
     *
     * **Modes follow blocks, not text.** If two blocks swap identical content, or one
     * block's text is replaced wholesale with another's, each block keeps its own mode:
     * the toggle is anchored to the block the user clicked, and re-deriving it from
     * content would make a block's appearance depend on whether some *other* block
     * happened to hold the same string. That case is also the reason the resolution above
     * is by offset — two blocks holding identical text are, by definition, indistinguishable
     * by content, and duplicate paragraphs are ordinary in a note.
     */
    fun remapAfter(offset: Int, delta: Int) {
        if (delta == 0) return
        val remapped = LinkedHashMap<Int, RenderMode>(modes.size)
        for ((key, mode) in modes) {
            val target = if (key >= offset) key + delta else key
            if (target !in remapped) {
                remapped[target] = mode
            } else {
                // Two blocks, one key. Shifting is injective over keys at or after the
                // edit, so the entry already here can only be a *stationary* block, and
                // this entry is the moving one — unless `target == key`, in which case
                // this entry is the stationary block arriving second and it takes the key.
                collisions++
                if (target == key) remapped[target] = mode
            }
        }
        modes = remapped
    }

    /** Drops keys for blocks that no longer exist. */
    fun retainOnly(validStarts: Set<Int>) {
        modes = modes.filterKeys { it in validStarts }
    }

    fun copy(): BlockViewState = BlockViewState(modes)

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
object ToggleCoordinator {

    /** Block start offsets in a parsed document, in document order. */
    fun blockStarts(blocks: List<BlockNode>): Set<Int> =
        blocks.flatMap { it.flatten().map { child -> child.range.start } }.toSet()

    /**
     * Applies an edit's effect to [view] and drops keys for blocks that are gone.
     *
     * The order matters and is not interchangeable. [BlockViewState.remapAfter] has to
     * run first because it produces keys in the *new* document's coordinates, and
     * [validStarts] is expressed in those same coordinates; retaining first would test
     * unshifted keys against the new parse and discard every block below the edit.
     *
     * Retaining second is also what resolves the contest described on [BlockViewState.remapAfter]
     * in the common case: after the remap, a key that no longer names a block is dropped
     * whether it was shifted or not.
     *
     * @param offset where the edit happened
     * @param delta how much the document grew
     * @param validStarts the block starts in the *new* parse
     */
    fun afterEdit(
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

/** This node and every descendant, in document order. Single canonical traversal; see [flattenBlocks]. */
fun BlockNode.flatten(): List<BlockNode> = listOf(this).flattenBlocks()
