package dev.fude.render

import dev.fude.core.InlineRange
import dev.fude.core.Insert
import dev.fude.markdown.BlockViewState
import dev.fude.markdown.IncrementalMarkdownParser
import dev.fude.markdown.RenderMode
import dev.fude.markdown.ToggleCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The source/rendered toggle, which is a *view* concern.
 *
 * The property that matters: toggling must not change the document or mark it
 * modified. A host has to be able to flip every block in a note without the note
 * appearing edited, and a toggle that touched the text would defeat every
 * save-on-change check it has.
 */
class BlockToggleTest {
    private fun blocks(text: String) = IncrementalMarkdownParser().parse(text)

    @Test
    fun blocksDefaultToRendered() {
        val view = BlockViewState()
        assertTrue(view.isDefault)
        assertEquals(RenderMode.RENDERED, view.modeOf(0))
        assertFalse(view.isSource(0))
    }

    @Test
    fun togglingChangesOnlyTheView() {
        val text = "one\n\ntwo"
        val before = blocks(text)
        val view = BlockViewState()

        view.toggle(before.blocks[0].range.start)

        assertTrue(view.isSource(before.blocks[0].range.start))
        assertFalse(view.isSource(before.blocks[1].range.start), "only the toggled block changes")

        val after = blocks(text)
        assertEquals(before, after, "the parse is unchanged by a toggle")
        assertEquals(text, after.text, "and so is the document")
    }

    @Test
    fun togglingBackRestoresRendered() {
        val view = BlockViewState()
        val start = 7
        assertEquals(RenderMode.SOURCE, view.toggle(start))
        assertEquals(RenderMode.RENDERED, view.toggle(start))
        assertEquals(emptySet(), view.sourceBlocks(), "nothing is left showing source")
    }

    @Test
    fun eachBlockTogglesIndependently() {
        val text = "a\n\nb\n\nc"
        val doc = blocks(text)
        val view = BlockViewState()
        doc.blocks.forEach { view.toggle(it.range.start) }

        assertEquals(setOf(0, 3, 6), view.sourceBlocks(), "the real block starts, not assumed ones")
        assertEquals(3, view.sourceBlocks().size)
    }

    @Test
    fun anEditBelowALeavesTheToggleOnTheSameBlock() {
        val text = "alpha\n\nbeta\n\ngamma"
        val doc = blocks(text)
        val view = BlockViewState()
        val betaStart = doc.blocks[1].range.start
        view.toggle(betaStart)

        val target = text.length
        val edited = text + "extra"
        val reparsed = blocks(edited)
        val starts = ToggleCoordinator.blockStarts(reparsed.blocks)
        ToggleCoordinator.afterEdit(view, target, edited.length - text.length, starts)

        // The block that was "beta" is still showing source after typing below it.
        assertEquals(3, reparsed.blocks.size)
        val stillSource = view.sourceBlocks().single()
        assertEquals(
            reparsed.blocks[1].range.start,
            stillSource,
            "the toggle followed its block rather than its old offset",
        )
    }

    @Test
    fun anEditAboveShiftsTheToggleWithItsBlock() {
        val text = "alpha\n\nbeta"
        val doc = blocks(text)
        val view = BlockViewState()
        val betaStart = doc.blocks[1].range.start
        view.toggle(betaStart)

        val edited = "# x\n\n" + text
        val reparsed = blocks(edited)
        ToggleCoordinator.afterEdit(view, 0, 5, ToggleCoordinator.blockStarts(reparsed.blocks))

        assertTrue(view.isSource(reparsed.blocks[2].range.start), "the beta block still shows source")
        assertFalse(view.isSource(reparsed.blocks[1].range.start))
    }

    @Test
    fun togglesForDeletedBlocksAreDropped() {
        val text = "alpha\n\nbeta"
        val doc = blocks(text)
        val view = BlockViewState()
        view.toggle(doc.blocks[1].range.start)

        val edited = "alpha"
        val reparsed = blocks(edited)
        ToggleCoordinator.afterEdit(view, 5, -5, ToggleCoordinator.blockStarts(reparsed.blocks))

        assertTrue(view.isDefault, "a stale toggle is dropped rather than left behind")
    }

