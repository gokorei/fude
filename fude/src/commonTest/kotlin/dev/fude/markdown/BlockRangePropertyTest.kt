package dev.fude.markdown

import dev.fude.core.Insert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The invariant every block range in every document must satisfy.
 *
 * Written as a property rather than as more examples because **five separate range
 * defects got through the example-based suite**: a table swallowing a thematic break,
 * a code fence and a list item each claiming the blank line after them, a phantom
 * paragraph synthesised from the document's final newline, and a shifted tail that
 * dropped every block below an edit. Each was found by hand, one at a time, by
 * reading a stack trace or printing a tree. Every one of them violates a rule that a
 * single assertion would have caught.
 *
 * So the rule is stated once here and checked over a matrix of every block type
 * against every follower, rather than being re-derived per defect.
 */
class BlockRangePropertyTest {

    /**
     * Shared with the exhaustive reparse sweep. The two matrices used to be copies, and
     * the divergence was documented as intentional -- which was defensible with one copy
     * and one consumer, and stopped being defensible with three. A fixture added here
     * now reaches both.
     *
     * Two entries are in the shared set but not applied to every invariant here: an
     * unclosed fence swallows its followers as content, so a matrix pair whose *first*
     * block is one has no second block to assert about, and a host block only exists
     * when a `SyntaxExtension` is registered. Both are covered by the sweep, which is
     * the point of sharing rather than copying.
     */
    private val blocks = BLOCK_FIXTURES

