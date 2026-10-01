package dev.fude.render

import dev.fude.core.TextRange
import dev.fude.markdown.BlockNode
import dev.fude.markdown.BlockViewState

/**
 * A cached layout for one block.
 *
 * Keyed by content hash, style and viewport width rather than by block identity,
 * because after an edit above a block its *offset* changes but its content does
 * not — and a layout that survived would be correct. Keying on offset alone
 * would invalidate every block below a keystroke.
 */
public data class LayoutKey(
    val contentHash: Int,
    val textStyleHash: Int,
    val widthBucket: Int,
    val renderModeHash: Int,
)

/**
 * Per-block layout cache.
 *
 * Exists because the spike measured a keystroke into a 5,000-line note at 116 ms
 * with no decoration and 196 ms with a per-frame decoration reparse. Neither is
 * near a frame, and the 80 ms delta is Fude's own decoration work — so the budget
 * for decoration is small and layout has to not make it worse.
 *
 * A reparse invalidates one entry. Scroll and caret mapping read the cache and
 * never trigger a reparse; that separation is what keeps the two concerns from
 * feeding each other.
 */
public class LayoutCache(private val maxEntries: Int = 512) {
    private val entries = LinkedHashMap<LayoutKey, BlockLayout>()

    /** How many times a layout was computed rather than reused. */
    public var misses: Int = 0
        private set

    /** How many times a cached layout was reused. */
    public var hits: Int = 0
        private set

    public val size: Int get() = entries.size

    /** The cached layout for [key], or null. */
    public fun get(key: LayoutKey): BlockLayout? {
        val found = entries.remove(key)
        if (found == null) {
            misses++
            return null
        }
        // Re-insert to keep the access order for eviction.
        entries[key] = found
        hits++
        return found
    }

    public fun put(key: LayoutKey, layout: BlockLayout) {
        entries[key] = layout
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    /** The key a block's layout is cached under. */
    public fun keyFor(
        block: BlockNode,
        text: CharSequence,
        textStyleHash: Int,
        width: Int,
        renderModeHash: Int,
    ): LayoutKey = LayoutKey(
        contentHash = contentHashOf(block, text),
        textStyleHash = textStyleHash,
        // Bucket the width so a one-pixel resize does not invalidate everything.
        widthBucket = width / 8,
        renderModeHash = renderModeHash,
    )

    /**
     * A hash of the block's source.
     *
     * Deliberately simple rather than cryptographic: this detects "did this text
     * change", and a collision costs a re-layout, not a wrong document.
     */
    public fun contentHashOf(block: BlockNode, text: CharSequence): Int {
        var hash = block.range.length
        hash = 31 * hash + block.kind.hashCode()
        // Sample the content rather than hashing every character: a 5,000-line
        // block would otherwise cost more to hash than to lay out.
        val step = (block.range.length / 32).coerceAtLeast(1)
        var i = block.range.start
        while (i < block.range.end) {
            hash = 31 * hash + text[i].code
            i += step
        }
        return hash
    }

    /**
     * Drops every entry whose content hash no longer appears in [validHashes].
     *
     * One changed block invalidates one entry. Everything else survives, which is
     * the whole reason the cache is keyed by content rather than by position.
     */
    public fun invalidateAllExcept(validHashes: Set<Int>) {
        entries.keys.filter { it.contentHash !in validHashes }.forEach { entries.remove(it) }
    }

    public fun clear() {
        entries.clear()
        misses = 0
        hits = 0
    }

    public fun resetStats() {
        misses = 0
        hits = 0
    }
}

/** What the renderer needs to know about a laid-out block. */
public data class BlockLayout(
    val blockRange: TextRange,
    /** Height in pixels. */
    val height: Float,
    /** Top offset relative to the document. */
    val top: Float,
    /** How many visual lines the block occupies. */
    val visualLines: Int = 1,
)
