package dev.fude.markdown

import dev.fude.core.InlineRange
import dev.fude.syntax.BlockContext
import dev.fude.syntax.BlockMatch
import dev.fude.syntax.SyntaxExtension
import dev.fude.syntax.resolveBlockMatches
import dev.fude.syntax.resolveMatches

/**
 * Counts full parses.
 *
 * The ticket asks for an incremental-reparse test that asserts on a counter rather
 * than a timing, because a timing assertion is a flaky assertion: it fails on a
 * slow machine and passes on a fast one while the code is equally wrong in both.
 * A counter is exact.
 */
public class ReparseCounter {
    public var fullParses: Int = 0
        private set

    public var blockParses: Int = 0
        private set

    public fun recordFullParse() {
        fullParses++
    }

    /**
     * Records one *top-level* block parsed on the incremental path.
     *
     * Top-level only, and only outside a full parse, because those are the two
     * distinctions that make the number mean something. Counting nested blocks would
     * make parsing one list item cost several, and the design commitment is about
     * top-level blocks. Counting blocks inside `parse` would make the full-parse
     * count redundant with the block count.
     *
     * Counting *per block constructed* rather than per call site is the whole point.
     * An earlier version incremented once per loop iteration in [reparse], which
     * reported `blockParses == 1` for a call that had in fact parsed the entire
     * document — so the test guarding this design's central performance claim passed
     * while the property was violated. See the reparse loop for the other half.
     */
    public fun recordBlockParse() {
        blockParses++
    }

    public fun reset() {
        fullParses = 0
        blockParses = 0
    }
}

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
 * `org.jetbrains:markdown` remains the right choice for rendering Markdown to
 * HTML, and for conformance testing this parser against. If a vendor-based
 * incremental parse becomes available it can replace this behind the same
 * interface.
 */