    @Test
    fun blockStartsCoverEveryNodeInTheTree() {
        val text = "- one\n    - two\n\n> quote\n\n| a |\n|---|\n| 1 |"
        val doc = blocks(text)
        val starts = ToggleCoordinator.blockStarts(doc.blocks)
        for (block in doc.blocks.flatMap { listOf(it) + it.children }) {
            assertTrue(block.range.start in starts, "${block.kind} start should be a key")
        }
    }
}

/**
 * Layout cache.
 *
 * Keyed by content hash rather than by block offset, because after an edit above
 * a block its offset changes and its content does not. A cache keyed on offset
 * would miss on every keystroke — which, at 116 ms measured for the layout alone
 * in the spike, is the difference between usable and not.
 */
class LayoutCacheTest {
    private fun parse(text: String) = IncrementalMarkdownParser().parse(text)
    private val styleHash = 12345

    @Test
    fun aMissIsRecordedAndThenAHit() {
        val cache = LayoutCache()
        val doc = parse("one\n\ntwo")
        val key = cache.keyFor(doc.blocks[0], doc.text, styleHash, width = 800, renderModeHash = 0)

        assertEquals(null, cache.get(key))
        assertEquals(1, cache.misses)

        cache.put(key, BlockLayout(dev.fude.core.TextRange(0, 3), 20f, 0f))
        assertEquals(20f, cache.get(key)?.height)
        assertEquals(1, cache.hits)
    }

    @Test
    fun theSameBlockAfterAnEditBelowItStillHits() {
        val text = "alpha\n\nbeta"
        val cache = LayoutCache()
        val doc = parse(text)
        val key = cache.keyFor(doc.blocks[0], doc.text, styleHash, 800, 0)
        cache.put(key, BlockLayout(dev.fude.core.TextRange(0, 5), 20f, 0f))

        val edited = text + "extra"
        val reparsed = parse(edited)
        val keyAfter = cache.keyFor(reparsed.blocks[0], reparsed.text, styleHash, 800, 0)

        assertEquals(key, keyAfter, "an edit below does not change this block's identity")
        assertEquals(20f, cache.get(keyAfter)?.height, "so the layout is reused")
    }

    @Test
    fun anEditInsideABlockInvalidatesIt() {
        val text = "alpha\n\nbeta"
        val cache = LayoutCache()
        val doc = parse(text)
        val key = cache.keyFor(doc.blocks[0], doc.text, styleHash, 800, 0)

        val edited = "ALPHA\n\nbeta"
        val reparsed = parse(edited)
        val keyAfter = cache.keyFor(reparsed.blocks[0], reparsed.text, styleHash, 800, 0)

        assertTrue(key != keyAfter, "changed content means a different key")
    }

    @Test
    fun aDifferentWidthBucketInvalidates() {
        val doc = parse("alpha")
        val cache = LayoutCache()
        val a = cache.keyFor(doc.blocks[0], doc.text, styleHash, width = 800, renderModeHash = 0)
        val b = cache.keyFor(doc.blocks[0], doc.text, styleHash, width = 801, renderModeHash = 0)
        assertEquals(a, b, "a one-pixel resize is bucketed away")
    }

    @Test
    fun aDifferentStyleOrRenderModeInvalidates() {
        val doc = parse("alpha")
        val cache = LayoutCache()
        val base = cache.keyFor(doc.blocks[0], doc.text, styleHash, 800, 0)
        assertTrue(base != cache.keyFor(doc.blocks[0], doc.text, 999, 800, 0), "style matters")
        assertTrue(base != cache.keyFor(doc.blocks[0], doc.text, styleHash, 800, 1), "render mode matters")
    }

    @Test
    fun theCacheIsBounded() {
        val cache = LayoutCache(maxEntries = 3)
        val doc = parse("a\n\nb\n\nc\n\nd\n\ne")
        doc.blocks.forEach { block ->
            cache.put(
            cache.keyFor(block, doc.text, styleHash, 800, 0),
            BlockLayout(dev.fude.core.TextRange(block.range.start, block.range.end), 10f, 0f),
        )
        }
        assertEquals(3, cache.size, "older entries fall off rather than growing without limit")
    }

