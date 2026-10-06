package dev.fude.markdown

import dev.fude.core.InlineRange
import dev.fude.syntax.BlockContext
import dev.fude.syntax.BlockMatch
import dev.fude.syntax.SyntaxExtension
import dev.fude.syntax.resolveBlockMatches
import dev.fude.syntax.resolveMatches

/** U+FEFF. Encoding metadata, never content. */
private const val BYTE_ORDER_MARK = '\uFEFF'

/**
 * Incremental Markdown parsing.
 *
 * Holds the last parse and, given an [dev.fude.core.Edit], reparses only the
 * blocks that could have changed.
 *
 * The spike found that Compose hands the renderer a fresh buffer every frame, so
 * decoration cannot be cached across frames and has to be recomputed. That makes
 * block-bounded reparse a correctness requirement rather than a performance
 * choice: a full reparse of a 5,000-line note per keystroke is not affordable.
 *
 * The implementation is a line scanner rather than a call into the JetBrains
 * Markdown parser. That is deliberate and worth stating, because the spike chose
 * that library and this looks like a reversal:
 *
 * - the vendor library parses a whole document in one call, which is exactly the
 *   thing that must not happen per keystroke
 * - it produces an `ASTNode` tree in *its* vocabulary, and converting that into
 *   Fude's block tree per block per frame would cost more than the parse
 * - Fude needs source ranges and stable block identity across edits, which the
 *   vendor tree does not give
 *
 * **Offsets are in original-text space. Always.**
 *
 * This is the invariant everything else here depends on, and it is worth stating
 * because the obvious alternative is wrong.
 *
 * Every [InlineRange] this parser emits indexes directly into the [text] it was
 * given. Nothing is normalised, rewritten or stripped on the way in: not a BOM, not a
 * `\r\n`, not a lone `\r`. A caller may therefore take any offset out of a node and
 * use it against the host's own string, and it means the same thing — which is what
 * makes `blockAt`, caret mapping and any future viewport windowing
 * safe to build on top.
 *
 * The tempting alternative is to normalise at parse entry. It is shorter and it would
 * have made the CRLF bugs disappear. It also means a BOM silently deletes one
 * character and each CRLF silently deletes another, so every offset after them
 * disagrees with the text the user is looking at — and `EditorState` holds the host's
 * exact string as canonical. That failure is invisible until a caret lands in the
 * wrong place in a document that was saved from Windows, which is exactly the input
 * this library exists to consume.
 *
 * So encoding artefacts are handled at the edges of a range rather than by rewriting
 * the document:
 *
 * - A leading BOM is **offset past**, so it belongs to no node. `text[0]` is still the
 *   BOM; the first block starts at 1.
 * - A block's range **ends at its content**, never on a terminator, so a `\r\n` sits
 *   in the gap between blocks where nothing can misread it.
 * - Terminators are recognised by [isLineTerminatorChar] rather than by looking for
 *   `'\n'`, so `\r\n` is one break and a lone `\r` is a break too.
 *
 * The cost is one character at the start of a BOM'd document that no block owns, and
 * `blockAt(0)` returns null there. That is the honest answer: offset 0 is not inside
 * any block because it holds encoding metadata rather than content.
 *
 * `org.jetbrains:markdown` remains the right choice for rendering Markdown to
 * HTML, and for conformance testing this parser against. If a vendor-based
 * incremental parse becomes available it can replace this behind the same
 * interface.
 */