public class IncrementalMarkdownParser(
    private val extensions: List<SyntaxExtension> = emptyList(),
    private val counter: ReparseCounter = ReparseCounter(),
) {
    private var cached: ParsedDocument = ParsedDocument.EMPTY

    /** Parses [text] from scratch. */
    public fun parse(text: String): ParsedDocument {
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
    public fun reparse(text: String, edit: dev.fude.core.Edit): ParsedDocument {
        if (text == cached.text) return cached

        val previous = cached
        if (previous.text.isEmpty() || text.isEmpty()) return parse(text)

        val delta = text.length - previous.text.length
        val editStart = edit.affectedRange.start.coerceIn(0, previous.text.length)

        // `>=`, not `>`: a deletion that starts exactly where one block ends also
        // joins the block after it, so the block before the edit must be reparsed
        // too. With `>`, deleting the blank line between two paragraphs reparsed
        // from the *second* one's start and produced a document whose ranges no
        // longer matched its text.
        val firstAffected = previous.blocks.indexOfFirst { it.range.end >= editStart }
        if (firstAffected < 0) {
            // The edit landed after every block, e.g. an append. Reparse the tail.
            val boundary = previous.blocks.lastOrNull()?.range?.end ?: 0
            // Blocks before the edit are reused untouched, so they are only valid
            // while they still fit. A document that shrank past them has not left
            // the earlier blocks alone, whatever the edit claims.
            if (boundary > text.length) return parse(text)
            val reparsedTail = parseBlocks(text, boundary, text.length, countBlocks = true)
            counter.recordBlockParse()
            val document = ParsedDocument(text, previous.blocks + reparsedTail)
            cached = document
            return document
        }

        val reusableTail = previous.blocks.drop(firstAffected + 1)
        val boundary = previous.blocks[firstAffected].range.start
        val oldEnd = previous.blocks[firstAffected].range.end

        // Reparse forward until we have covered the old affected block's extent.
        val targetEnd = (oldEnd + delta).coerceIn(0, text.length)

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
        val contentEnd = text.indexOfLast { !it.isWhitespace() } + 1

        // Built up from the untouched head rather than concatenated at the end, so a
        // reparse that begins on a table's delimiter row can complete the header row
        // already in `head` into a TableNode, exactly as a full parse would.
        val document = previous.blocks.take(firstAffected).toMutableList()
        var cursor = boundary

        while (cursor < contentEnd) {
            val step = parseOneBlock(text, cursor, contentEnd, document.lastOrNull())
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
            if (cursor >= targetEnd) break
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
            val reparsedRest =
                if (cursor >= contentEnd) emptyList()
                else parseBlocks(text, cursor, contentEnd, countBlocks = true)
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
            val reparsedRest =
                if (cursor >= contentEnd) emptyList()
                else parseBlocks(text, cursor, contentEnd, countBlocks = true)
            cached = ParsedDocument(text, document + reparsedRest)
            return cached
        }

        val remaining = shiftedTail.filter { it.range.end > cursor }

        cached = ParsedDocument(text, document + remaining)
        return cached
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
                counter.recordBlockParse()
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
private class BlockStep(
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
                val openingEnd = skipNewline(text, lineEndAt(text, from, end), fenceEnd)
                // The closing fence line is not content either.
                val contentEnd = closingFenceStart(text, from, fenceEnd) ?: fenceEnd
                step(
                    CodeFenceNode(
                        range = InlineRange(from, fenceEnd),
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
                    val tableEnd = endOfTable(text, lineEnd, end)
                    step(
                        TableNode(
                            range = InlineRange(previous.range.start, tableEnd),
                            children = listOf(previous) + tableRows(text, lineEnd, tableEnd),
                            hasHeader = true,
                        ),
                        tableEnd,
                        replacesPrevious = true,
                    )
                } else {
                    step(null, skipNewline(text, lineEnd, end))
                }
            }

            isTableRow(line) -> {
                val delimiterEnd = lineEndAt(text, lineEnd, end)
                if (isTableDelimiter(text.substring(lineEnd, delimiterEnd))) {
                    val tableEnd = endOfTable(text, delimiterEnd, end)
                    step(
                        TableNode(
                            range = InlineRange(from, tableEnd),
                            children = tableRows(text, from, tableEnd),
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
                // Exclude the trailing newline from the block: a paragraph's range is
                // its content, and a caret at the end of a line is at the block's end,
                // not one past a newline it does not contain.
                val contentEnd = text.lastIndexOf('\n', consumed - 1).let {
                    if (it < from) consumed else it
                }
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
        var cursor = start

        // A range that is empty or entirely blank still yields one empty block, so
        // the renderer always has something to lay out. Collapsing it to no blocks
        // is how a note that is just a newline ends up with no clickable target.
        if (start >= end || text.substring(start, end).isBlank()) {
            if (start < end || start < text.length) {
                blocks += ParagraphNode(InlineRange(start, end.coerceAtLeast(start)), emptyList())
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

            val contentStart = cursor + indent + markerLength(line)
            val itemEnd = endOfListItem(text, lineEnd, end, indent)
            val nested = parseBlocks(text, contentStart, itemEnd)
            // The item's own text is the first line's content; anything further in
            // belongs to a nested block, not to the item's inline run.
            val ownEnd = text.indexOf('\n', contentStart).let { if (it < 0 || it > itemEnd) itemEnd else it }
            items += ListItemNode(
                range = InlineRange(cursor, itemEnd),
                children = nested,
                inlines = parseInline(text, contentStart, ownEnd),
                indent = indent,
            )
            cursor = itemEnd
        }

        val listEnd = items.lastOrNull()?.range?.end ?: start
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

    private fun parseBlockQuote(text: String, start: Int, end: Int): Pair<Int, BlockQuoteNode> {
        var cursor = start
        var contentStart = -1
        var contentEnd = start
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)
            if (!isBlockQuote(line)) break
            if (contentStart < 0) contentStart = cursor + 2
            contentEnd = lineEnd
            cursor = skipNewline(text, lineEnd, end)
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
    private fun endOfTable(text: String, from: Int, end: Int): Int {
        var cursor = skipNewline(text, from, end)
        var lastRowEnd = cursor
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)
            if (!isTableRow(line) && !isTableDelimiter(line)) break
            lastRowEnd = pastLineTerminator(text, lineEnd, end)
            cursor = lastRowEnd
        }
        return lastRowEnd
    }

    private fun tableRows(text: String, start: Int, end: Int): List<TableRowNode> {
        val rows = mutableListOf<TableRowNode>()
        var cursor = start
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)
            if (!isTableRow(line)) {
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
        val line = text.substring(start, end)
        val cells = mutableListOf<TableCellNode>()
        var offset = start
        // Skip the leading pipe.
        if (line.startsWith("|")) offset++

        var cellStart = offset
        for (i in offset until end) {
            if (text[i] == '|') {
                cells += TableCellNode(
                    range = InlineRange(cellStart, i),
                    inlines = parseInline(text, cellStart, i),
                )
                cellStart = i + 1
            }
        }
        if (cellStart < end) {
            cells += TableCellNode(
                range = InlineRange(cellStart, end),
                inlines = parseInline(text, cellStart, end),
            )
        }
        return cells
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
                    val close = text.indexOf('`', cursor + 1)
                    if (close in 0 until end) {
                        flushText(cursor)
                        nodes += CodeSpanNode(InlineRange(cursor, close + 1), text.substring(cursor + 1, close))
                        cursor = close + 1
                        textStart = cursor
                        continue
                    }
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
        val destEnd = text.indexOf(')', labelEnd + 2)
        if (destEnd < 0 || destEnd > end) return null
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

    /** The offset just past the next newline after [from], or [end]. */
    private fun nextLineStart(text: String, from: Int, end: Int): Int {
        var i = from
        while (i < end && text[i] != '\n') i++
        return if (i < end) i + 1 else end
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
                // Exactly one line terminator. Taking every newline here would hand
                // the fence the blank lines that follow it, the same over-extension
                // the table had.
                return pastLineTerminator(text, lineEnd, end).coerceAtLeast(lineEnd)
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

    private fun lineEndAt(text: String, start: Int, end: Int): Int {
        var i = start
        while (i < end && text[i] != '\n') i++
        return i
    }

    private fun skipNewline(text: String, from: Int, end: Int): Int {
        var i = from
        while (i < end && text[i] == '\n') i++
        return i
    }

    private fun indentOf(line: String): Int {
        var count = 0
        for (c in line) {
            when (c) {
                ' ' -> count++
                '\t' -> count += 4
                else -> return count
            }
        }
        return count
    }

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

    private fun isThematicBreak(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        val chars = trimmed.toSet()
        return (chars == setOf('-') || chars == setOf('*') || chars == setOf('_')) &&
            trimmed.all { it == '-' || it == '*' || it == '_' || it == ' ' }
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
        val cells = trimmed.removePrefix("|").removeSuffix("|").split('|')
        return cells.isNotEmpty() && cells.all { it.isNotBlank() && it.all { c -> c == '-' || c == ':' } }
    }

    private fun isTableRow(line: String): Boolean = line.trim().startsWith("|")
}

/** Shifts an [InlineRange] by [by], clamped to non-negative. */
internal fun InlineRange.shifted(by: Int): InlineRange =
    InlineRange((start + by).coerceAtLeast(0), (end + by).coerceAtLeast(0))

/** A parsed document: the text it was parsed from, and its block tree. */
public data class ParsedDocument(
    val text: String,
    val blocks: List<BlockNode>,
) {
    /** Every node in the tree, in document order. */
    public val allBlocks: List<BlockNode> get() = blocks.flattenBlocks()

    /** The block containing [offset], or null when the document has none there. */
    public fun blockAt(offset: Int): BlockNode? =
        blocks.firstOrNull { offset >= it.range.start && offset <= it.range.end }
            ?: blocks.lastOrNull { it.range.start <= offset }

    /** The offset at which the block containing [offset] begins. */
    public fun blockStartAtOrBefore(offset: Int): Int {
        val block = blockAt(offset) ?: return 0
        return block.range.start
    }

    /** The whole document's extent, for a host extension scanning everything. */
    public fun contentRange(): InlineRange = InlineRange(0, text.length)

    /** The block whose range contains [range]. */
    public fun blockContaining(range: InlineRange): BlockNode? =
        blocks.firstOrNull { range.start >= it.range.start && range.end <= it.range.end }
            ?: blockAt(range.start)

    public companion object {
        public val EMPTY: ParsedDocument = ParsedDocument("", emptyList())
    }
}
