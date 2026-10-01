package dev.fude.markdown

import dev.fude.core.InlineRange
import dev.fude.syntax.BlockContext
import dev.fude.syntax.BlockMatch
import dev.fude.syntax.SyntaxExtension
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
            val reparsedTail = parseBlocks(text, boundary, text.length)
            counter.recordBlockParse()
            val document = ParsedDocument(text, previous.blocks + reparsedTail)
            cached = document
            return document
        }

        val boundary = previous.blocks[firstAffected].range.start
        val shiftedTail = previous.blocks.drop(firstAffected + 1).map { shiftBlock(it, delta) }

        // Reparse forward until we have covered the old affected block's extent.
        val targetEnd = (previous.blocks[firstAffected].range.end + delta).coerceIn(0, text.length)
        val reparsed = mutableListOf<BlockNode>()
        var cursor = boundary

        while (cursor < text.length) {
            val (block, next) = parseOneBlock(text, cursor, text.length)
            counter.recordBlockParse()
            if (next <= cursor) break
            reparsed += block
            cursor = next
            if (cursor >= targetEnd) break
        }

        // If the reparse ran past where the shifted tail begins, the edit merged
        // blocks and the tail has been re-parsed already. Reusing it would duplicate.
        val tailStart = shiftedTail.firstOrNull()?.range?.start
        val tailConsumed = tailStart != null && cursor > tailStart
        val head = previous.blocks.take(firstAffected)

        val document = ParsedDocument(
            text = text,
            blocks = if (tailConsumed) head + reparsed else head + reparsed + shiftedTail,
        )
        cached = document
        return document
    }

    /**
     * Parses the single block starting at [start].
     *
     * @return the block and the offset the next block begins at.
     */
    private fun parseOneBlock(
        text: String,
        start: Int,
        end: Int,
    ): Pair<BlockNode, Int> {
        val blocks: List<BlockNode> = parseBlocks(text, start, end)
        val first: BlockNode = blocks.firstOrNull() ?: return Pair(blankNode(start, end), end)
        val next: Int = blocks.getOrNull(1)?.range?.start?.coerceAtLeast(first.range.end)
            ?: first.range.end.coerceAtLeast(start + 1)
        return Pair(first, next)
    }

    private fun blankNode(start: Int, end: Int): BlockNode =
        ParagraphNode(InlineRange(start, maxOf(end, start)), emptyList())

    /** Shifts a block and its descendants by [by], without re-parsing them. */
    private fun shiftBlock(node: BlockNode, by: Int): BlockNode = when (node) {
        is ParagraphNode -> node.copy(range = node.range.shifted(by))
        is HeadingNode -> node.copy(range = node.range.shifted(by))
        is ListNode -> node.copy(range = node.range.shifted(by), children = node.children.map { shiftBlock(it, by) })
        is ListItemNode -> node.copy(range = node.range.shifted(by), children = node.children.map { shiftBlock(it, by) })
        is BlockQuoteNode -> node.copy(range = node.range.shifted(by), children = node.children.map { shiftBlock(it, by) })
        is CodeFenceNode -> node.copy(range = node.range.shifted(by), contentRange = node.contentRange.shifted(by))
        is TableNode -> node.copy(range = node.range.shifted(by), children = node.children.map { shiftBlock(it, by) })
        is TableRowNode -> node.copy(range = node.range.shifted(by), cells = node.cells.map { shiftBlock(it, by) as TableCellNode })
        is TableCellNode -> node.copy(range = node.range.shifted(by))
        is ThematicBreakNode -> node.copy(range = node.range.shifted(by))
        is HostBlockNode -> node.copy(range = node.range.shifted(by))
    }

    /** Parses [text] between [start] and [end] into top-level blocks. */
    private fun parseBlocks(text: String, start: Int, end: Int): List<BlockNode> {
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
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)

            when {
                line.isBlank() -> cursor = skipNewline(text, lineEnd, end)
                isFence(line) -> {
                    val fenceEnd = endOfFence(text, cursor, end)
                    val openingEnd = skipNewline(text, lineEndAt(text, cursor, end), fenceEnd)
                    // The closing fence line is not content either.
                    val contentEnd = closingFenceStart(text, cursor, fenceEnd) ?: fenceEnd
                    blocks += CodeFenceNode(
                        range = InlineRange(cursor, fenceEnd),
                        contentRange = InlineRange(openingEnd, contentEnd.coerceAtLeast(openingEnd)),
                        info = fenceInfo(text, cursor, fenceEnd),
                    )
                    cursor = fenceEnd
                }
                isThematicBreak(line) -> {
                    blocks += ThematicBreakNode(InlineRange(cursor, lineEnd))
                    cursor = skipNewline(text, lineEnd, end)
                }
                isAtxHeading(line) != null -> {
                    val level = isAtxHeading(line)!!
                    blocks += HeadingNode(
                        range = InlineRange(cursor, lineEnd),
                        level = level,
                        inlines = parseInline(text, cursor + headingPrefixLength(line), lineEnd),
                    )
                    cursor = skipNewline(text, lineEnd, end)
                }
                isListMarker(line) != null -> {
                    val (consumed, node) = parseList(text, cursor, end)
                    blocks += node
                    cursor = consumed
                }
                isTableDelimiter(line) -> {
                    // A delimiter row only means something after a header row.
                    val previous = blocks.lastOrNull()
                    if (previous is TableRowNode) {
                        val tableEnd = endOfTable(text, lineEnd, end)
                        blocks[blocks.lastIndex] = TableNode(
                            range = InlineRange(previous.range.start, tableEnd),
                            children = listOf(previous) + tableRows(text, lineEnd, tableEnd),
                            hasHeader = true,
                        )
                        cursor = tableEnd
                    } else {
                        cursor = skipNewline(text, lineEnd, end)
                    }
                }
                isTableRow(line) -> {
                    val nextLineEnd = lineEndAt(text, lineEnd, end)
                    val nextLine = text.substring(lineEnd, nextLineEnd)
                    if (isTableDelimiter(nextLine)) {
                        val tableEnd = endOfTable(text, nextLineEnd, end)
                        blocks += TableNode(
                            range = InlineRange(cursor, tableEnd),
                            children = tableRows(text, cursor, tableEnd),
                            hasHeader = true,
                        )
                        cursor = tableEnd
                    } else {
                        blocks += TableRowNode(
                            range = InlineRange(cursor, lineEnd),
                            cells = parseCells(text, cursor, lineEnd),
                        )
                        cursor = skipNewline(text, lineEnd, end)
                    }
                }
                isBlockQuote(line) -> {
                    val (consumed, node) = parseBlockQuote(text, cursor, end)
                    blocks += node
                    cursor = consumed
                }
                else -> {
                    val consumed = endOfParagraph(text, cursor, end)
                    // Exclude the trailing newline from the block: a paragraph's
                    // range is its content, and a caret at the end of a line is at
                    // the block's end, not one past a newline it does not contain.
                    val contentEnd = text.lastIndexOf('\n', consumed - 1).let {
                        if (it < cursor) consumed else it
                    }
                    blocks += ParagraphNode(
                        range = InlineRange(cursor, contentEnd),
                        inlines = parseInline(text, cursor, contentEnd),
                    )
                    cursor = consumed
                }
            }
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
        var cursor = skipNewline(text, from, end)
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

    private fun endOfTable(text: String, from: Int, end: Int): Int {
        var cursor = skipNewline(text, from, end)
        while (cursor < end) {
            val lineEnd = lineEndAt(text, cursor, end)
            val line = text.substring(cursor, lineEnd)
            if (!isTableRow(line) && !isTableDelimiter(line)) break
            cursor = skipNewline(text, lineEnd, end)
        }
        return cursor
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
                return skipNewline(text, lineEnd, end).coerceAtLeast(lineEnd)
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
     * Whether [line] is a table's delimiter row: pipes, dashes and colons only,
     * with at least one dash per cell.
     */
    private fun isTableDelimiter(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
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

    /** The block whose range contains [range]. */
    public fun blockContaining(range: InlineRange): BlockNode? =
        blocks.firstOrNull { range.start >= it.range.start && range.end <= it.range.end }
            ?: blockAt(range.start)

    public companion object {
        public val EMPTY: ParsedDocument = ParsedDocument("", emptyList())
    }
}
