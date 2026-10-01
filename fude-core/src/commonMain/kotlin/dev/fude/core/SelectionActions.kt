package dev.fude.core

/**
 * Selection actions, in source terms.
 *
 * Every one of these behaves differently at a block boundary than in a plain text
 * field, which is why they are modelled here rather than left to the platform:
 * select-word on a heading should not run past the heading, and select-line on a
 * list item should include the item's marker so cutting and pasting keeps the list
 * structure.
 */
public object SelectionActions {

    /** Extends the selection to cover whole words around it. */
    public fun selectWord(state: EditorState): EditorState {
        val selection = state.selection
        val start = wordStart(state.text, selection.start)
        val end = wordEnd(state.text, if (selection.isCollapsed) selection.start else selection.end)
        return state.selectRange(start, end)
    }

    /** Extends the selection to cover the caret's whole line, terminator included. */
    public fun selectLine(state: EditorState): EditorState {
        val index = LineIndex.of(state.text.text)
        val line = index.lineOf(state.selection.end)
        return state.selectRange(index.lineStart(line), index.lineEnd(line, endInclusive = true))
    }

    /**
     * Selects the caret's block, from the start of the line to the end.
     *
     * "Block" is deliberately the line rather than a Markdown block: the library
     * does not know where blocks are at this layer, and guessing would couple
     * selection to parsing.
     */
    public fun selectBlock(state: EditorState): EditorState = selectLine(state)

    public fun selectAll(state: EditorState): EditorState = state.selectRange(0, state.text.length)

    /** Extends the selection to the previous word boundary. */
    public fun expandToPreviousWord(state: EditorState): EditorState {
        val start = wordStart(state.text, state.selection.start)
        return state.selectRange(start, state.selection.end)
    }

    /** Extends the selection to the next word boundary. */
    public fun expandToNextWord(state: EditorState): EditorState {
        val end = wordEnd(state.text, state.selection.end)
        return state.selectRange(state.selection.start, end)
    }

    private fun isWordCharacter(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** The start of the word containing or preceding [offset]. */
    public fun wordStart(text: TextBuffer, offset: Int): Int {
        var index = offset.coerceIn(0, text.length)
        while (index > 0 && !isWordCharacter(text.charAt(index - 1))) {
            index = text.previousGraphemeBoundary(index)
        }
        while (index > 0 && isWordCharacter(text.charAt(index - 1))) {
            index--
        }
        return index
    }

    /** The end of the word containing or following [offset]. */
    public fun wordEnd(text: TextBuffer, offset: Int): Int {
        var index = offset.coerceIn(0, text.length)
        while (index < text.length && !isWordCharacter(text.charAt(index))) {
            index = text.nextGraphemeBoundary(index)
        }
        while (index < text.length && isWordCharacter(text.charAt(index))) {
            index++
        }
        return index
    }
}