    @Test
    fun invalidationDropsOnlyChangedBlocks() {
        val text = "alpha\n\nbeta\n\ngamma"
        val doc = parse(text)
        val cache = LayoutCache()
        val keys = doc.blocks.map { cache.keyFor(it, doc.text, styleHash, 800, 0) }
        keys.forEach { cache.put(it, BlockLayout(dev.fude.core.TextRange(0, 1), 10f, 0f)) }

        // The middle block changed; the other two are still valid.
        val edited = "alpha\n\nBETA\n\ngamma"
        val reparsed = parse(edited)
        val newKeys = reparsed.blocks.map { cache.keyFor(it, reparsed.text, styleHash, 800, 0) }
        cache.invalidateAllExcept(newKeys.map { it.contentHash }.toSet())

        assertEquals(2, cache.size, "one block invalidated, two kept")
    }

    @Test
    fun contentHashIsStableForUnchangedText() {
        val text = "alpha\n\nbeta"
        val doc = parse(text)
        val cache = LayoutCache()
        val a = cache.contentHashOf(doc.blocks[0], doc.text)
        val b = cache.contentHashOf(doc.blocks[0], doc.text)
        assertEquals(a, b)
        assertTrue(a != cache.contentHashOf(doc.blocks[1], doc.text), "different blocks differ")
    }
}

/**
 * Visual-to-source mapping.
 *
 * The cases here are the ones that break naive implementations: a link renders
 * its label rather than its target, and a list item renders a marker the user
 * never typed. Getting either wrong selects the wrong text, and the error is
 * invisible until someone tries to select something.
 */
class VisualMappingTest {
    @Test
    fun aPlainRunMapsOneToOne() {
        val mapper = VisualToSourceMapper(
            listOf(
                VisualToSourceMapper.VisualRun(InlineRange(0, 1)),
                VisualToSourceMapper.VisualRun(InlineRange(1, 2)),
                VisualToSourceMapper.VisualRun(InlineRange(2, 3)),
            ),
        )
        val text = "abc"
        assertEquals(InlineRange(1, 2), mapper.sourceAt(1))
        assertEquals(1..1, mapper.visualOf(InlineRange(1, 2)))
        assertEquals("b", mapper.sourceTextFor(1..1, text))
    }

    @Test
    fun aRenderedLinkMapsToItsLabelNotItsDestination() {
        // "[label](https://example.com)" renders five characters of label over a
        // much longer source span. A one-to-one mapping would select the URL.
        val mapper = VisualToSourceMapper(
            listOf(
                VisualToSourceMapper.VisualRun(InlineRange(1, 6)),
            ),
        )
        val text = "[label](https://example.com)"
        assertEquals(InlineRange(1, 6), mapper.sourceAt(0))
        assertEquals("label", mapper.sourceTextFor(0..0, text))
        assertEquals(0..0, mapper.visualOf(InlineRange(1, 6)))
    }

    @Test
    fun aListMarkerWithNoSourceContributesNothing() {
        // The bullet is rendered but the user never typed it, so it must not appear
        // in a selection's source text.
        val mapper = VisualToSourceMapper(
            listOf(
                VisualToSourceMapper.VisualRun(InlineRange.ZERO),
                VisualToSourceMapper.VisualRun(InlineRange(2, 6)),
            ),
        )
        val text = "- item"
        assertEquals(InlineRange(2, 6), mapper.sourceAt(1))
        assertEquals("item", mapper.sourceTextFor(0..1, text), "the bullet is not part of the selection")
    }

    @Test
    fun aSelectionSpanningSeveralRunsConcatenatesTheirSource() {
        val mapper = VisualToSourceMapper(
            listOf(
                VisualToSourceMapper.VisualRun(InlineRange(0, 1)),
                VisualToSourceMapper.VisualRun(InlineRange(1, 2)),
                VisualToSourceMapper.VisualRun(InlineRange(2, 3)),
            ),
        )
        assertEquals("abc", mapper.sourceTextFor(0..2, "abc"))
    }

    @Test
    fun aCollapsedVisualRangeResolvesToTheCharacterAtThatRun() {
        val mapper = VisualToSourceMapper(
            listOf(
                VisualToSourceMapper.VisualRun(InlineRange(0, 1)),
                VisualToSourceMapper.VisualRun(InlineRange(1, 2)),
            ),
        )
        // A collapsed range has first > last, so `first` is the run index.
        assertEquals("b", mapper.sourceTextFor(1..0, "abc"), "a collapsed caret resolves to one character")
    }

    @Test
    fun outOfRangeVisualOffsetsClamp() {
        val mapper = VisualToSourceMapper(listOf(VisualToSourceMapper.VisualRun(InlineRange(0, 3))))
        assertEquals(InlineRange(0, 3), mapper.sourceAt(99))
    }
}