class IncrementalMarkdownParser(
    private val extensions: List<SyntaxExtension> = emptyList(),
    private val counter: ReparseCounter = ReparseCounter(),
) {
    private var cached: ParsedDocument = ParsedDocument.EMPTY

    /** Parses [text] from scratch. */
    fun parse(text: String): ParsedDocument {
        counter.recordFullParse()
        val blocks = parseBlocks(text, 0, text.length)
        val document = ParsedDocument(text, blocks)
        cached = document
        return document
    }

    /**
     * Reparses [text] after [edit], touching only the blocks that changed.
     *
     * The strategy: find the first block whose range reaches past the edit, reparse
     * forward from its start until that block is accounted for, and shift the
     * remaining blocks by the edit's delta. Blocks *before* the edit are not
     * touched at all — their content is unchanged and their offsets are unchanged.
     *
     * If the edit changed the block structure — pressing Enter inside a paragraph
     * splits it, deleting a newline joins two — the reparse naturally consumes more
     * or fewer blocks, and the shifted tail is dropped when the reparse ran past it.
     * That is the fallback path, and it is correct rather than fast: a structural
     * edit genuinely may invalidate everything below it.
     *
     * **The tail may only be reused when the shift actually lands it in the new
     * document.** [shifted] clamps at zero, so a shift larger than the distance to
     * the document's start silently collapses a block to `(0, 0)` instead of
     * reporting that it no longer fits — and a replacement that shrinks the text by
     * more than one block is exactly that shift. Reusing it then loses the whole
     * tail: the caller gets an in-range tree covering a fraction of the text, with
     * everything after the first block no longer recognised as Markdown. So the
     * check below falls back to a full parse, which is correct and costs a full
     * parse only for edits that genuinely need one.
     */
    fun reparse(text: String, edit: dev.fude.core.Edit): ParsedDocument {
        if (text == cached.text) return cached

        val previous = cached
        if (previous.text.isEmpty() || text.isEmpty()) return parse(text)

        val delta = text.length - previous.text.length
        val editStart = edit.affectedRange.start.coerceIn(0, previous.text.length)

        // Which block the reparse starts from: the first block whose **owned region**
        // contains the edit.
        //
        // The owned region is not `range.end`. By the `endOfContent` invariant a
        // block's range deliberately stops at its last piece of content, so its region
        // runs on past its trailing terminator and the blank lines after it, up to the
        // next block's start (or the end of the document, for the last block).
        //
        // Both halves of the rule have to hold at once, and neither subsumes the other:
        //
        // - An edit *inside* block n's content is an edit to block n. `editStart <
        //   range.end` finds that.
        // - An edit in the **gap** after block n — the terminator, the blank lines, the
        //   document's trailing whitespace — is still an edit *to block n*, because the
        //   blank line that separated the two blocks is precisely what the edit
        //   changed. `range.end >= editStart` does not find that: it names block n+1,
        //   leaves block n reused with offsets from the old text, and leaves the
        //   character at the edit belonging to no block at all.
        //
        // Past that there is a third reason to include the block above, and it is the
        // one that took the longest to see. **A block's identity is decided partly by
        // the line above it.** Whether a paragraph continues into the next line is a
        // question about that next line, so an edit anywhere in a block's *first* line
        // can turn a separate block into a lazy continuation of the one above and make
        // the document one block instead of two. Replacing the space in
        // `"A paragraph.\n# Heading\n"` at offset 14 leaves `#X Heading`, which is not a
        // heading and does not interrupt a paragraph, so the full parse gives one
        // paragraph of twenty-two characters where reparsing from the heading gave two.
        //
        // That reason only applies where the two blocks **abut**. Across a blank line
        // the separation is usually already made: the blank line sits *before* the
        // boundary, and nothing happening at the start of the lower block can undo
        // it — so a keystroke at the start of a block in a blank-line-separated
        // document reparses one block, not two. The abutting test is what keeps that
        // cost at one.
        //
        // "Usually", because two constructs look past a blank line while deciding
        // their own extent and so are *not* finished with the text when they reach one.
        // A list skips a blank line to see whether the next line continues it, so
        // `" - one\n- two\n\n# Heading\n"` loses its heading to a lazy continuation of
        // its second item even though a blank line separates them. A fence scans to the
        // end of the document looking for its closing marker. For those the blank line
        // protects nothing and the region has to reach the same distance.
        if (previous.blocks.isEmpty()) return parse(text)
        val firstAffected = previous.blocks.indices.firstOrNull { index ->
            val regionEnd = ownedRegionEnd(previous.blocks, index, previous.text.length)
            val reachesFurther = seesPastABlankLine(previous.blocks[index])
            val abuts = reachesFurther ||
                !separatedByBlankLine(previous.text, previous.blocks[index].range.end, regionEnd)
            editStart < regionEnd || (abuts && editStart <= regionEndOfFirstLine(previous, index))
        } ?: previous.blocks.lastIndex

        // Only the blocks that lie wholly *after* the edit can be shifted rather than
        // reparsed.
        //
        // `delta` is the right shift for an offset beyond the edit's affected range and
        // the wrong one for anything inside it, so a tail block that the edit reached
        // into is dropped from the tail rather than relocated. Deleting the `b` from
        // `"a\n\nb\n\nc\n"` used to shift the destroyed block to `[2,3)` — a paragraph
        // covering the two newlines that replaced it — and hand the user a block made
        // of nothing but the gap.
        val editEnd = edit.affectedRange.end.coerceIn(0, previous.text.length)
        val reusableTail = previous.blocks
            .drop(firstAffected + 1)
            .filter { it.range.start >= editEnd }
        val boundary = previous.blocks[firstAffected].range.start

        // Where the reusable tail now begins — which is also where the *next* block
        // begins, if this reparse has correctly consumed everything the first affected
        // block owned. Taken from the first block that actually survived the edit,
        // because [reusableTail] may have dropped the block that used to be there.
        //
        // `delta` rather than `edit.mapOffset`, deliberately: [delta] is the shift the
        // tail is actually reused with by [shiftBlock] a few lines below, so deriving
        // the stop point from the same number keeps the two consistent by
        // construction. Mapping through the edit instead disagrees whenever the two
        // can — an insertion's UPSTREAM affinity pins the boundary at the insertion
        // point, and a caller that reports "replace everything from offset 2" while
        // handing over a wholly different text (which is what a host computes for a
        // full-buffer replacement) has a delta that says nothing like its
        // `affectedRange`.
        //
        // With nothing left to reuse there is no boundary to reconcile against, so the
        // reparse runs to the end of the document. That is the honest reading: an edit
        // that destroyed the block below the one it landed in has to reparse what that
        // block was, and stopping at the region's end instead stopped the reparse one
        // block early and lost the merged paragraph entirely.
        //
        // Neither end of the block's own range works as the stopping point:
        //
        // - `range.end` stops the reparse at the block's content, which leaves the gap
        //   after it unparsed and hands it to the shifted tail. That gap is where the
        //   deleted or inserted character may have *become* a block: inserting "X" into
        //   the blank line between two headings makes a paragraph, and stopping at the
        //   heading's content lost it.
        // - The region's end stops it one block too late. `parseOneBlock` returns the
        //   offset the next block's scan *begins* at, which is the blank line rather
        //   than the block after it, so waiting for the region's end parses a second
        //   block every keystroke — twice the work, for a tree that then has to drop
        //   the duplicate again.
        //
        // So the loop below stops when it reaches the tail's start, *or* when only
        // whitespace is left before it. The second clause is the ordinary case and it
        // is what keeps one keystroke at one block: the reparse stops on the gap it has
        // already covered, not on the block beyond it.
        val tailStart = reusableTail.firstOrNull()
            ?.let { (it.range.start + delta).coerceIn(0, text.length) }
            ?: text.length

        // Stop at the last non-whitespace character, not at `text.length`.
        //
        // `parseBlocks` is written to always yield one block so the renderer has
        // something to lay out, which is right when it is descending into a block's
        // content and wrong at the document's own tail: a document ending in a
        // newline has a final "\n" that is not a block. Entering it here
        // synthesises an empty paragraph covering that one character, so an
        // incremental parse grows a phantom block at the end that a full parse does
        // not produce — and `blockAt`, `blockStarts` and the layout cache then all
        // see a block the document does not contain.
        //
        // So `contentEnd` is the **loop's** bound and not the scanner's. Handing it to
        // the scanner as well made the two paths compute different extents for the same
        // text: `endOfFence` returns its `end` when it finds no closing marker, and
        // `endOfTable` stops when it runs out of text, so an unterminated fence came out
        // one character shorter here than it did from `parse`, which scans to
        // `text.length`. The bound a scanner is given is part of its answer, so both
        // paths have to give it the same one.
        val contentEnd = text.indexOfLast { !it.isWhitespace() } + 1

        // Built up from the untouched head rather than concatenated at the end, so a
        // reparse that begins on a table's delimiter row can complete the header row
        // already in `head` into a TableNode, exactly as a full parse would.
        val document = previous.blocks.take(firstAffected).toMutableList()
        var cursor = boundary

        while (cursor < contentEnd) {
            val step = parseOneBlock(text, cursor, text.length, document.lastOrNull())
            if (step.next <= cursor) break
            val node = step.block
            if (node != null) {
                if (step.replacesPrevious && document.isNotEmpty()) {
                    document[document.lastIndex] = node
                } else {
                    document += node
                }
            }
            cursor = step.next
            if (cursor >= tailStart) break
            // Only the gap is left before the tail, so the tail's own shift is
            // consistent with where the reparse stopped and there is nothing to parse.
            if (text.substring(cursor, tailStart).isBlank()) break
        }

        // The tail moves by the edit's delta, which is exact: everything before it is
        // either untouched or re-parsed, and the total length change is `delta`.
        // What is *not* exact is how far the reparse actually got, so the tail is
        // reconciled against [cursor] rather than assumed to start where it used to.
        if (!tailSurvivesShift(reusableTail, delta, text.length)) {
            // The tail no longer fits in the new document, so nothing below this
            // point can be trusted to have survived unchanged. Reparse it rather
            // than shift it — correct, and rare, because a keystroke's delta is small.
            // Nothing to reparse once the cursor has reached the document's real end.
            // `parseBlocks` is written to always yield one block so the renderer has
            // something to lay out, which at end-of-document means inventing an empty
            // paragraph covering a newline that is not there.
            // `text.length` rather than [contentEnd] as the scan bound, for the same
            // reason the reparse loop does: the bound is part of what a scanner
            // computes, and a fence with no closing marker ends at whatever bound it
            // was handed. Two paths over the same text have to agree on the bound as
            // well as on the result.
            val reparsedRest =
                if (cursor >= contentEnd) emptyList()
                else parseBlocks(text, cursor, text.length, countBlocks = true)
            cached = ParsedDocument(text, document + reparsedRest)
            return cached
        }

        // Keep only the part of the tail the reparse did not already cover.
        //
        // Testing only the tail's *start* is not enough, and was a real bug: a
        // reparsed block can span across that start and stop in the middle of a
        // shifted block, and dropping the tail then silently loses everything below
        // it. A host extension claiming a block is what produces that shape, because
        // the claim can change how far one block reaches.
        val shiftedTail = reusableTail.map { shiftBlock(it, delta) }
        val overlapsReparsed = shiftedTail.any { it.range.start < cursor && it.range.end > cursor }
        if (overlapsReparsed) {
            // The reparse covered part of a block we shifted, so the two disagree
            // about where that block begins and neither can be trusted. Reparse.
            // Nothing to reparse once the cursor has reached the document's real end.
            // `parseBlocks` is written to always yield one block so the renderer has
            // something to lay out, which at end-of-document means inventing an empty
            // paragraph covering a newline that is not there.
            // `text.length` rather than [contentEnd] as the scan bound, for the same
            // reason the reparse loop does: the bound is part of what a scanner
            // computes, and a fence with no closing marker ends at whatever bound it
            // was handed. Two paths over the same text have to agree on the bound as
            // well as on the result.
            val reparsedRest =
                if (cursor >= contentEnd) emptyList()
                else parseBlocks(text, cursor, text.length, countBlocks = true)
            cached = ParsedDocument(text, document + reparsedRest)
            return cached
        }

        val remaining = shiftedTail.filter { it.range.end > cursor }

        cached = ParsedDocument(text, document + remaining)
        return cached
    }

    /**
     * The end of the region block [index] **owns**: where the next block starts, or
     * [textLength] for the last one.
     *
     * Not `block.range.end`, and the difference is the whole of one defect. A block's
     * range stops at its content — never on a terminator, never into the blank line
     * after it — which is the invariant `BlockRangePropertyTest` exists to hold. But
     * the *region* a block is responsible for is larger than its range: it also owns
     * the terminator that ends it and the blank lines that follow, because those are
     * what separate it from the next block and an edit to them is an edit to it.
     *
     * So an edit at offset 4 of `"# H\n\npara\n"` lands in the heading's region, not the
     * paragraph's, even though `range.end` says the heading stopped at 3.
     */
    private fun ownedRegionEnd(blocks: List<BlockNode>, index: Int, textLength: Int): Int =
        blocks.getOrNull(index + 1)?.range?.start ?: textLength

    /**
     * Where the region block [index] owns runs on to: the end of the **first line** of
     * the block below it, or the end of the document when there is no block below.
     *
     * An edit anywhere in there can change whether the lower block is its own block at
     * all, because a paragraph's continuation is decided by looking at the line that
     * would continue it. The first line is as far as that reaches: by the second line
     * the two blocks are already separate for reasons the edit cannot undo.
     */
    private fun regionEndOfFirstLine(previous: ParsedDocument, index: Int): Int {
        val next = previous.blocks.getOrNull(index + 1) ?: return previous.text.length
        var cursor = next.range.start
        while (cursor < previous.text.length && !isLineTerminatorChar(previous.text[cursor])) cursor++
        return cursor
    }

/**
     * Whether [node] keeps reading past a blank line while deciding its own extent, so
     * that an edit below one can still change it.
     *
     * A paragraph, heading, quote, table row or thematic break is finished at the blank
     * line: the scanner breaks there and the block is complete. A **list** steps over the
     * blank line to look at the line after it — that is what decides whether the list
     * continues — and a **fence** scans the rest of the document for its closing marker.
     * Neither is finished at a blank line, so for those two a blank line does not separate
     * them from anything below and an edit down there is an edit to them.
     */
    private fun seesPastABlankLine(node: BlockNode): Boolean =
        node is ListNode || node is CodeFenceNode

    /**
     * Whether `[from, to)` contains a blank line — two line terminators in a row.
     *
     * The gap between two blocks is one of exactly two things. Either it is a single
     * line terminator, meaning the blocks abut and the second block's own first line is
     * what decides whether they are separate, or it holds a blank line, meaning the
     * separation is already made — for every block except the two in
     * [seesPastABlankLine].
     */
    private fun separatedByBlankLine(text: String, from: Int, to: Int): Boolean {
        var run = 0
        for (i in from until to.coerceAtMost(text.length)) {
            run = if (isLineTerminatorChar(text[i])) run + 1 else 0
            if (run >= 2) return true
        }
        return false
    }

    /**
     * Whether shifting [tail] by [delta] lands every one of its ranges inside a
     * document of [textLength] characters, with no clamping.
     *
     * The check is on the top-level block ranges only. A descendant cannot reach
     * further than its ancestor — the parser only ever builds children within their
     * parent's extent — so if the parent shifts cleanly the children do too.
     */
    private fun tailSurvivesShift(
        tail: List<BlockNode>,
        delta: Int,
        textLength: Int,
    ): Boolean = tail.all { it.range.start + delta >= 0 && it.range.end + delta <= textLength }

/**
 * Parses the single block starting at [start], reading nothing beyond it.
 *
 * The whole cost of the incremental path lives here, so it is worth being exact
 * about what it does: it parses **one** block and stops at that block's boundary. It
 * does not call [parseBlocks] over `[start, end)`, which would build a `BlockNode`
 * for every remaining block in the document only to return the first and discard the
 * rest — that is what made the "block-bounded" reparse three times *slower* than a
 * full parse at 5,000 lines, while [ReparseCounter] cheerfully reported one block.
 *
 * Blank lines between blocks are skipped rather than returned, so [next] can jump
 * over them and the caller may find the next block already in hand.
 *
 * @return the block and the offset the next block begins at.
 */
    private fun parseOneBlock(
        text: String,
        start: Int,
        end: Int,
        previous: BlockNode?,
    ): BlockStep {
        var cursor = start
        while (cursor < end) {
            val step = parseBlockAt(text, cursor, end, previous)
            if (step.next <= cursor) break
            if (step.block != null) {
                // Not when the step **replaces** what the caller already holds: a table's
                // header row is parsed as a `TableRowNode`, counted, and then replaced by
                // the `TableNode` the delimiter row completes it into. One block ends up
                // in the tree and two were counted.
                //
                // `parseBlocks` has always guarded this — its `replacesPrevious` branch
                // swaps the node in without counting, with a comment saying the header
                // row was already counted and the table is the same block, now complete.
                // This path did not, so the bounded loop and the two `parseBlocks`
                // fallbacks disagreed about what a count means, which is precisely what a
                // counter read across routes must not do.
                //
                // Measured, inserting `X` into `| a | b |` of a heading-abutting table:
                // `blockParses = 3` for a document holding 2 blocks.
                if (!step.replacesPrevious) counter.recordBlockParse()
                return step
            }
            cursor = step.next
        }
        return BlockStep(blankNode(start, end), end)
    }

    private fun blankNode(start: Int, end: Int): BlockNode =
        ParagraphNode(InlineRange(start, maxOf(end, start)), emptyList())

    /**
     * Shifts a block and its descendants by [by], without re-parsing them.
     *
     * Inline ranges are shifted too, and that is the part that is easy to forget:
     * the renderer decorates from `block.inlines`, not from `block.range`. Moving
     * only the block range leaves every span below the edit pointing at the
     * document's previous coordinates, which decorates the wrong characters and
     * stays silent — no exception, just bold applied one character off, and getting
     * more wrong the further down the document you look.
     */
    private fun shiftBlock(node: BlockNode, by: Int): BlockNode = when (node) {
        is ParagraphNode -> node.copy(range = node.range.shifted(by), inlines = shiftInlines(node.inlines, by))
        is HeadingNode -> node.copy(range = node.range.shifted(by), inlines = shiftInlines(node.inlines, by))
        is ListNode -> node.copy(range = node.range.shifted(by), children = node.children.map { shiftBlock(it, by) })
        is ListItemNode -> node.copy(
            range = node.range.shifted(by),
            children = node.children.map { shiftBlock(it, by) },
            inlines = shiftInlines(node.inlines, by),
        )
        is BlockQuoteNode -> node.copy(range = node.range.shifted(by), children = node.children.map { shiftBlock(it, by) })
        is CodeFenceNode -> node.copy(range = node.range.shifted(by), contentRange = node.contentRange.shifted(by))
        is TableNode -> node.copy(range = node.range.shifted(by), children = node.children.map { shiftBlock(it, by) })
        is TableRowNode -> node.copy(range = node.range.shifted(by), cells = node.cells.map { shiftBlock(it, by) as TableCellNode })
        is TableCellNode -> node.copy(range = node.range.shifted(by), inlines = shiftInlines(node.inlines, by))
        is ThematicBreakNode -> node.copy(range = node.range.shifted(by))
        is HostBlockNode -> node.copy(range = node.range.shifted(by), inlines = shiftInlines(node.inlines, by))
    }

    /** Shifts an inline run by [by], including the ranges a link holds separately. */
    private fun shiftInlines(inlines: List<InlineNode>, by: Int): List<InlineNode> =
        inlines.map { inline ->
            when (inline) {
                is TextNode -> inline.copy(range = inline.range.shifted(by))
                is CodeSpanNode -> inline.copy(range = inline.range.shifted(by))
                // The label is a range in its own right: rendering shows the label,
                // so shifting the link's full range alone would put the underline on
                // the destination's coordinates.
                is LinkNode -> inline.copy(
                    range = inline.range.shifted(by),
                    labelRange = inline.labelRange.shifted(by),
                )
                is ImageNode -> inline.copy(range = inline.range.shifted(by))
                is HostInlineNode -> inline.copy(range = inline.range.shifted(by))
                is EmphasisNode -> inline.copy(
                    range = inline.range.shifted(by),
                    children = shiftInlines(inline.children, by),
                )
            }
        }

    /**
 * One step of the block scanner: a block, and where the next one starts.
 *
 * [replacesPrevious] is the case that keeps a table's header row from appearing
 * twice. A delimiter row does not introduce a block; it completes the `TableRowNode`
 * above it into a `TableNode`. Carrying that as an explicit flag rather than
 * inferring it afterwards is what lets [parseBlockAt] be reused by the
 * incremental path without either caller having to know about table internals.
 */
private data class BlockStep(
    val block: BlockNode?,
    val next: Int,
    val replacesPrevious: Boolean = false,
)

/**
     * Asks the registered extensions whether one of them owns the block at [from].
     *
     * Asked before any built-in rule, and a match wins, because that is the only way
     * a host can turn something Markdown already understands into something else: a
     * callout *is* a block quote until the host says otherwise.
     *
     * The context spans the candidate block — the run of non-blank lines starting at
     * [from] — rather than the document, so a multi-line construct is visible without
     * an extension having to scan the file. The extension's returned range is then
     * authoritative for both the block's extent and where scanning resumes.
     *
     * @return the step, or null when no extension claims this block.
     */
    private fun hostBlockStepAt(text: String, from: Int, end: Int): BlockStep? {
        if (extensions.isEmpty()) return null
        val candidateEnd = endOfLineRun(text, from, end)
        if (candidateEnd <= from) return null

        val candidates = extensions.flatMap { extension ->
            extension
                .recogniseBlocks(BlockContext(text, InlineRange(from, candidateEnd)))
                .filter { it.range.start >= from && it.range.end <= candidateEnd && it.range.end > it.range.start }
                .map { extension to it }
        }
        val winner = resolveBlockMatches(candidates).firstOrNull() ?: return null
        val match = winner.match
        val resume = if (match.ownsTerminator) {
            match.range.end
        } else {
            // The extension reported content only, deliberately leaving its closing
            // delimiter out of the range. Resume past the end of that line so the
            // delimiter is consumed with the construct rather than rescanned, which
            // would match the same construct again and loop.
            pastLineTerminator(text, match.range.end, end)
        }
        if (resume <= from) return null
        return BlockStep(
            block = HostBlockNode(
                range = match.range,
                inlines = parseInline(text, match.range.start, match.range.end),
                extensionId = winner.extensionId,
                ownsTerminator = match.ownsTerminator,
            ),
            next = resume,
        )
    }

    /** The end of the run of non-blank lines starting at [from], or [from] if none. */
    private fun endOfLineRun(text: String, from: Int, end: Int): Int {
        var cursor = from
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            if (text.substring(cursor, lineEnd).isBlank()) break
            cursor = pastLineTerminator(text, lineEnd, end)
        }
        return cursor
    }

    /**
     * Parses the single block starting at [from], and stops there.
     *
     * This is the unit the incremental path is built on, so it must not read past
     * the block it returns. [previous] is the block immediately above [from], needed
     * because a table's delimiter row means nothing without the header row above it.
     *
     * The block is `null` when the range held only blank lines: those separate
     * blocks rather than being one, so the cursor advances and the caller asks again.
     */
    private fun parseBlockAt(
        text: String,
        from: Int,
        end: Int,
        previous: BlockNode?,
    ): BlockStep {
        val lineEnd = lineEndAt(text, from, end)
        val line = text.substring(from, lineEnd)

        fun step(block: BlockNode?, next: Int, replacesPrevious: Boolean = false) =
            BlockStep(block, next, replacesPrevious)

        hostBlockStepAt(text, from, end)?.let { return it }

        return when {
            line.isBlank() -> step(null, skipNewline(text, lineEnd, end))

            isFence(line) -> {
                val fenceEnd = endOfFence(text, from, end)
                // `endOfFence` stops at its closing marker's `lineEnd` for a closed fence,
                // so the terminator after the marker is never included. An unclosed fence
                // has no marker and runs to `end`, which on a document ending in a newline
                // is one past the last character of the code -- so the *range* is trimmed
                // here to match the closed case.
                //
                // Only the range. `contentRange` keeps the untrimmed extent on purpose: a
                // fenced block's literal content includes its final line ending, so
                // "```\ncode\n" contains "code\n" and trimming it would quietly change what
                // `content(text)` returns to every host. The scan cursor likewise keeps the
                // untrimmed `fenceEnd`, or the parser would re-read the terminator as new
                // content.
                val blockEnd = endOfContent(text, fenceEnd, from)
                val openingEnd = skipNewline(text, lineEndAt(text, from, end), fenceEnd)
                // The closing fence line is not content either.
                val contentEnd = closingFenceStart(text, from, fenceEnd) ?: fenceEnd
                step(
                    CodeFenceNode(
                        range = InlineRange(from, blockEnd),
                        contentRange = InlineRange(openingEnd, contentEnd.coerceAtLeast(openingEnd)),
                        info = fenceInfo(text, from, fenceEnd),
                    ),
                    fenceEnd,
                )
            }

            isThematicBreak(line) ->
                step(ThematicBreakNode(InlineRange(from, lineEnd)), skipNewline(text, lineEnd, end))

            isAtxHeading(line) != null -> step(
                HeadingNode(
                    range = InlineRange(from, lineEnd),
                    level = isAtxHeading(line)!!,
                    inlines = parseInline(text, from + headingPrefixLength(line), lineEnd),
                ),
                skipNewline(text, lineEnd, end),
            )

            isListMarker(line) != null ->
                parseList(text, from, end).let { (consumed, node) -> step(node, consumed) }

            isTableDelimiter(line) -> {
                // A delimiter row only means something after a header row.
                if (previous is TableRowNode) {
                    val tableEnd = endOfTable(text, lineEnd, end, columnCount(line))
                    step(
                        TableNode(
                            range = InlineRange(
                                previous.range.start,
                                endOfContent(text, tableEnd, previous.range.start),
                            ),
                            children = listOf(previous) + tableRows(text, lineEnd, tableEnd, columnCount(line)),
                            hasHeader = true,
                        ),
                        tableEnd,
                        replacesPrevious = true,
                    )
                } else {
                    step(null, skipNewline(text, lineEnd, end))
                }
            }

            isTableRow(line) || canStartTable(line, lineAt(text, pastLineTerminator(text, lineEnd, end), end)) -> {
                val delimiterEnd = lineEndAt(text, lineEnd, end)
                if (isTableDelimiter(text.substring(lineEnd, delimiterEnd))) {
                    val tableEnd = endOfTable(text, delimiterEnd, end, columnCount(line))
                    step(
                        TableNode(
                            range = InlineRange(from, endOfContent(text, tableEnd, from)),
                            children = tableRows(text, from, tableEnd, columnCount(line)),
                            hasHeader = true,
                        ),
                        tableEnd,
                    )
                } else {
                    step(
                        TableRowNode(
                            range = InlineRange(from, lineEnd),
                            cells = parseCells(text, from, lineEnd),
                        ),
                        skipNewline(text, lineEnd, end),
                    )
                }
            }

            isBlockQuote(line) ->
                parseBlockQuote(text, from, end).let { (consumed, node) -> step(node, consumed) }

            else -> {
                val consumed = endOfParagraph(text, from, end)
                // Exclude the trailing terminator from the block: a paragraph's range is
                // its content, and a caret at the end of a line is at the block's end,
                // not one past a newline it does not contain. Trimming every terminator
                // rather than seeking the last '\n' is what keeps a CRLF paragraph from
                // ending on its carriage return.
                val contentEnd = endOfContent(text, consumed, from)
                step(
                    ParagraphNode(
                        range = InlineRange(from, contentEnd),
                        inlines = parseInline(text, from, contentEnd),
                    ),
                    consumed,
                )
            }
        }
    }

    /**
     * Parses [text] between [start] and [end] into top-level blocks.
     *
     * [countBlocks] records each top-level block against [ReparseCounter], and is set
     * only when this call is doing incremental work. Recursive descents into a block's
     * own content leave it off: those blocks are part of parsing the block above them,
     * not extra top-level reparses.
     */
    private fun parseBlocks(
        text: String,
        start: Int,
        end: Int,
        countBlocks: Boolean = false,
    ): List<BlockNode> {
        val blocks = mutableListOf<BlockNode>()

        // A leading byte-order mark is encoding metadata, not content.
        //
        // It is offset *past*, never stripped. The document is the host's exact string
        // and every range Fude emits indexes into it, so removing a character would
        // silently desynchronise every offset from the text the user is looking at.
        // Leaving it unowned keeps offsets true to the source, at the cost of one
        // character at the very start belonging to no block.
        //
        // Without this the BOM defeats every block test on the first line: a document
        // starting with a BOM then `# Title` parsed as a paragraph, not a heading.
        val first =
            if (start == 0 && start < end && text[start] == BYTE_ORDER_MARK) start + 1 else start
        var cursor = first

        // A range that is empty or entirely blank still yields one empty block, so
        // the renderer always has something to lay out. Collapsing it to no blocks
        // is how a note that is just a newline ends up with no clickable target.
        if (first >= end || text.substring(first, end).isBlank()) {
            if (first < end || first < text.length) {
                blocks += ParagraphNode(InlineRange(first, end.coerceAtLeast(first)), emptyList())
            }
            return blocks
        }

        while (cursor < end) {
            val step = parseBlockAt(text, cursor, end, blocks.lastOrNull())
            if (step.next <= cursor) break
            step.block?.let { node ->
                if (step.replacesPrevious && blocks.isNotEmpty()) {
                    // The header row was already counted when it was appended; the
                    // table that replaces it is the same block, now complete.
                    blocks[blocks.lastIndex] = node
                } else {
                    blocks += node
                    if (countBlocks) counter.recordBlockParse()
                }
            }
            cursor = step.next
        }
        return blocks
    }

    private fun parseList(text: String, start: Int, end: Int): Pair<Int, ListNode> {
        val ordered = isOrderedMarker(text.substring(start, lineEndAt(text, start, end)))
        val items = mutableListOf<BlockNode>()
        var cursor = start
        var baseIndent = Int.MAX_VALUE

        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)
            if (line.isBlank()) {
                val after = skipNewline(text, lineEnd, end)
                if (after >= end) break
                val peek = text.substring(after, lineEndAt(text, after, end))
                // A blank line ends the list unless the next line continues it,
                // either by being indented or by being another item at this level.
                val peekIndent = indentOf(peek)
                if (peekIndent < baseIndent && isListMarker(peek) == null) break
                if (peekIndent == 0 && peekIndent < baseIndent && isListMarker(peek) == null) break
                cursor = after
                continue
            }
            val indent = indentOf(line)
            if (isListMarker(line) == null && items.isEmpty()) break
            if (indent == 0) {
                if (items.isNotEmpty() && isListMarker(line) == null) break
            }
            if (baseIndent == Int.MAX_VALUE) baseIndent = indent

            // The character count, not `indent`. `contentStart` is an offset into the
            // document and every range here indexes the original text one-for-one,
            // while `indent` is the column count that decides nesting and
            // continuation. They differ whenever a tab is involved.
            val contentStart = cursor + indentWidthOf(line) + markerLength(line)
            val itemEnd = endOfListItem(text, lineEnd, end, indent)
            val nested = parseBlocks(text, contentStart, itemEnd)
            // The item's own text is the first line's content; anything further in
            // belongs to a nested block, not to the item's inline run.
            val ownEnd = text.indexOf('\n', contentStart).let { if (it < 0 || it > itemEnd) itemEnd else it }
            items += ListItemNode(
                range = InlineRange(cursor, endOfContent(text, itemEnd, cursor)),
                children = nested,
                inlines = parseInline(text, contentStart, ownEnd),
                indent = indent,
            )
            cursor = itemEnd
        }

        // The list ends at its last item's content, not at the blank line that follows
        // it. That blank line belongs to the gap between blocks, and a block whose
        // range reaches into the gap makes `blockAt` resolve those offsets to the list
        // and puts a window boundary in the wrong place.
        val listEnd = items.lastOrNull()?.range?.end?.let { endOfContent(text, it, start) } ?: start
        return cursor to ListNode(
            range = InlineRange(start, listEnd),
            ordered = ordered,
            children = items,
        )
    }

    /**
     * The end of the list item whose first line ended at [from].
     *
     * Continuation is anything indented past the item's own marker. A following
     * line at the same indent starts a new item, and [parseList] handles that.
     */
    private fun endOfListItem(text: String, from: Int, end: Int, itemIndent: Int): Int {
        // Past this line's own terminator, not past every newline that follows it.
        // Starting with `skipNewline` here skipped the blank line between this item
        // and the next thing in the document before the loop had looked at it, so the
        // item's range reached past its own content — the same over-extension the
        // table and the fence had.
        var cursor = pastLineTerminator(text, from, end)
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)
            if (line.isBlank()) {
                // A blank line only continues the item if an indented line follows.
                val after = skipNewline(text, lineEnd, end)
                if (after >= end) break
                if (indentOf(text.substring(after, lineEndAt(text, after, end))) <= itemIndent) break
                cursor = after
                continue
            }
            if (indentOf(line) <= itemIndent) break
            cursor = skipNewline(text, lineEnd, end)
        }
        return cursor
    }

    /**
     * How many characters of [line] the block-quote marker and its padding take.
     *
     * A block quote opens with `>`, optionally indented by up to three spaces, and
     * CommonMark allows exactly one optional space after the `>` — which is *optional*.
     * `>quote` is a block quote whose content is `quote`, not `uote`.
     *
     * This was hardcoded as `2`, on the assumption that every quote is written `> x`.
     * [isBlockQuote] accepts `>` with no space, so every unspaced quote silently lost its
     * first character: not thrown, not logged, just absent from the tree, so the caret
     * cannot reach it and decoration never touches it.
     *
     * Two things this must get right, and the reason it is measured rather than guessed:
     *
     * - The **leading indentation counts**, because the result is a document offset and
     *   the offset has to land on the first character of the content wherever the quote
     *   starts. Trimming the line first and counting only the marker would be right for
     *   an unindented quote and wrong for an indented one.
     * - **Only the outer marker is stripped.** `"> > nested"` must yield `" > nested"`,
     *   which is itself a quote line, and it does: this measures one `>` plus at most one
     *   following space and stops. A loop that kept stripping would eat the inner marker
     *   and flatten the nesting. See [nestedQuotesKeepTheirInnerMarker].
     */
    private fun blockQuoteMarkerWidth(line: String): Int {
        var i = 0
        // Up to three spaces of indentation, then the marker.
        while (i < line.length && i < 3 && line[i] == ' ') i++
        if (i >= line.length || line[i] != '>') return 0
        i++
        // Exactly one optional space after the marker — not `trimStart`, which would
        // swallow indentation that follows and misplace the offset.
        if (i < line.length && line[i] == ' ') i++
        return i
    }

    private fun parseBlockQuote(text: String, start: Int, end: Int): Pair<Int, BlockQuoteNode> {
        var cursor = start
        var contentStart = -1
        var contentEnd = start
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)
            if (!isBlockQuote(line)) break
            // Measured from this line, not from the first one: a quote's later lines can
            // be indented differently, and each line's content starts after its own
            // marker. Only the first line's offset is used, because `parseBlocks` is
            // handed a single start and reads forward from there — but measuring rather
            // than assuming is what keeps an unspaced marker from eating a character.
            if (contentStart < 0) contentStart = cursor + blockQuoteMarkerWidth(line)
            contentEnd = lineEnd
            // One terminator, not every consecutive one.
            //
            // `skipNewline` here swallowed the blank line between `> a` and `> b`, so
            // the two quotes became one and a paragraph break visibly did nothing.
            // Stepping over exactly one terminator leaves the blank line in place,
            // where the loop above sees it as not a quote and stops.
            cursor = pastLineTerminator(text, lineEnd, end)
        }
        val children = if (contentStart >= 0) parseBlocks(text, contentStart, contentEnd) else emptyList()
        return cursor to BlockQuoteNode(
            range = InlineRange(start, contentEnd.coerceAtLeast(start)),
            children = children,
        )
    }

    /**
     * The offset just past **one** line terminator at [from], or [end].
     *
     * [skipNewline] skips every consecutive newline, which is right when stepping
     * over a run of blank lines and wrong at the end of a block's own content: there
     * it swallows the blank line that belongs to the gap between blocks and hands the
     * block a range that reaches into it. `\r\n` counts as one terminator, not two.
     */
    private fun pastLineTerminator(text: String, from: Int, end: Int): Int {
        var i = from
        if (i < end && text[i] == '\r') {
            i++
            if (i < end && text[i] == '\n') i++
            return i
        }
        if (i < end && text[i] == '\n') i++
        return i
    }

    /**
     * Where a table's rows end.
     *
     * Returns the offset just past the last row's own line terminator, **not** past the
     * blank lines that follow it. Returning the position the scan had *reached* rather
     * than the position the table *ended* at is what made a table's range run on into
     * whatever came next: `blockAt` resolves offsets by falling back to "the last block
     * starting at or before this offset", so every offset in the swallowed region
     * resolved to the table.
     */
    private fun endOfTable(text: String, from: Int, end: Int, columns: Int): Int {
        var cursor = skipNewline(text, from, end)
        var lastRowEnd = cursor
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)
            // Inside a table that is already established, a body row only needs a pipe. The
            // opening `|` was required here before GFM's unbracketed form was
            // supported, and keeping it meant a body row written as `1 | 2` fell out
            // of the table and became a paragraph of its own.
            // `columns` rather than a pipe count, because a two-column row written
            // without outer pipes has exactly one pipe and a fixed threshold would
            // quietly exclude it.
            //
            // The blank check is explicit because an empty line has a column count of
            // one: without it a single-column table runs on past its own content and
            // swallows the gap, which is the defect `aTableDoesNotClaimTheBlankLinesAfterIt`
            // exists to catch.
            if (line.isBlank()) break
            if (!isTableRow(line) && !isTableDelimiter(line) && columnCount(line) < columns) break
            lastRowEnd = pastLineTerminator(text, lineEnd, end)
            cursor = lastRowEnd
        }
        return lastRowEnd
    }

    private fun tableRows(text: String, start: Int, end: Int, columns: Int): List<TableRowNode> {
        val rows = mutableListOf<TableRowNode>()
        var cursor = start
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)
            // A body row needs a pipe but not an opening one, matching GFM. The column
            // count is not re-checked here: GFM tolerates short rows, and rejecting
            // them would drop content the author wrote.
            if (!isTableRow(line) && columnCount(line) < columns) {
                cursor = skipNewline(text, lineEnd, end)
                continue
            }
            if (!isTableDelimiter(line)) {
                rows += TableRowNode(
                    range = InlineRange(cursor, lineEnd),
                    cells = parseCells(text, cursor, lineEnd),
                )
            }
            cursor = skipNewline(text, lineEnd, end)
        }
        return rows
    }

    private fun parseCells(text: String, start: Int, end: Int): List<TableCellNode> {
        val cells = mutableListOf<TableCellNode>()
        var offset = start

        // Indentation, then an optional opening pipe.
        //
        // The indentation was not skipped before, so an indented table's first cell
        // began with its own leading spaces and an indented `| A |` table did not
        // recognise its opening pipe at all. GFM allows up to three spaces.
        while (offset < end && (text[offset] == ' ' || text[offset] == '\t')) offset++
        if (offset < end && text[offset] == '|') offset++

        var cellStart = offset
        var i = offset
        while (i < end) {
            when (text[i]) {
                // An escaped pipe is content, not a cell boundary. Skipping it keeps
                // this loop agreeing with `countUnescapedPipes`, which decides how many
                // columns the row has — if the two disagreed, a row could be counted as
                // two columns here and one there.
                '\\' -> i++
                '|' -> {
                    cells += cellAt(text, cellStart, i)
                    cellStart = i + 1
                }
            }
            i++
        }
        if (cellStart < end) cells += cellAt(text, cellStart, end)
        return cells
    }

    /**
     * One cell, with its padding trimmed off the range.
     *
     * Trimming matters because the range is what the decorator styles and what
     * `VisualToSourceMapper` maps a selection through. Leaving the padding inside
     * meant an emphasis span or a clicked cell included spaces the author wrote only
     * to line the columns up.
     */
    private fun cellAt(text: String, from: Int, to: Int): TableCellNode {
        var start = from
        var end = to
        while (start < end && (text[start] == ' ' || text[start] == '\t')) start++
        while (end > start && (text[end - 1] == ' ' || text[end - 1] == '\t')) end--
        return TableCellNode(
            range = InlineRange(start, end),
            inlines = parseInline(text, start, end),
        )
    }

    /**
     * Parses inline syntax between [start] and [end].
     *
     * Host extensions run first and their ranges are removed from the text the
     * built-in scanner sees, so a wikilink is never re-interpreted as a Markdown
     * link. This is what lets a host add a dialect without forking the parser.
     */
    internal fun parseInline(text: String, start: Int, end: Int): List<InlineNode> {
        if (start >= end) return emptyList()

        val hostMatches = extensions.flatMap { extension ->
            extension.recogniseInline(BlockContext(text, InlineRange(start, end)))
                .filter { it.start >= start && it.end <= end }
                .map { extension to it }
        }
        val resolved = resolveMatches(hostMatches)

        val nodes = mutableListOf<InlineNode>()
        var cursor = start

        for (match in resolved) {
            if (match.range.start > cursor) {
                nodes += parseBuiltInInline(text, cursor, match.range.start)
            }
            nodes += HostInlineNode(match.range, match.extensionId)
            cursor = match.range.end
        }
        if (cursor < end) {
            nodes += parseBuiltInInline(text, cursor, end)
        }
        return nodes
    }

    private fun parseBuiltInInline(text: String, start: Int, end: Int): List<InlineNode> {
        val nodes = mutableListOf<InlineNode>()
        var cursor = start
        var textStart = start

        fun flushText(upTo: Int) {
            if (upTo > textStart) {
                nodes += TextNode(InlineRange(textStart, upTo), text.substring(textStart, upTo))
            }
        }

        while (cursor < end) {
            val char = text[cursor]
            when {
                char == '`' -> {
                    // The same run-length rule `*`, `_` and `~` already use below.
                    //
                    // CommonMark: a code span opens with a run of N backticks and is
                    // closed by a run of exactly N. Searching for "the next backtick of
                    // any length" split `` `` a ` b `` `` into three spans and two
                    // stray highlighted empty regions, because the first two backticks
                    // paired as an empty span and the interior was read as text.
                    //
                    // Code spans predate [runLengthAt] and [findClosingRun] and were
                    // never migrated, so this removes a hand-rolled special case in
                    // favour of the general one rather than adding a second rule.
                    val runLength = runLengthAt(text, cursor, end, '`')
                    val closing = findClosingRun(text, cursor + runLength, end, '`', runLength)
                    if (closing >= 0) {
                        flushText(cursor)
                        // The content, without the delimiters — `CodeSpanNode.code` is
                        // what the renderer shows, so the backticks must not be in it.
                        nodes += CodeSpanNode(
                            InlineRange(cursor, closing + runLength),
                            text.substring(cursor + runLength, closing),
                        )
                        cursor = closing + runLength
                        textStart = cursor
                        continue
                    }
                    // Unmatched: the run stays literal text rather than pairing with a
                    // shorter run somewhere else in the block.
                }
                char == '*' || char == '_' -> {
                    val runLength = runLengthAt(text, cursor, end, char)
                    val closing = findClosingRun(text, cursor + runLength, end, char, runLength)
                    if (closing >= 0) {
                        flushText(cursor)
                        val inner = InlineRange(cursor + runLength, closing)
                        nodes += EmphasisNode(
                            range = InlineRange(cursor, closing + runLength),
                            children = parseInline(text, inner.start, inner.end),
                            strong = runLength >= 2,
                        )
                        cursor = closing + runLength
                        textStart = cursor
                        continue
                    }
                }
                char == '!' && cursor + 1 < end && text[cursor + 1] == '[' -> {
                    val link = parseLinkLike(text, cursor + 1, end)
                    if (link != null) {
                        flushText(cursor)
                        nodes += ImageNode(
                            range = link.first,
                            altText = text.substring(link.second.first.start, link.second.first.end),
                            destination = link.second.second,
                        )
                        cursor = link.first.end
                        textStart = cursor
                        continue
                    }
                }
                char == '[' -> {
                    val link = parseLinkLike(text, cursor, end)
                    if (link != null) {
                        flushText(cursor)
                        nodes += LinkNode(link.first, link.second.first, link.second.second)
                        cursor = link.first.end
                        textStart = cursor
                        continue
                    }
                }
                char == '~' -> {
                    val runLength = runLengthAt(text, cursor, end, '~')
                    if (runLength >= 2) {
                        val closing = findClosingRun(text, cursor + runLength, end, '~', runLength)
                        if (closing >= 0) {
                            flushText(cursor)
                            nodes += EmphasisNode(
                                range = InlineRange(cursor, closing + runLength),
                                children = parseInline(text, cursor + runLength, closing),
                                strong = false,
                            )
                            cursor = closing + runLength
                            textStart = cursor
                            continue
                        }
                    }
                }
            }
            cursor++
        }
        flushText(end)
        return nodes
    }

    /** Returns the node's range and its (labelRange, destination). */
    private fun parseLinkLike(
        text: String,
        start: Int,
        end: Int,
    ): Pair<InlineRange, Pair<InlineRange, String>>? {
        val labelEnd = matchingBracket(text, start, end) ?: return null
        if (labelEnd + 1 >= end || text[labelEnd + 1] != '(') return null
        // Bounded by [end] rather than `indexOf`'s default of end-of-document.
        //
        // Decoration runs on **every frame** by design — Compose hands the renderer a
        // fresh buffer each time — so an unbounded scan here makes every frame cost
        // O(document length) for a block holding one unmatched `(`. The same applies to
        // the backtick search above. `String.indexOf` has no end-bound overload, so the
        // bound is applied to the result; a `)` past `end` belongs to a later block and
        // must not close this link.
        val destEnd = text.indexOf(')', labelEnd + 2)
        if (destEnd < 0 || destEnd >= end) return null
        val label = InlineRange(start + 1, labelEnd)
        val destination = text.substring(labelEnd + 2, destEnd).trim()
        return InlineRange(start, destEnd + 1) to (label to destination)
    }

    private fun matchingBracket(text: String, open: Int, end: Int): Int? {
        var depth = 0
        for (i in open until end) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun runLengthAt(text: String, start: Int, end: Int, char: Char): Int {
        var length = 0
        var i = start
        while (i < end && text[i] == char) {
            length++
            i++
        }
        return length
    }

    private fun findClosingRun(
        text: String,
        from: Int,
        end: Int,
        char: Char,
        length: Int,
    ): Int {
        var i = from
        while (i < end) {
            if (text[i] == char) {
                val run = runLengthAt(text, i, end, char)
                if (run >= length) return i
                i += run
                continue
            }
            i++
        }
        return -1
    }

    /**
     * Where the paragraph starting at [start] ends.
     *
     * Advances one line at a time rather than skipping every newline: a paragraph
     * is terminated *by* a blank line, so stepping over consecutive newlines would
     * step over the terminator and silently merge every paragraph in the document
     * into one.
     */
    private fun endOfParagraph(text: String, start: Int, end: Int): Int {
        var cursor = start
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)
            val interrupts = line.isBlank() ||
                isAtxHeading(line) != null ||
                isFence(line) ||
                isThematicBreak(line) ||
                isListMarker(line) != null ||
                isBlockQuote(line)
            if (interrupts) break
            cursor = nextLineStart(text, lineEnd, end)
        }
        return cursor.coerceAtLeast(start + 1)
    }

    /**
     * The offset just past the next line terminator after [from], or [end].
     *
     * Advances past the whole terminator, so a `\r\n` pair is one break rather than
     * two — which is what previously gave a CRLF document the wrong blocks from its LF
     * twin.
     */
    private fun nextLineStart(text: String, from: Int, end: Int): Int {
        var i = from
        while (i < end && !isLineTerminatorChar(text[i])) i++
        return if (i < end) pastLineTerminator(text, i, end) else end
    }

    private fun endOfFence(text: String, start: Int, end: Int): Int {
        val opening = text.substring(start, lineEndAt(text, start, end))
        val fenceChar = opening.trimStart().first()
        val fenceLength = opening.trimStart().takeWhile { it == fenceChar }.length
        var cursor = skipNewline(text, lineEndAt(text, start, end), end)
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd).trim()
            val run = line.takeWhile { it == fenceChar }
            // A closing fence is at least as long as the opening one and has no
            // info string. This is what lets "````" wrap a line containing "```".
            if (run.length >= fenceLength && run.length >= 3 && line.drop(run.length).isBlank()) {
                // The fence ends at its closing marker, not past the line terminator
                // that follows it. That terminator is the gap between this block and
                // the next, and a block whose range reaches into the gap makes
                // `blockAt` misroute offsets there and puts a window boundary in the
                // wrong place. The scanner steps over it on the next pass.
                return lineEnd
            }