    /**
     * Every block's range must cover the source the author actually wrote.
     *
     * The other invariants in this file are about ranges being *disjoint*, staying
     * inside the document, owning no trailing blank line and ending on no terminator.
     * All four held while a tab-indented list item's inline content started one past the
     * end of the item holding a single character: `indentOf` counts a tab as four columns,
     * which is right for the nesting question and wrong for a character offset, and the
     * two were added together to compute one. The tree looked structurally perfect and
     * indexed the wrong characters, so nothing threw, no range escaped the document, and
     * decoration was applied to the wrong place.
     *
     * That is the gap this closes: **disjointness is not correctness.** Two blocks can be
     * disjoint and both misaligned, and a range can be inside the document and cover the
     * wrong text inside it.
     *
     * The assertion is deliberately about content rather than about a specific offset, so
     * it states the property rather than pinning today's arithmetic. It reads the text
     * back out of the document through each node's own range and requires that what comes
     * out is what went in — for a block, for every inline that has text, and for a list
     * item's own run.
     */
    @Test
    fun everyBlockCoversTheSourceTheAuthorWrote() {
        for ((name, block) in blocks) {
            for ((followerName, follower) in blocks) {
                for (separator in listOf("\n", "\n\n")) {
                    val text = block.trimEnd('\n') + separator + follower
                    val parsed = IncrementalMarkdownParser().parse(text)
                    val label = "$name then $followerName separated by '${separator.replace("\n", "\\n")}'"

                    for (node in parsed.allBlocks) {
                        val covered = text.substring(node.range.start, node.range.end)
                        assertTrue(
                            covered.isNotBlank(),
                            "$label: ${node.kind} at ${node.range} covers only whitespace",
                        )
                        // An inline that carries text must cover exactly that text. This is
                        // the assertion the tab-indented list failed: its single TextNode
                        // claimed `"m"` — the last character of the item — while claiming
                        // to be the item's content.
                        for (inline in node.inlines) {
                            val span = text.substring(inline.range.start, inline.range.end)
                            when (inline) {
                                is TextNode -> assertEquals(
                                    inline.text,
                                    span,
                                    "$label: ${node.kind}'s TextNode at ${inline.range} " +
                                        "claims to be \"${inline.text}\" but covers \"$span\"",
                                )

                                is CodeSpanNode -> assertEquals(
                                    inline.code,
                                    span.removePrefix("`").removeSuffix("`"),
                                    "$label: ${node.kind}'s CodeSpanNode at ${inline.range} " +
                                        "does not cover its own delimiters and content",
                                )

                                else -> assertTrue(
                                    inline.range.start >= 0 && inline.range.end <= text.length,
                                    "$label: ${node.kind}'s ${inline::class.simpleName} at " +
                                        "${inline.range} escapes the document",
                                )
                            }
                        }
                        // A list item's own inline run must lie inside the item and inside
                        // the block, which is what "decoration lands on the item's text"
                        // means in practice.
                        if (node is ListItemNode) {
                            for (inline in node.inlines) {
                                assertTrue(
                                    inline.range.start >= node.range.start &&
                                        inline.range.end <= node.range.end,
                                    "$label: ${node.kind} [${node.range}] has content at " +
                                        "${inline.range}, outside the item it belongs to",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Every block followed by every other block, separated by a blank line.
     *
     * The blank line is the interesting part: it belongs to neither block, and it is
     * exactly the character each of the five defects swallowed.
     */
    @Test
    fun noBlockOverlapsItsNeighbour() {
        var checked = 0
        for ((firstName, first) in blocks) {
            for ((secondName, second) in blocks) {
                val text = first + "\n" + second
                val parsed = IncrementalMarkdownParser().parse(text)
                val label = "$firstName then $secondName"

                assertNoOverlap(parsed, label, text)
                checked += parsed.blocks.size
            }
        }
        assertTrue(checked > 40, "the matrix should cover a lot of blocks, covered $checked")
    }

    /**
     * The same invariant over a realistic document rather than a synthetic pair.
     *
     * Pairwise construction proves the rule for two blocks. A whole note is where a
     * range goes wrong relative to something five blocks away, which is what breaks
     * `blockAt` and the layout cache.
     */
    @Test
    fun noBlockOverlapsItsNeighbourInARealisticDocument() {
        val text = """
            # A note

            An opening paragraph with **bold**, `code` and a [link](https://example.com).

            ## A section

            - one
            - two
                - nested

            > a quote
            >
            > > nested quote

            ```kotlin
            fun main() = Unit
            ```

            | a | b |
            |---|---|
            | 1 | 2 |

            ---

            A closing paragraph.

            ## Another section

            - trailing item
        """.trimIndent() + "\n"

        val parsed = IncrementalMarkdownParser().parse(text)
        assertNoOverlap(parsed, "realistic document", text)

        // And the ranges must actually spell the right source, which overlap alone
        // would not catch: two blocks can be disjoint and both misaligned.
        for (block in parsed.blocks) {
            val covered = text.substring(block.range.start, block.range.end)
            assertTrue(
                covered.isNotBlank(),
                "${block.kind} at ${block.range} covers only whitespace",
            )
            assertTrue(
                !covered.endsWith("\n\n"),
                "${block.kind} at ${block.range} ends in a blank line it does not own: '$covered'",
            )
        }
    }

    /**
     * Every top-level range must lie inside the document.
     *
     * Cheap, and it is the invariant that was violated when the incremental reparse
     * shifted a tail below zero and clamped it to `(0, 0)` instead of reporting that
     * it no longer fit.
     */
    @Test
    fun noRangeEscapesTheDocument() {
        for ((name, block) in blocks) {
            for ((followerName, follower) in blocks) {
                val text = block + "\n" + follower
                val parsed = IncrementalMarkdownParser().parse(text)
                for (node in parsed.allBlocks) {
                    assertTrue(
                        node.range.start >= 0 && node.range.end <= text.length,
                        "$name then $followerName: ${node::class.simpleName} at ${node.range} " +
                            "escapes a ${text.length}-char document",
                    )
                }
            }
        }
    }

    /**
     * `blockAt` resolves by falling back to "the last block starting at or before
     * this offset". That makes an over-extended range silently misroute every offset
     * it covers, which is the consequence that surfaces much later as caret
     * misplacement rather than as anything visibly wrong.
     */
    @Test
    fun blockAtResolvesToABlockThatContainsTheOffset() {
        for ((name, block) in blocks) {
            for ((followerName, follower) in blocks) {
                val text = block + "\n" + follower
                val parsed = IncrementalMarkdownParser().parse(text)
                for (offset in 0..text.length) {
                    val resolved = parsed.blockAt(offset) ?: continue
                    assertTrue(
                        offset >= resolved.range.start,
                        "$name then $followerName: offset $offset resolved to a block " +
                            "starting at ${resolved.range.start}",
                    )
                }
            }
        }
    }

    private fun assertNoOverlap(parsed: ParsedDocument, label: String, text: String) {
        val top = parsed.blocks
        for (i in 0 until top.size - 1) {
            val here = top[i]
            val next = top[i + 1]
            assertTrue(
                here.range.end <= next.range.start,
                "$label: ${here.kind} [${here.range.start},${here.range.end}) overlaps " +
                    "${next.kind} [${next.range.start},${next.range.end}) in " +
                    text.replace("\n", "\\n"),
            )
        }
    }

    /**
     * The same invariant when the two blocks are **not** separated by a blank line.
     *
     * A table's rows must run straight into the next block, and a fence's closing
     * marker abuts what follows. This is where the table bug actually lived: a bare
     * `---` is lexically close enough to a table separator row that the table's
     * continuation rule accepted it as another row.
     */
    @Test
    fun noBlockOverlapsItsNeighbourWithoutABlankLine() {
        for ((firstName, first) in blocks) {
            for ((secondName, second) in blocks) {
                val text = first.trimEnd('\n') + "\n" + second
                val parsed = IncrementalMarkdownParser().parse(text)
                assertNoOverlap(parsed, "$firstName abutting $secondName", text)
            }
        }
    }

    /**
     * A block must not own a blank line that separates it from what follows.
     *
     * Asserted separately from non-overlap because two adjacent blocks can be disjoint
     * and both still claim the same blank line — which is what happened to lists and
     * code fences, and what `blockAt` then misroutes.
     */
    @Test
    fun noBlockOwnsTheBlankLineAfterIt() {
        for ((firstName, first) in blocks) {
            for ((secondName, second) in blocks) {
                val text = first + "\n\n" + second
                val parsed = IncrementalMarkdownParser().parse(text)
                for (block in parsed.blocks.dropLast(1)) {
                    val covered = text.substring(block.range.start, block.range.end)
                    assertTrue(
                        !covered.endsWith("\n\n"),
                        "$firstName then $secondName: ${block.kind} at ${block.range} owns " +
                            "the blank line after it: '${covered.replace("\n", "\\n")}'",
                    )
                }
            }
        }
    }

    /**
     * No block's range may *end* on a line terminator.
     *
     * Added after three tests in `LineEndingAndBlockExtentTest` failed while this file
     * was green — which is the whole point of recording it. The earlier assertions here
     * checked non-overlap and "does not end in a blank line", and a block ending on a
     * *single* trailing newline slips between those two. It reads as a weaker version
     * of what was already covered, so it is easy to leave out, and it is exactly the
     * shape the list-item and code-fence defects took.
     *
     * A terminator belongs to the gap between blocks. The one exception is a fenced
     * block's closing marker, which is content rather than separator — so this only
     * constrains the offset the range ends *at*.
     */
    @Test
    fun noBlockEndsOnALineTerminator() {
        for ((firstName, first) in blocks) {
            for ((secondName, second) in blocks) {
                val text = first + "\n\n" + second
                val parsed = IncrementalMarkdownParser().parse(text)
                // `allBlocks`, not `blocks`: two of the three failures this assertion
                // was written for are nested — a list item and a table cell — and a
                // top-level-only check passes straight over them.
                for (block in parsed.allBlocks) {
                    val end = block.range.end
                    assertTrue(
                        end == 0 || !isTerminator(text[end - 1]),
                        "$firstName then $secondName: ${block.kind} at ${block.range} ends on " +
                            "the terminator '${text[end - 1]}', covered " +
                            "'${text.substring(block.range.start, end).replace("\n", "\\n")}'",
                    )
                }
            }
        }
    }

    /** The same rule for a block that is the last thing in the document. */
    @Test
    fun noBlockEndsOnALineTerminatorAtTheEndOfTheDocument() {
        for ((name, block) in blocks) {
            for (trailer in listOf("\n", "\n\n", "\n\n\n", "")) {
                val text = block + trailer
                val parsed = IncrementalMarkdownParser().parse(text)
                val last = parsed.allBlocks.lastOrNull() ?: continue
                val end = last.range.end
                assertTrue(
                    end == 0 || !isTerminator(text[end - 1]),
                    "$name with trailer '${trailer.replace("\n", "\\n")}': ${last.kind} at " +
                        "${last.range} ends on a terminator",
                )
            }
        }
    }

    private fun isTerminator(c: Char): Boolean = c == '\n' || c == '\r'

    /**
     * An unclosed fence must not claim the line terminator at the end of the document.
     *
     * The `blocks` matrix also carries an unclosed fence, which is what makes
     * [noBlockEndsOnALineTerminator] and [noBlockEndsOnALineTerminatorAtTheEndOfTheDocument]
     * fail without the fix. This test is not redundant with them: the matrix checks one
     * shape against every follower, and this sweeps the *shape* itself — info strings,
     * multi-line and empty content, and four different trailers. A partial fix that
     * handled ````kotlin` but not an empty info string, or trimmed one newline but not
     * three, would pass the matrix and fail here.
     *
     * The asymmetry it pins is between the two branches of `endOfFence`. A **closed**
     * fence returns its closing marker's `lineEnd`, deliberately stopping short of the
     * terminator that follows, with a comment saying that terminator is the gap between
     * blocks and that a range reaching into it makes `blockAt` misroute offsets there.
     * An **unclosed** fence returned `end` unchanged, which on a document ending in a
     * newline put the range one past the last character of the code — the exact defect
     * the closed branch documents itself as avoiding.
     */
    @Test
    fun noUnclosedFenceClaimsATrailingTerminator() {
        for (info in listOf("", "kotlin", "text")) {
            for (content in listOf("val x = 1", "one\ntwo", "")) {
                for (trailer in listOf("\n", "\n\n", "\n\n\n", "")) {
                    val open = if (info.isEmpty()) "```" else "```$info"
                    val text = open + "\n" + content + trailer
                    val label = "info='$info' content='${content.replace("\n", "\\n")}' " +
                        "trailer='${trailer.replace("\n", "\\n")}'"

                    val parsed = IncrementalMarkdownParser().parse(text)
                    val fence = parsed.allBlocks.filterIsInstance<CodeFenceNode>().firstOrNull()
                    assertNotNull(fence, "$label: no fence parsed from '$text'")

                    val end = fence.range.end
                    assertTrue(
                        end == 0 || !isTerminator(text[end - 1]),
                        "$label: the unclosed fence at ${fence.range} ends on the terminator " +
                            "'${text[end - 1]}', claiming a gap that belongs to no block",
                    )
                }
            }
        }
    }

    /**
     * The coverage check the rest of this file was missing, over `reparse`.
     *
     * Every test above calls `parse`. That left a whole failure mode unobservable: a
     * tree can satisfy non-overlap, stay inside the document, own no trailing blank
     * line and still have a **hole** in it. `"# H\n\npara\n"` with the blank line
     * deleted reparse to `[HEADING 0..3, PARAGRAPH 5..8]` — no overlap, every range
     * inside the document, neither range ending on a terminator — and the `p` of `para`
     * at offset 4 belongs to no block at all. `blockAt` then falls back to "the last
     * block starting at or before this offset" and resolves offset 4 to the **heading**.
     *
     * That is the defect a range invariant cannot see, because it is an invariant about
     * the *union* of the ranges rather than about any one of them. So this asserts the
     * union: for every offset of the document, `blockAt` must resolve to the same block
     * kind a fresh `parse` resolves it to, and that block must actually contain the
     * offset. Run through `reparse` at every offset, since that is the path that had
     * the hole.
     */
    @Test
    fun afterAnySingleCharacterEditEveryOffsetStillResolvesToTheBlockThatContainsIt() {
        for ((firstName, first) in blocks) {
            for ((secondName, second) in blocks) {
                val original = first + "\n" + second
                for (offset in 0..original.length) {
                    val edited = original.substring(0, offset) + "X" + original.substring(offset)
                    val incremental = IncrementalMarkdownParser().let { parser ->
                        parser.parse(original)
                        parser.reparse(edited, Insert(offset, "X"))
                    }
                    val fromScratch = IncrementalMarkdownParser().parse(edited)
                    val label = "$firstName then $secondName, insert X at $offset"

                    for (at in 0..edited.length) {
                        val expected = fromScratch.blockAt(at)
                        val actual = incremental.blockAt(at)
                        assertEquals(
                            expected?.range,
                            actual?.range,
                            "$label: offset $at resolves to $actual but a full parse says " +
                                "$expected in '${original.replace("\n", "\\n")}'",
                        )
                        // Only offsets *inside* a block must resolve to a block that
                        // contains them. An offset in the gap between two blocks is a
                        // different question — `blockAt` answers it by falling back to
                        // the block above, deliberately, and `blockAtResolvesToABlockThat
                        // ContainsTheOffset` above pins that. What must never happen is
                        // an offset in the middle of a block's content resolving to some
                        // other block, which is the `"ara"` case.
                        if (expected == null || at < expected.range.start || at >= expected.range.end) continue
                        assertTrue(
                            actual != null && at >= actual.range.start && at <= actual.range.end,
                            "$label: offset $at is inside ${expected.kind} ${expected.range} " +
                                "but resolved to ${actual?.kind} ${actual?.range}, which does " +
                                "not contain it\n  full parse:  " +
                                "${fromScratch.blocks.map { "${it.kind}${it.range}" }}\n" +
                                "  incremental: ${incremental.blocks.map { "${it.kind}${it.range}" }}",
                        )
                    }
                }
            }
        }
    }

    /**
     * No character of a document's content may fall outside every block's range.
     *
     * The complement of the test above, and stricter: this walks each block's *content*
     * rather than every offset, so it says something about the union of the ranges
     * instead of about `blockAt`'s fallback. It is the assertion the `"ara"` case was
     * invisible to.
     */
    @Test
    fun afterAnySingleCharacterEditEveryBlocksContentIsCoveredByThatBlock() {
        for ((firstName, first) in blocks) {
            for ((secondName, second) in blocks) {
                val original = first + "\n" + second
                for (offset in 0..original.length) {
                    val edited = original.substring(0, offset) + "X" + original.substring(offset)
                    val reparsed = IncrementalMarkdownParser().let { parser ->
                        parser.parse(original)
                        parser.reparse(edited, Insert(offset, "X"))
                    }
                    val fromScratch = IncrementalMarkdownParser().parse(edited)
                    val label = "$firstName then $secondName, insert X at $offset"

                    val expected = fromScratch.blocks.map { it.range }
                    val actual = reparsed.blocks.map { it.range }
                    assertEquals(expected, actual, "$label: block extents differ from a full parse")
                }
            }
        }
    }
}
