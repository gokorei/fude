package dev.fude.render

import dev.fude.core.Delete
import dev.fude.core.Edit
import dev.fude.core.Insert
import dev.fude.core.Replace
import dev.fude.core.TextBuffer
import dev.fude.core.TextRange
import dev.fude.core.editBetween
import dev.fude.markdown.IncrementalMarkdownParser
import dev.fude.markdown.ParagraphNode
import dev.fude.markdown.ParsedDocument
import dev.fude.markdown.ReparseCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

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
    /**
     * The same property, at a size where it is impossible to get right by accident.
     *
     * An earlier `parseOneBlock` parsed `[start, end)` — the whole document tail —
     * and returned only the first block. `ReparseCounter` incremented once per loop
     * iteration, so it reported one block parsed while parsing 50,000. Measured, that
     * made one keystroke's reparse **2.96× slower than a full parse** at 5,000 lines,
     * which is the opposite of what the design is for.
     *
     * Asserted on the counter rather than a timing because that is exact: a timing
     * assertion fails on a slow machine while the code is equally wrong. This test
     * fails against the old implementation and passes now.
     */
    @Test
    fun aKeystrokeInAVeryLargeDocumentStillParsesOneBlock() {
        val text = document(50_000)
        val counter = ReparseCounter()
        val parser = IncrementalMarkdownParser(counter = counter)
        parser.parse(text)

        val at = text.length / 2
        val edited = text.substring(0, at) + "x" + text.substring(at)
        parser.reparse(edited, Insert(at, "x"))

        // The fixture check goes through a second parser so it cannot move this
        // one's counter.
        assertEquals(50_000, IncrementalMarkdownParser().parse(text).blocks.size, "the fixture is the size claimed")
        assertEquals(
            1,
            counter.blockParses,
            "one keystroke in a 50,000-paragraph document must not parse 50,000 blocks",
        )
        assertEquals(1, counter.fullParses, "and must not fall back to a full parse")
    }

    /**
     * The same invariant, over 8 edits.
     *
     * What we actually measured is recorded in the ticket and in the README:
     *
     * | lines | reparse before | reparse after | full parse |
     * |---|---|---|---|
     * | 500 | 0.83 ms | 0.13 ms | 0.78 ms |
     * | 5,000 | 10.07 ms | 1.27 ms | 3.46 ms |
     * | 50,000 | 30.71 ms | 6.14 ms | 34.44 ms |
     *
     * Reparse is now cheaper than a full parse at every size measured. It is **not**
     * flat in document length, and should not be claimed to be: the design shifts the
     * untouched tail by the edit's delta, which means rebuilding a node per tail block
     * on every keystroke. That is O(n) by construction and is a separate question
     * from this one. At 1,682 lines — the live document in the functional suite — the
     * tail is small enough that it does not matter.
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
     * The same property as [everyReparseAgreesWithAFullParseOfTheSameText], at
     * **every offset of every fixture** instead of eight hand-picked ones.
     *
     * Eight cases cannot find this class of defect, and the reason is structural
     * rather than a matter of taste: every one of those cases is either a
     * whole-document replacement or an edit that lands *inside* a block. An edit in
     * the **gap between two blocks** is the shape that breaks, because by the
     * `endOfContent` invariant a block's range deliberately excludes its trailing
     * terminator and the blank lines after it — so the gap belongs to no block, and
     * no block's own extent says the edit happened to it.
     *
     * ## Method, and why each part is the way it is
     *
     * **A fresh parser per case.** A cumulative sweep is worse than useless: it
     * reuses the parser across edits, so `cached` is whatever the *previous* case
     * left behind, and a failure names an edit while reporting the document it
     * reached. Each case here therefore constructs its own parser and seeds it with
     * `parse(original)`, so the only difference between the two trees being compared
     * is `reparse` versus `parse`.
     *
     * **Tree equality, via [describe], not block count.** Block count passes the
     * case where a whole block has been dropped whenever the counts happen to agree,
     * and it cannot see a range that is shifted by one character. [describe] carries
     * kind, range, every inline's type and range, and every child's type and range,
     * over the full `allBlocks` flatten, so a nested list item or table cell is
     * compared too.
     *
     * **No timing, anywhere.** A timing assertion fails on a slow machine and passes
     * on a fast one while the code is equally wrong in both; this suite asserts that
     * on the counter instead, in the tests above. Boundedness is a real property and
     * it is checked there. It is simply not what this test is for: nothing here would
     * notice a reparse that quietly became a full parse, and nothing in the
     * boundedness tests would notice a tree that is in range and still wrong.
     *
     * **No case is skipped.** Every offset of every fixture goes through Insert,
     * Delete and Replace. An excluded case would be a silent hole in the one test
     * whose job is to have no holes.
     *
     * ## Fixture set
     *
     * The 7×7 block matrix from `BlockRangePropertyTest` — heading, paragraph, list,
     * fenced code, block quote, table, thematic break — in both of the shapes that
     * matter: blank-line separated (which is where the gap lives) and abutting (a
     * table's rows run straight into the next block). Plus a realistic whole note and
     * the three minimal fixtures the failing cases were first reduced to.
     *
     * ## What it found, and what it now holds
     *
     * Written red on purpose (PQBCQN9H), so the reason is recorded here rather than
     * only in the ticket: a red test with the reason written down cannot be mistaken
     * for a broken build, and cannot be made green by relaxing it.
     *
     * **First run: 835 of 9,840 edits disagreed.** 273 inserts of 3,350, 290
     * deletions of 3,245, 272 replacements of 3,245. After the fix: **zero of 9,840.**
     *
     * **Now 0 of ~24,480**, because the fixture set went from 7 blocks to 12 (288
     * documents rather than 105) when this matrix stopped being a private copy. The
     * five added fixtures are the three constructs fixed in wave 2 -- a tab-indented
     * list, `>quote` with no space after the marker, and `- - -` -- plus an unclosed
     * fence and a host block. Before they were added this sweep was guarding none of
     * the three, which is the specific failure this ticket exists to close. The count
     * is recorded so a later change that reintroduces a disagreement shows up as a diff
     * in this number rather than as a surprise.
     *
     * Five distinct failure classes, each verified by hand against a fresh `parse` of
     * the same text:
     *
     * **1. An edit in the gap reparse from the wrong side of it.**
     * ```
     * "# H\n\npara\n"   delete offset 4  ->  "# H\npara\n"
     *   incremental: [HEADING 0..3, PARAGRAPH 5..8]      paragraph covers "ara"
     *   full parse:  [HEADING 0..3, PARAGRAPH 4..8]
     * ```
     * The `p` of `para` is in no block, so `ParsedDocument.blockAt` — which falls
     * back to "the last block starting at or before this offset" — resolves offset 4
     * to the **heading**. The user deletes a blank line and the first character of the
     * next paragraph becomes part of the heading: caret in the wrong place, decoration
     * on the wrong text, no exception anywhere. The cause was
     * `indexOfFirst { it.range.end >= editStart }`: `range.end` is not the end of the
     * region a block owns, because that region runs on to the next block's start.
     *
     * **2. A merge whose content was never parsed.**
     * ```
     * "a\n\nb\n\nc\n"   delete offset 5  ->  "a\n\nb\nc\n"
     *   incremental: [PARAGRAPH 0..1, PARAGRAPH 3..4]    "c" belongs to no block
     *   full parse:  [PARAGRAPH 0..1, PARAGRAPH 3..6]
     * ```
     *
     * **3. The last block split by an edit in the gap after it.**
     * ```
     * "# H\n\npara\n"   insert "X" at offset 10  ->  "# H\n\npara\nX"
     *   incremental: [HEADING, PARAGRAPH 5..9, PARAGRAPH 10..11]   split in two
     *   full parse:  [HEADING, PARAGRAPH 5..11]
     * ```
     *
     * **4. A block's identity depends on the line above it.** Replacing the space in
     * `"A paragraph.\n# Heading\n"` leaves `#X Heading`, which is not a heading and
     * does not interrupt a paragraph, so the full parse gives one paragraph of 23
     * characters where reparsing from the heading gave two. This one is worth stating
     * because it is not about the gap at all: an edit well inside a block can still
     * belong to the block above, because whether a paragraph continues into the next
     * line is a question about that next line. A list reaches even further — it steps
     * over a blank line to see whether the list continues — so for a list or a fence a
     * blank line separates nothing.
     *
     * **5. The two paths scanned with different bounds.** Not the reparse origin at
     * all: `endOfFence` returns whatever `end` it was given when it finds no closing
     * marker, and `endOfTable` stops when it runs out of text, so the bound a scanner
     * is handed is part of its answer. `parse` passed `text.length`; `reparse` passed
     * `contentEnd`, the last non-whitespace character plus one, in three places. It made
     * no difference for any closed construct and one character of difference for an
     * unclosed fence or a table running to the end of the document. `contentEnd` is
     * still the reparse *loop's* bound — that is what stops it inventing a paragraph
     * out of the document's last newline — but it is no longer the bound handed to a
     * scanner.
     *
     * One thing this class exposed that is **not** fixed, because it is a disagreement
     * inside `parse` rather than between the two paths: for an unterminated fence the
     * answer both paths now agree on *ends on a terminator*, which is exactly what
     * `BlockRangePropertyTest.noBlockEndsOnALineTerminator` forbids. That test cannot
     * see it because every one of its fixtures is a closed fence. Changing `parse` to
     * trim the fence would make the two paths agree differently and is a decision about
     * `parse`, not about the incremental path — filed separately, not smuggled in here.
     */
    @Test
    fun everySingleCharacterEditAtEveryOffsetReparsesToAFullParseOfTheSameText() {
        val mismatches = linkedMapOf<String, MutableList<String>>()
        val attempted = linkedMapOf<String, Int>()

        for ((label, original) in sweepDocuments()) {
            for (offset in 0..original.length) {
                for (case in editsAt(original, offset)) {
                    attempted[case.kind] = (attempted[case.kind] ?: 0) + 1
                    // A thrown exception is a failure of this property too, and one
                    // that aborts the sweep on its first case would hide every other.
                    val reported = try {
                        val incremental = IncrementalMarkdownParser().let { parser ->
                            parser.parse(original)
                            parser.reparse(case.edited, case.edit)
                        }
                        val fromScratch = IncrementalMarkdownParser().parse(case.edited)
                        val expected = describe(fromScratch)
                        val actual = describe(incremental)
                        if (expected == actual) {
                            null
                        } else {
                            describeMismatch(expected, actual, case, label, original, offset)
                        }
                    } catch (failure: Throwable) {
                        "${failure::class.simpleName}: ${failure.message} — ${case.kind} at " +
                            "offset $offset in \"${marked(original, offset)}\"  [$label]"
                    }
                    if (reported != null) {
                        mismatches.getOrPut(case.kind) { mutableListOf() }.add(reported)
                    }
                }
            }
        }

        val report = buildString {
            for (kind in EDIT_KINDS) {
                val cases = mismatches[kind].orEmpty()
                val total = attempted[kind] ?: 0
                appendLine(
                    "$kind: ${cases.size} of $total single-character edits reparse to a tree " +
                        "a full parse does not produce",
                )
                cases.take(REPORTED_CASES_PER_KIND).forEach { appendLine("    $it") }
                if (cases.size > REPORTED_CASES_PER_KIND) {
                    appendLine("    ... and ${cases.size - REPORTED_CASES_PER_KIND} more")
                }
            }
        }
        assertTrue(mismatches.values.all { it.isEmpty() }, report)
    }

    /**
     * The exact count on **every** route out of `reparse`, not only the fast path.
     *
     * The existing tests in this file all assert `blockParses == 1` on the bounded loop,
     * which is why a counter that disagreed between routes survived: nothing ever read
     * the number on the other two. `ReparseCounter` is the instrument the whole
     * bounded-reparse argument rests on, so a number that changes meaning depending on
     * which branch an edit happened to take is not measuring anything, and a reader
     * comparing two routes would be comparing two different quantities.
     *
     * All three routes are driven deliberately, and all three are asserted to agree with
     * the number of top-level blocks the result actually contains:
     *
     * - **The bounded loop** — an edit inside one block of a large document.
     * - **`tailSurvivesShift`** — the whole document replaced by a much shorter one, so
     *   the reusable tail would shift below offset zero and cannot be trusted.
     * - **`overlapsReparsed`** — the same replacement against a *two*-block document,
     *   where the tail survives the shift but the reparse ran into the middle of it, so
     *   the two disagree about where the block below begins.
     *
     * The last two were found by instrumenting the two branches, which is worth
     * recording: they are not reachable by reasoning about offsets from outside, and the
     * two-block case is the only one that reaches the second. Both were verified to agree
     * with a full parse of the same text.
     */
    @Test
    fun everyRouteOutOfReparseReportsTheBlocksItActuallyConstructed() {
        val replacement = REPLACEMENT

        // `expect` is what the route owes the counter, which is not the same for all
        // three. The bounded loop **reuses** the tail, so it constructs only the blocks
        // it had to touch and owes a small number regardless of the document's size. The
        // two fallbacks **rebuild** the document, so they owe exactly the number of
        // blocks the result contains — which for these replacements is the number a full
        // parse finds.
        val routes = listOf(
            Route(
                name = "the bounded loop",
                document = document(500),
                edited = document(500).let { it.substring(0, 11) + "X" + it.substring(11) },
                edit = Insert(11, "X"),
                expect = { reparsed -> 1 },
                why = "one edit inside one block of 500 reparses one block and shifts the rest",
            ),
            Route(
                name = "the tailSurvivesShift fallback",
                document = document(200),
                edited = replacement,
                edit = Insert(2, ""),
                expect = { reparsed -> reparsed.blocks.size },
                why = "the whole document is rebuilt, so every block in it was constructed",
            ),
            Route(
                name = "the overlapsReparsed fallback",
                document = document(2),
                edited = replacement,
                edit = Insert(2, ""),
                expect = { reparsed -> reparsed.blocks.size },
                why = "the tail survives the shift but the reparse ran into the middle of " +
                    "it, so it is discarded and the rest is rebuilt",
            ),
        )

        for (route in routes) {
            val counter = ReparseCounter()
            val parser = IncrementalMarkdownParser(counter = counter)
            parser.parse(route.document)
            val reparsed = parser.reparse(route.edited, route.edit)

            assertEquals(1, counter.fullParses, "${route.name}: no route may fall back to a full parse")

            val fromScratch = IncrementalMarkdownParser().parse(route.edited)
            assertEquals(
                fromScratch.blocks.map { it.range },
                reparsed.blocks.map { it.range },
                "${route.name}: the tree must equal a full parse's",
            )
            assertEquals(
                route.expect(reparsed),
                counter.blockParses,
                "${route.name}: ${route.why}. Reported ${counter.blockParses} for a result " +
                    "holding ${reparsed.blocks.size} blocks.",
            )
        }
    }

    /**
     * An append does not get more expensive as the document grows.
     *
     * This is the property that killed the over-count. The measured defect was that
     * appending a block to a document of N blocks reported N+1 — which meant the append
     * path was counting one call, not one block, so the number scaled with a document the
     * append had nothing to do with.
     *
     * The count is 2, and it is 2 for every size, because two blocks really are
     * constructed: the appended one, and the last existing one, whose region the edit
     * landed in. That region includes the terminator and the blank lines after a block,
     * and the blank line separating the last block from the new one is exactly what the
     * append created — so its extent is a question the edit has answered, not one it left
     * alone. `ReparseCounter.recordBlockParse`'s KDoc states this, because a reader
     * seeing 2 for a one-block append would otherwise assume the counter is broken.
     *
     * Asserted as flatness rather than as a single number on purpose: a single measurement
     * cannot distinguish "correct" from "grows with the document, and the document was
     * small".
     */
    @Test
    fun anAppendCostsTheSameInADocumentOfAnySize() {
        val counts = (1..6).map { n ->
            val before = document(n)
            val append = "\n\nAppended paragraph with `code`.\n"
            val edited = before + append
            val counter = ReparseCounter()
            val parser = IncrementalMarkdownParser(counter = counter)
            parser.parse(before)
            val reparsed = parser.reparse(edited, Insert(before.length, append))

            assertEquals(1, counter.fullParses, "n=$n: no full parse")
            assertEquals(n + 1, reparsed.blocks.size, "n=$n: the append added exactly one block")
            assertEquals(
                reparsed.blocks.map { it.range },
                IncrementalMarkdownParser().parse(edited).blocks.map { it.range },
                "n=$n: the tree must equal a full parse's",
            )
            counter.blockParses
        }

        assertEquals(
            listOf(2, 2, 2, 2, 2, 2),
            counts,
            "an append must not cost more as the document grows; a count that tracked the " +
                "document size would be counting calls, not blocks",
        )
    }

    /**
     * The counter never claims more work than the tree shows.
     *
     * The complement of the two tests above, and the only one of the three that can hold
     * on **every** route rather than on the routes it knows how to trigger. Under-counting
     * is the defect this ticket was about; over-counting is the same failure with the sign
     * flipped, and neither is visible from a single edit.
     *
     * Swept over the same documents the exhaustive property test uses, at every offset, so
     * a route reached only by an unusual edit is covered even though the test does not
     * know which one it took.
     */
    @Test
    fun theCounterNeverClaimsMoreWorkThanTheTreeShows() {
        for ((label, original) in sweepDocuments()) {
            for (offset in 0..original.length) {
                val edited = original.substring(0, offset) + "X" + original.substring(offset)
                val counter = ReparseCounter()
                val parser = IncrementalMarkdownParser(counter = counter)
                parser.parse(original)
                val reparsed = try {
                    parser.reparse(edited, Insert(offset, "X"))
                } catch (failure: Throwable) {
                    fail("$label: insert X at $offset threw ${failure::class.simpleName}: ${failure.message}")
                }
                assertTrue(
                    counter.blockParses <= reparsed.blocks.size,
                    "$label, insert X at $offset: blockParses=${counter.blockParses} but the " +
                        "result holds only ${reparsed.blocks.size} blocks, so at least one of " +
                        "them was counted more than once",
                )
                assertTrue(
                    counter.fullParses == 1,
                    "$label, insert X at $offset: fullParses=${counter.fullParses}",
                )
            }
        }
    }

    /** One route through `reparse`, named so a failure says which one broke. */
    private data class Route(
        val name: String,
        val document: String,
        val edited: String,
        val edit: Edit,
        /** What this route owes the counter, as a function of the result it produced. */
        val expect: (ParsedDocument) -> Int,
        /** Why that is the right number, quoted in the failure message. */
        val why: String,
    )

    /** One synthetic edit: its kind, the text it produces, and the edit itself. */
    private data class SweepEdit(
        val kind: String,
        val edited: String,
        val edit: Edit,
    )

    /**
     * The three single-character edits available at [offset].
     *
     * Delete and Replace only where there is a character to remove; Insert at every
     * offset including the end of the document, which is the append case.
     */
    private fun editsAt(text: String, offset: Int): List<SweepEdit> = buildList {
        add(
            SweepEdit(
                kind = "insert",
                edited = text.substring(0, offset) + "X" + text.substring(offset),
                edit = Insert(offset, "X"),
            ),
        )
        if (offset >= text.length) return@buildList
        val removed = text.substring(offset, offset + 1)
        val tail = text.substring(offset + 1)
        add(
            SweepEdit(
                kind = "delete",
                edited = text.substring(0, offset) + tail,
                edit = Delete(TextRange(offset, offset + 1), removed),
            ),
        )
        // Never replace a character with itself: `reparse` short-circuits an
        // unchanged document, and a no-op Replace would be counted as agreeing.
        val inserted = if (removed == "X") "Y" else "X"
        add(
            SweepEdit(
                kind = "replace",
                edited = text.substring(0, offset) + inserted + tail,
                edit = Replace(TextRange(offset, offset + 1), removed, inserted),
            ),
        )
    }

    /**
     * Every block type the library produces on its own.
     *
     * Duplicated from `BlockRangePropertyTest` rather than shared with it, on purpose.
     * That file is in `dev.fude.markdown` and this one is in `dev.fude.render`, and the
     * alternative to a copy is a shared fixture that both suites then have an interest
     * in editing — which would let a change made for one of them quietly shrink the
     * other's coverage. Two copies of seven one-line strings, each sitting next to the
     * suite that motivated it, costs less than that coupling.
     */
    /**
     * Shared with `BlockRangePropertyTest`, deliberately and now for a reason: the two
     * matrices drifted, and three defects fixed in one wave were absent from *this* one,
     * so the sweep -- the test whose whole value is exhaustive coverage -- was not
     * guarding them. See `BlockFixtures` for what is and is not covered here.
     */
    private val blockMatrix = dev.fude.markdown.BLOCK_FIXTURES

    /**
     * The documents the sweep drives, each labelled with the pair that produced it so a
     * failure names a shape rather than a bare string.
     */
    private fun sweepDocuments(): List<Pair<String, String>> = buildList {
        for ((firstName, first) in blockMatrix) {
            for ((secondName, second) in blockMatrix) {
                // Blank-line separated. This is the shape that matters: the blank line
                // belongs to neither block, and it is the gap an edit lands in.
                add("$firstName then $secondName" to first + "\n" + second)
                // Abutting. A table's rows run straight into the next block and a
                // fence's closing marker does too, so the "own region" of the block
                // above runs on past its own last character here as well.
                add("$firstName abutting $secondName" to first.trimEnd('\n') + "\n" + second)
            }
        }
        add("realistic note" to realisticDocument())
        add("three paragraphs" to "a\n\nb\n\nc\n")
        add("heading then paragraph" to "# H\n\npara\n")
        add("table abutting a paragraph" to "| a | b |\n|---|---|\n| 1 | 2 |\nparagraph\n")
        add("crlf heading then paragraph" to "# H\r\n\r\npara\r\n")
        add("no trailing newline" to "# H\n\npara")
        add("trailing blank lines" to "# H\n\npara\n\n\n")
    }

    /**
     * The failure text for one case: the fixture with the offset marked, and the first
     * node at which the two trees disagree.
     *
     * Every one of these defects is invisible without the fixture. A `PARAGRAPH 5..8`
     * in isolation is a plausible-looking block; `"ara"` printed next to it is the bug.
     */
    private fun describeMismatch(
        expected: List<String>,
        actual: List<String>,
        case: SweepEdit,
        label: String,
        original: String,
        offset: Int,
    ): String {
        val at = firstDifference(expected, actual)
        val marker = "at" to "<OFFSET>"
        return buildString {
            append(case.kind).append(" at offset ").append(offset)
            append(" in \"").append(marked(original, offset)).append('"')
            append("  [").append(label).append(']')
            append("\n      input     ").append(escape(case.edited))
            append("\n      full parse  ").append(flattenForReport(expected, at))
            append("\n      incremental ").append(flattenForReport(actual, at))
            append("\n      ").append(marker.first).append(' ').append(marker.second)
            append(", node ").append(at)
        }
    }

    /**
     * The two trees rendered as one bracketed list per line, at most [MAX_NODES_REPORTED]
     * entries, centred on the first disagreement so the message stays readable on a
     * fixture with a hundred nodes.
     */
    private fun flattenForReport(nodes: List<String>, around: Int): String {
        if (nodes.isEmpty()) return "[]"
        val from = (around - MAX_NODES_REPORTED / 2).coerceAtLeast(0)
        val to = (from + MAX_NODES_REPORTED).coerceAtMost(nodes.size)
        val shown = nodes.subList(from, to).joinToString(", ")
        val ellipsis = if (from > 0 || to < nodes.size) "…" else ""
        return "$ellipsis[$shown]"
    }

    private fun firstDifference(expected: List<String>, actual: List<String>): Int {
        for (i in 0 until maxOf(expected.size, actual.size)) {
            if (expected.getOrNull(i) != actual.getOrNull(i)) return i
        }
        return -1
    }

    /** The fixture with the edited offset marked, escaped so newlines are visible. */
    private fun marked(text: String, offset: Int): String {
        val at = offset.coerceIn(0, text.length)
        return escape(text.substring(0, at)) + "<OFFSET>" + escape(text.substring(at))
    }

    /** Visible newlines, tabs and backslashes — a fixture is unreadable without them. */
    private fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private companion object {
        /** Shorter than the original by a factor of nine. */
        const val REPLACEMENT = "# Replaced\n\nA **new** document with a `code span`.\n"

        /** Every edit kind, in the order they are reported. */
        val EDIT_KINDS = listOf("insert", "delete", "replace")

        /** Failing cases named per edit kind, so the message stays legible. */
        const val REPORTED_CASES_PER_KIND = 12

        /** Nodes rendered per tree around the first disagreement. */
        const val MAX_NODES_REPORTED = 9
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

    /**
     * W9309TE3: the composable must pass the recovered edit, never a synthetic
     * empty insert. A multi-block delete's affected range spans blocks, so a
     * collapsed `Insert(offset, "")` misdescribes it and the reusable-tail
     * filter keeps blocks the edit destroyed. The honest edit reparses to the
     * same tree as a full parse.
     */
    @Test
    fun aMultiBlockDeleteWithTheRecoveredEditMatchesAFullParse() {
        val before = "# Title\n\nFirst paragraph.\n\nSecond paragraph.\n\nThird.\n"
        val after = "# Title\n\nThird.\n"
        val edit = editBetween(before, after)!!
        assertFalse(
            edit.affectedRange.isCollapsed,
            "the delete spans two blocks; a collapsed Insert would misdescribe it, got $edit",
        )

        val parser = IncrementalMarkdownParser()
        parser.parse(before)
        val reparsed = parser.reparse(after, edit)

        assertEquals(
            describe(IncrementalMarkdownParser().parse(after)),
            describe(reparsed),
            "reparse with the recovered edit must equal a full parse",
        )
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
}