cursor = skipNewline(text, lineEnd, end)
        }
        return end
    }

    /** Where the closing fence line begins, or null when the fence is unclosed. */
    private fun closingFenceStart(text: String, start: Int, end: Int): Int? {
        val opening = text.substring(start, lineEndAt(text, start, end))
        val fenceChar = opening.trimStart().first()
        val fenceLength = opening.trimStart().takeWhile { it == fenceChar }.length
        var cursor = skipNewline(text, lineEndAt(text, start, end), end)
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd).trim()
            val run = line.takeWhile { it == fenceChar }
            if (run.length >= fenceLength && run.length >= 3 && line.drop(run.length).isBlank()) return cursor
            cursor = skipNewline(text, lineEnd, end)
        }
        return null
    }

    private fun fenceInfo(text: String, start: Int, end: Int): String {
        val opening = text.substring(start, lineEndAt(text, start, end))
        val trimmed = opening.trimStart()
        val fenceChar = trimmed.first()
        val fence = trimmed.takeWhile { it == fenceChar }
        return opening.drop(opening.indexOf(fence) + fence.length).trim()
    }

    /** Whether [c] terminates a line. `\r\n` is one terminator of two characters. */
    private fun isLineTerminatorChar(c: Char): Boolean = c == '\n' || c == '\r'

    /**
     * [end] walked back over any line terminator, never below [floor].
     *
     * A block's range must stop at its last piece of *content*. The line terminator
     * after it belongs to the gap between blocks, and a block that reaches into the
     * gap makes `blockAt` misroute those offsets and puts a window boundary in the
     * wrong place. Applied to the node's range only — the scan cursor keeps using the
     * untrimmed extent, or the parser would re-read the terminator as new content.
     */
    private fun endOfContent(text: String, end: Int, floor: Int): Int {
        var e = end.coerceIn(floor, text.length)
        while (e > floor && isLineTerminatorChar(text[e - 1])) e--
        return e
    }

    /**
     * The offset of the line terminator at or after [start], or [end].
     *
     * Stops at `\r` as well as `\n`, so a CRLF line's range covers its content and
     * not its carriage return. Searching only for `\n` left every CRLF document's
     * blocks ending on a `\r`, which is not the block's own text and which put the
     * terminator inside inline ranges. `\r\n` is one terminator of two characters, so
     * stopping at the `\r` is what makes that read as one break.
     */
    private fun lineEndAt(text: String, start: Int, end: Int): Int {
        var i = start
        while (i < end && !isLineTerminatorChar(text[i])) i++
        return i
    }

    /**
     * [from] advanced past a run of line terminators.
     *
     * Matches [lineEndAt] by recognising `\r` as well as `\n`. When it did not, a
     * cursor sitting on a bare `\r` made no progress at all, so a CRLF document could
     * spin here without advancing — and stopping at the terminator that *begins* a
     * blank line is not the same as crossing it.
     */
    private fun skipNewline(text: String, from: Int, end: Int): Int {
        var i = from
        while (i < end && isLineTerminatorChar(text[i])) i++
        return i
    }

    /** Leading whitespace in both measures from a single scan: columns (tab=4) and character width. */
    internal data class LeadingWhitespace(val columns: Int, val widthChars: Int)

    internal fun leadingWhitespaceOf(line: String): LeadingWhitespace {
        var columns = 0
        var width = 0
        for (c in line) {
            when (c) {
                ' ' -> { columns++; width++ }
                '\t' -> { columns += 4; width++ }
                else -> break
            }
        }
        return LeadingWhitespace(columns, width)
    }

    /**
     * How many **columns** [line] is indented, with a tab counting as four.
 *
     * The right answer to a column question, which is what CommonMark asks: indentation
     * is measured in columns against tab stops, because that is what decides whether a
     * line continues a list item. It is compared against other lines' indentation in
 * [endOfListItem] and against a running minimum in [parseList], and it is what
 * [ListItemNode.indent] reports, since nesting level is a column question too.
 *
     * It is **not** the right answer to a character question. A tab is four columns but
 * *one* character, so using this as an offset into the document overshoots by three per
 * * tab. See [indentWidthOf] for that.
     */
    private fun indentOf(line: String): Int = leadingWhitespaceOf(line).columns

    /**
     * How many **characters** of [line] are leading indentation.
     *
     * The companion to [indentOf], for the places that compute an offset into the
     * document rather than a nesting level. Every range this parser emits indexes the
     * original text one-for-one, so an offset has to count characters.
     *
     * Conflating the two is not subtle — it is wrong by three per tab, and silently.
     * `"\t- item"` used to compute its content start as `indent + markerLength`, that is
     * `4 + 2`, putting the item's inline content at offset 6 of a 7-character item: one
     * past the end, holding `"m"`. Nothing threw and no range escaped the document;
     * `BlockRangePropertyTest`'s invariants all held, because the ranges stayed disjoint
     * while covering the wrong text. That is the shape of bug a range invariant cannot
     * see, and why the tests for the two of this family assert on content.
     */
    private fun indentWidthOf(line: String): Int = leadingWhitespaceOf(line).widthChars

    /**
     * Whether [line] opens a fenced code block.
     *
     * An info string is allowed after the fence: "```markdown" opens a fence, and
     * rejecting it because the language name is not blank is exactly the bug that
     * turns a fenced block into a paragraph followed by a heading.
     */
    private fun isFence(line: String): Boolean {
        val trimmed = line.trimStart()
        val fenceChar = when {
            trimmed.startsWith("```") -> '`'
            trimmed.startsWith("~~~") -> '~'
            else -> return false
        }
        val run = trimmed.takeWhile { it == fenceChar }
        if (run.length < 3) return false
        // A backtick fence's info string may not contain a backtick.
        return fenceChar != '`' || !trimmed.drop(run.length).contains('`')
    }

    /**
     * Whether [line] is a thematic break: three or more matching `-`, `*` or `_`,
     * with optional whitespace between them.
     *
     * Written as a single scan rather than as "the set of characters is exactly
     * `{-}`". That earlier version built the set from the whole trimmed line, spaces
     * included, so `chars == setOf('-')` was false for `"- - -"` — the set is
     * `{'-', ' '}` — and the predicate failed even though the very next clause,
     * `all { it == '-' || … || it == ' ' }`, shows spaces were meant to be allowed.
     * The set test silently forbade what the character test permitted.
     *
     * CommonMark: a line of three or more matching markers, with spaces or tabs
     * allowed *between* them, and — where a thematic break and a list item are both
     * possible readings — **the break wins**. The branch order in [parseBlockAt] puts
     * this test ahead of [isListMarker], so that precedence holds; it is asserted
     * rather than assumed, in `aSpacedThematicBreakIsNotAListItem`.
     *
     * Tab-separated breaks were rejected before, because the old length check counted
     * a tab as one character and the character test did not allow it at all. CommonMark
     * accepts them, so both are handled here.
     */
    private fun isThematicBreak(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        var marker: Char? = null
        var markers = 0
        for (c in trimmed) {
            if (c == ' ' || c == '\t') continue
            if (c != '-' && c != '*' && c != '_') return false
            if (marker == null) {
                marker = c
            } else if (c != marker) {
                return false
            }
            markers++
        }
        return markers >= 3
    }

    private fun isAtxHeading(line: String): Int? {
        val trimmed = line.trimStart()
        var level = 0
        while (level < trimmed.length && trimmed[level] == '#') level++
        if (level == 0 || level > 6) return null
        if (level < trimmed.length && !trimmed[level].isWhitespace()) return null
        return level
    }

    /**
     * How many characters of [line] the heading markers and following spaces take.
     *
     * The heading's inline content starts after this. The `#` loop has to advance
     * `i` as well as count: counting without advancing never terminates.
     */
    private fun headingPrefixLength(line: String): Int {
        var i = 0
        while (i < line.length && line[i] == ' ') i++
        while (i < line.length && line[i] == '#') i++
        while (i < line.length && line[i] == ' ') i++
        return i
    }

    /** Whether [line] starts with a numeric marker, e.g. "1." or "2)". */
    private fun isOrderedMarker(line: String): Boolean {
        val rest = line.trimStart()
        var i = 0
        while (i < rest.length && rest[i].isDigit() && i < 9) i++
        return i > 0 && i + 1 < rest.length && (rest[i] == '.' || rest[i] == ')') && rest[i + 1] == ' '
    }

    /** Returns the indent of a list marker, or null when the line is not a list item. */
    private fun isListMarker(line: String): Int? {
        val indent = indentOf(line)
        val rest = line.trimStart()
        if (rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ")) return indent
        // Task list items are list items.
        if (rest.startsWith("- [") || rest.startsWith("* [") || rest.startsWith("+ [")) return indent
        var i = 0
        while (i < rest.length && rest[i].isDigit() && i < 9) i++
        if (i > 0 && i + 1 < rest.length && (rest[i] == '.' || rest[i] == ')') && rest[i + 1] == ' ') {
            return indent
        }
        return null
    }

    private fun markerLength(line: String): Int {
        val rest = line.trimStart()
        if (rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ")) return 2
        if (rest.startsWith("- [") || rest.startsWith("* [") || rest.startsWith("+ [")) return 4
        var i = 0
        while (i < rest.length && rest[i].isDigit()) i++
        if (i > 0 && i + 1 < rest.length && (rest[i] == '.' || rest[i] == ')')) return i + 2
        return 1
    }

    private fun isBlockQuote(line: String): Boolean = line.trimStart().startsWith(">")

/**
     * Whether [line] is a table's delimiter row: pipes, dashes and colons only, with
     * at least one dash per cell.
     *
     * The pipe requirement is load-bearing. A delimiter row is a row of *cells*; a
     * line of nothing but dashes is a thematic break, and without this check `---`,
     * `--` and `- - -` all qualified — which meant a horizontal rule immediately after
     * a table was eaten as one more table row and the table's range grew to cover it.
     */
    private fun isTableDelimiter(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
        if (!trimmed.contains('|')) return false
        if (!trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' }) return false
        // Every cell between pipes must contain a dash, otherwise a paragraph of
        // dashes would qualify.
        //
        // Each cell is trimmed before its characters are checked. `--- | ---` splits
        // into ["--- ", " ---"], and without the trim the padding spaces fail the
        // character test below — so a delimiter row spaced like an ordinary table
        // was rejected while a tightly-written one was accepted. That asymmetry is
        // what made `A | B` unparseable while `|A | B|` worked: the first cannot be
        // recognised because its delimiter row is rejected, not because its header is.
        val cells = trimmed.removePrefix("|").removeSuffix("|").split('|')
        return cells.isNotEmpty() && cells.all { cell ->
            val trimmedCell = cell.trim()
            trimmedCell.isNotEmpty() && trimmedCell.all { c -> c == '-' || c == ':' }
        }
    }

    private fun isTableRow(line: String): Boolean = line.trim().startsWith("|")

    /**
     * Whether [line] can start a table, given that the next line is a delimiter row.
     *
     * The delimiter row is what makes a table a table — a line with a pipe in it is
     * ordinary prose far more often than it is a header. So the decision is made
     * where the *next* line is known, rather than by asking whether the current line
     * looks table-shaped on its own.
     *
     * Two forms are accepted, because GFM accepts both:
     * - `| A | B |` — outer pipes present, which is what [isTableRow] recognises
     * - `A | B` — no outer pipes, previously unrecognised
     *
     * Requiring a *second* pipe is what keeps prose containing one pipe out: `a | b`
     * is a single-column cell only if the author also wrote a delimiter row, and a
     * one-column table is not something GFM defines anyway.
     */
    private fun canStartTable(line: String, delimiterLine: String?): Boolean {
        if (isTableRow(line)) return true
        if (delimiterLine == null || !isTableDelimiter(delimiterLine)) return false
        // The cell counts have to agree, or `a | b` above `--- | --- | ---` would be a
        // table with mismatched columns. GFM requires this too, and it is the check
        // that keeps a stray delimiter line from turning ordinary prose into a table.
        return columnCount(line) == columnCount(delimiterLine)
    }

    /** Cells in a table line, whether or not it carries outer pipes. */
    private fun columnCount(line: String): Int {
        val pipes = countUnescapedPipes(line)
        val trimmed = line.trim()
        val leading = if (trimmed.startsWith("|")) 1 else 0
        val trailing = if (trimmed.endsWith("|") && trimmed.length > 1) 1 else 0
        return pipes - leading - trailing + 1
    }

    /** Unescaped `|` characters, which is what separates cells. */
    /** The line starting at [from], up to its terminator or [end]. */
    private fun lineAt(text: String, from: Int, end: Int): String? {
        if (from >= end) return null
        return text.substring(from, lineEndAt(text, from, end))
    }

    private fun countUnescapedPipes(line: String): Int {
        var count = 0
        var i = 0
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i++
                '|' -> count++
            }
            i++
        }
        return count
    }
}

