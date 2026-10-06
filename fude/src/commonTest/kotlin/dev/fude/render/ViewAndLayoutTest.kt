package dev.fude.render

import dev.fude.core.InlineRange
import dev.fude.core.Insert
import dev.fude.core.TextRange
import dev.fude.markdown.BlockViewState
import dev.fude.markdown.IncrementalMarkdownParser
import dev.fude.markdown.RenderMode
import dev.fude.markdown.ToggleCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // ------------------------------------------- remap collisions

    /**
     * The collision, with the offsets from the report.
     *
     * Key 13 sits after the edit at 10, so a deletion of 8 characters moves it to 5 —
     * where the block that did *not* move already lives. `Map.mapKeys` resolves that
     * silently, keeping the last of the two, so one block's mode vanishes.
     *
     * The two modes differ on purpose. With identical modes the loss is invisible, and
     * that is precisely why the eight pre-existing cases never caught this: they toggle
     * blocks to SOURCE, so every entry in the map holds the same value and dropping one
     * changes nothing an assertion could see.
     */
    @Test
    fun twoKeysLandingOnOneDoNotSilentlyDropAMode() {
        val view = BlockViewState(mapOf(5 to RenderMode.SOURCE, 13 to RenderMode.RENDERED))

        view.remapAfter(offset = 10, delta = -8)

        assertTrue(
            view.isSource(5),
            "the block at 5 survived the edit, so it keeps the mode the user set on it; got ${view.sourceBlocks()}",
        )
        assertEquals(setOf(5), view.sourceBlocks())
    }

    @Test
    fun twoCollisionsResolveIndependently() {
        // Two independent contests in one shift: key 11 lands on 3 and key 15 lands on 7.
        // Both stationary blocks were explicitly rendered; neither may end up showing
        // source because some block that the deletion destroyed used to hold it.
        val view = BlockViewState(
            mapOf(
                3 to RenderMode.RENDERED,
                7 to RenderMode.RENDERED,
                11 to RenderMode.SOURCE,
                15 to RenderMode.SOURCE,
            ),
        )

        view.remapAfter(offset = 10, delta = -8)

        assertFalse(view.isSource(3), "the live block at 3 keeps the mode it was given")
        assertFalse(view.isSource(7), "and so does the live block at 7")
        assertEquals(
            emptySet(),
            view.sourceBlocks(),
            "both source modes belonged to blocks the deletion removed",
        )
    }

    /**
     * The resolution must be visible, not silent.
     *
     * `mapKeys` had nowhere to say "I gave one of these up" — it just returned a smaller
     * map. A host that wants to notice that a toggle went missing has nothing to watch.
     */
    @Test
    fun aCollisionIsReportedRatherThanDecidedSilently() {
        val clean = BlockViewState(mapOf(5 to RenderMode.SOURCE, 20 to RenderMode.SOURCE))
        clean.remapAfter(offset = 10, delta = -8)
        assertEquals(0, clean.collisions, "a shift that contests nothing is not a collision")

        val oneContest = BlockViewState(mapOf(5 to RenderMode.SOURCE, 13 to RenderMode.SOURCE))
        oneContest.remapAfter(offset = 10, delta = -8)
        assertEquals(1, oneContest.collisions, "one mode was given up, so the host can be told")

        val twoContests = BlockViewState(
            mapOf(3 to RenderMode.SOURCE, 7 to RenderMode.SOURCE, 11 to RenderMode.SOURCE, 15 to RenderMode.SOURCE),
        )
        twoContests.remapAfter(offset = 10, delta = -8)
        assertEquals(2, twoContests.collisions, "contests are counted independently, not per call")
    }

    /**
     * Identical text in two blocks must not merge their modes.
     *
     * This is the judgement call content matching would have had to answer, and it is
     * why this file resolves collisions by offset instead: two blocks holding the same
     * string are, by definition, indistinguishable by content. Offsets are not
     * ambiguous here, and a toggle is anchored to the block the user clicked — so the
     * block that was at 5 is still the block at 10, whatever it now says.
     */
    @Test
    fun twoBlocksWithIdenticalTextKeepTheirOwnModes() {
        val doc = blocks("same\n\nsame")
        assertEquals(listOf(0, 6), doc.blocks.map { it.range.start })
        val view = BlockViewState()
        view.toggle(doc.blocks[0].range.start)
        view.set(doc.blocks[1].range.start, RenderMode.RENDERED)

        val edited = "# x\n\nsame\n\nsame"
        val reparsed = blocks(edited)
        ToggleCoordinator.afterEdit(view, 0, 5, ToggleCoordinator.blockStarts(reparsed.blocks))

        assertEquals(3, reparsed.blocks.size)
        assertTrue(
            reparsed.blocks.drop(1).all { edited.substring(it.range.start, it.range.end) == "same" },
            "the two blocks really are identical, or the test proves nothing",
        )
        assertTrue(view.isSource(reparsed.blocks[1].range.start), "the first block kept source")
        assertFalse(view.isSource(reparsed.blocks[2].range.start), "the second kept rendered")
    }

    /**
     * The acceptance case, end to end: a shrinking edit in the middle of a real
     * document, large enough to shift a key onto a key above it.
     *
     * "one\n\ntwo\n\nthree\n\nfour" has block starts at 0, 5, 10 and 17. Every mode is
     * set explicitly rather than toggled, because with four identical SOURCE values the
     * loss this fixes would be invisible to any assertion.
     *
     * The edit removes [6, 11) — the last two characters of "two" and the blank line
     * after it, which is a backspace with the caret parked at the end of a word. That
     * gives `offset` 6 and `delta` -5, and it is exactly enough to drag the key at 10
     * down onto the key at 5.
     */
    @Test
    fun aLargeNegativeDeltaKeepsTheLiveBlocksModes() {
        val text = "one\n\ntwo\n\nthree\n\nfour"
        val doc = blocks(text)
        assertEquals(listOf(0, 5, 10, 17), doc.blocks.map { it.range.start })

        val view = BlockViewState()
        view.set(0, RenderMode.SOURCE)
        view.set(5, RenderMode.RENDERED)
        view.set(10, RenderMode.SOURCE)
        view.set(17, RenderMode.SOURCE)

        val edited = text.removeRange(6, 11)
        assertEquals("one\n\nthree\n\nfour", edited)
        val reparsed = blocks(edited)
        assertEquals(listOf(0, 5, 12), reparsed.blocks.map { it.range.start })
        ToggleCoordinator.afterEdit(view, 6, -5, ToggleCoordinator.blockStarts(reparsed.blocks))

        assertTrue(view.isSource(0), "the block above the edit is untouched")
        assertFalse(
            view.isSource(5),
            "the block the user explicitly rendered must not inherit the destroyed block's source mode",
        )
        assertTrue(
            view.isSource(12),
            "'four' moved down intact, so its mode follows it",
        )
        assertEquals(
            setOf(0, 12),
            view.sourceBlocks(),
            "two of the three surviving blocks show source, and they are the two that should",
        )
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
