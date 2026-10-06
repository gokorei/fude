package dev.fude.markdown

import dev.fude.core.Edit
import dev.fude.core.InlineRange
import dev.fude.core.Replace
import dev.fude.core.TextRange

/**
 * Removes one level of Markdown list indentation from a line or a selection.
 *
 * This lives beside the parser rather than in `:fude-core` on purpose. Deciding
 * whether a line can be outdented requires knowing whether it is inside a fenced
 * code block, and that is a question about the block tree — so the answer comes
 * from [ParsedDocument] rather than from a second fence-tracker written here. Two
 * implementations of "is this line code" is two answers, and they would drift.
 *
 * Everything below is pure: text in, [Edit]? out. The composable applies the edit
 * and the existing per-keystroke effect records it in undo, so an outdent is one
 * undo step without any of that being duplicated here.
 */
internal object ListIndent {

    /**
     * One indent level, in columns.
     *
     * CommonMark treats a tab as advancing to the next multiple of four, and the
     * parser is hand-written rather than CommonMark-complete, so this states the
     * choice instead of leaving it to whichever branch of the code happened to run.
     */
    private const val INDENT_COLUMNS = 4

    /** The edit that outdents the line containing [offset], or null if it cannot. */
    internal fun outdentAt(parsed: ParsedDocument, offset: Int): Edit? =
        outdentLines(parsed, offset, offset)

    /**
     * The edit that outdents every line touched by the selection, or null if none
     * of them can be.
     *
     * One [Edit] spans all of it, deliberately. Outdenting six selected lines as six
     * edits gives the user six undos for one gesture, and undo coalescing is
     * decided by edit adjacency rather than by intent.
     */
    internal fun outdentRange(parsed: ParsedDocument, start: Int, end: Int): Edit? =
        outdentLines(parsed, minOf(start, end), maxOf(start, end))

    private fun outdentLines(parsed: ParsedDocument, start: Int, end: Int): Edit? {
        val text = parsed.text
        if (text.isEmpty()) return null
        val lo = start.coerceIn(0, text.length)
        val hi = end.coerceIn(lo, text.length)

        val firstLineStart = lineStartAt(text, lo)
        val lastLineEnd = lineEndAt(text, hi)

        val out = StringBuilder()
        var changed = false
        var at = firstLineStart
        while (at <= lastLineEnd) {
            val stop = lineEndAt(text, at)
            val line = text.substring(at, stop)
            val rewritten = if (isOutdentable(parsed, at)) rewriteLine(line) else null
            if (rewritten != null) {
                out.append(rewritten)
                changed = true
            } else {
                out.append(line)
            }
            // The newline is outside `affected`, and the loop must stop at
            // `lastLineEnd` rather than running to the end of the document — otherwise
            // a collapsed caret outdents the block below it and silently swallows
            // whatever followed.
            if (stop >= lastLineEnd || stop >= text.length) break
            out.append(text[stop])
            at = stop + 1
        }

        if (!changed) return null
        val affected = TextRange(firstLineStart, lastLineEnd)
        return Replace(
            range = affected,
            removed = text.substring(affected.start, affected.end),
            inserted = out.toString(),
        )
    }

    /**
     * Whether the line beginning at [lineStart] belongs to a list item, and is not
     * inside a fenced code block.
     *
     * The fence check is the whole reason this consults the tree. A code fence's
     * contents are opaque — never parsed as Markdown — so `    - not a list item`
     * inside a fence is code, and outdenting it would corrupt the document while
     * looking like it worked.
     */
    private fun isOutdentable(parsed: ParsedDocument, lineStart: Int): Boolean {
        for (block in parsed.allBlocks) {
            if (block is CodeFenceNode && lineStart > block.range.start && lineStart < block.range.end) {
                return false
            }
        }
        return innermostListItemAt(parsed, lineStart) != null
    }

    /**
     * The tightest list item containing [offset].
     *
     * Innermost rather than outermost, because a nested item's range is contained
     * in its parent's and outdenting has to move the item the caret is actually in.
     */
    private fun innermostListItemAt(parsed: ParsedDocument, offset: Int): ListItemNode? =
        parsed.allBlocks
            .filterIsInstance<ListItemNode>()
            .filter { offset >= it.range.start && offset <= it.range.end }
            .minByOrNull { it.range.length }

    /**
     * The outdented form of one line, or null when there is nothing to remove.
     *
     * Outdent removes exactly one level of indentation and **never touches the
     * marker**. That was the ticket's stated criterion — "a singly-nested item
     * becomes a plain paragraph" — and it is wrong, in the same way the criterion
     * proposing to renumber on outdent was wrong.
     *
     * A top-level item has no shallower level, so Shift-Tab on `- item` does
     * nothing at all. That is what every editor the design is copied from does, and
     * the alternative is worse than surprising: it gives the user a key that
     * silently deletes their bullets, with no undo affordance discoverable before
     * the fact. Turning a list item into a paragraph is Backspace-at-line-start,
     * which is visible and reversible. Stripping a marker here would also mean
     * rewriting `- [ ] done` into something that is no longer a task item.
     *
     * A line already at column 0 therefore returns null, and the composable reports
     * false so the platform can try its own path.
     */
    private fun rewriteLine(line: String): String? {
        val lead = line.takeWhile { it == ' ' || it == '\t' }
        val columns = indentColumns(lead)
        if (columns == 0) return null
        return consumeIndent(line, minOf(columns, INDENT_COLUMNS))
    }

    /** How many columns [whitespace] occupies, counting a tab to the next multiple of four. */
    private fun indentColumns(whitespace: String): Int {
        var column = 0
        for (c in whitespace) {
            column += if (c == '\t') 4 - (column % 4) else 1
        }
        return column
    }

    /**
     * [line] with [drop] columns of leading whitespace removed.
     *
     * Counting by column rather than by character is what makes a tab behave: a
     * line indented with one tab is at column 4, outdent drops 4, and the tab goes
     * with it. Trimming characters instead would leave the tab behind and the line
     * would still be indented.
     */
    private fun consumeIndent(line: String, drop: Int): String {
        var column = 0
        var i = 0
        while (i < line.length && column < drop) {
            val c = line[i]
            if (c != ' ' && c != '\t') break
            column += if (c == '\t') 4 - (column % 4) else 1
            i++
        }
        return line.substring(i)
    }

    private fun lineStartAt(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    /** The index of the newline ending the line containing [offset], or the text length. */
    private fun lineEndAt(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++
        return i
    }
}

/** The number of characters an inline range covers. */
private val InlineRange.length: Int get() = end - start