/** Shifts an [InlineRange] by [by], clamped to `[0, limit]` like [TextRange.shifted]. */
internal fun InlineRange.shifted(by: Int, limit: Int = Int.MAX_VALUE): InlineRange {
    val newStart = (start + by).coerceIn(0, limit)
    val newEnd = (end + by).coerceIn(0, limit)
    return InlineRange(minOf(newStart, newEnd), maxOf(newStart, newEnd))
}

/** A parsed document: the text it was parsed from, and its block tree. */
data class ParsedDocument(
    val text: String,
    val blocks: List<BlockNode>,
) {
    /** Every node in the tree, in document order. */
    val allBlocks: List<BlockNode> get() = blocks.flattenBlocks()

    /** The block containing [offset], or null when the document has none there.
     *
     * Half-open like [InlineRange.contains]: a block `[s, e)` contains `s` and
     * not `e`, so abutting blocks partition the document exactly once and a gap
     * offset belongs to no block. No tail fallback — an offset past every block
     * is not inside one.
     */
    fun blockAt(offset: Int): BlockNode? =
        blocks.firstOrNull { offset >= it.range.start && offset < it.range.end }

    /** The offset at which the block containing [offset] begins. */
    fun blockStartAtOrBefore(offset: Int): Int {
        val block = blockAt(offset) ?: return 0
        return block.range.start
    }

    /** The whole document's extent, for a host extension scanning everything. */
    fun contentRange(): InlineRange = InlineRange(0, text.length)

    /** The block whose range contains [range]. */
    fun blockContaining(range: InlineRange): BlockNode? =
        blocks.firstOrNull { range.start >= it.range.start && range.end <= it.range.end }
            ?: blockAt(range.start)

    companion object {
        val EMPTY: ParsedDocument = ParsedDocument("", emptyList())
    }
}
