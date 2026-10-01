package dev.fude.core

/**
 * Scroll position, as a value.
 *
 * Part of [EditorState] rather than a Compose `ScrollState`, because it is
 * observable document state and the state model must not depend on a UI toolkit.
 */
public data class ScrollState(
    val offset: Float = 0f,
    val extent: Float = 0f,
) {
    init {
        require(offset >= 0f) { "scroll offset must be non-negative, was $offset" }
    }

    /** Where the viewport starts, as a document offset. */
    public val firstVisibleOffset: Int get() = offset.toInt()

    /** Where the viewport ends, as a document offset. */
    public val lastVisibleOffset: Int get() = extent.toInt()

    public val isAtTop: Boolean get() = offset <= 0f

    public val isAtBottom: Boolean get() = extent <= 0f || offset >= extent

    public companion object {
        public val ZERO: ScrollState = ScrollState()
    }
}

/**
 * The whole editor state: text, selection, scroll. Nothing else.
 *
 * This is a value. Every edit produces a new one. That is what makes "rendering
 * is a pure function of state" checkable rather than aspirational — a composable
 * cannot mutate what it cannot reach, because there is no mutator.
 *
 * Deliberately absent: any document model, any notion of a vault or a file, any
 * knowledge of the host. If this type needs to know what a document *is*, the
 * boundary has leaked.
 */
public data class EditorState(
    val text: TextBuffer = TextBuffer.EMPTY,
    val selection: TextRange = TextRange.ZERO,
    val scroll: ScrollState = ScrollState.ZERO,
) {
    init {
        require(selection.end <= text.length) {
            "selection end ${selection.end} is outside a document of ${text.length} chars"
        }
    }

    /** The selected text, empty for a collapsed caret. */
    public val selectedText: String
        get() = if (selection.isCollapsed) "" else text.substring(selection.start, selection.end)

    public val isEmpty: Boolean get() = text.isBlank()

    /** Where the caret is. Meaningless unless the selection is collapsed. */
    public val caret: Int get() = if (selection.isCollapsed) selection.start else selection.start

    /**
     * The single mutation path.
     *
     * Typing, deleting, paste, an IME commit, a toggle-driven rewrite — all of
     * them come through here. A second path is how undo drifts out of sync, so
     * there is deliberately only one.
     *
     * An edit that would change nothing returns this same state, unchanged, which
     * is how "did the document actually change?" stays answerable.
     */
    public fun apply(edit: Edit): EditorState {
        if (edit.isNoOp(text)) return this
        val newText = TextBuffer.of(edit.applyTo(text.text))
        val newSelection = selection.mapThrough(edit).clampedTo(newText.length)
        return copy(text = newText, selection = newSelection)
    }

    /**
     * Applies a replacement of [range] with [text], mapping the caret through.
     *
     * The selection is mapped rather than reset, so a ranged selection survives an
     * edit that replaces part of it.
     */
    public fun replace(range: TextRange, text: String): EditorState =
        apply(replaceSelection(this.text, range, text))

    /** Inserts [inserted] at [offset], moving the caret after it. */
    public fun insertAt(offset: Int, inserted: String): EditorState =
        apply(Insert(offset.coerceIn(0, text.length), inserted))

    /**
     * Replaces the current selection with [inserted] and collapses the caret
     * after it.
     *
     * Typed input collapses rather than keeping a range, so this is separate
     * from [replace]: a paste over a selection should end with a caret, not with
     * a selection covering the pasted text.
     */
    public fun insert(inserted: String): EditorState {
        val edit = replaceSelection(text, selection, inserted)
        // The caret lands after whatever replaced the selection, so it is the
        // selection's end that has to be mapped, not its start.
        val caret = edit.mapOffset(selection.end, OffsetAffinity.DOWNSTREAM)
        val newText = TextBuffer.of(edit.applyTo(text.text))
        return copy(text = newText, selection = TextRange(caret.coerceIn(0, newText.length), caret.coerceIn(0, newText.length)))
    }

    /**
     * Deletes [range]. When the range is collapsed this deletes the grapheme
     * cluster before the caret rather than a single code unit, so a family emoji
     * or a combining accent does not leave half a character behind.
     */
    public fun deleteRange(range: TextRange): EditorState {
        if (!range.isCollapsed) return apply(replaceSelection(text, range, ""))
        val start = range.start.coerceIn(0, text.length)
        if (start == 0) return this
        val clusterStart = text.previousGraphemeBoundary(start)
        return apply(Delete(TextRange(clusterStart, start), text.substring(clusterStart, start)))
    }

    /** Deletes the selection, or the grapheme cluster before a collapsed caret. */
    public fun deleteBackward(): EditorState = deleteRange(selection)

    /** Deletes the selection, or the grapheme cluster after a collapsed caret. */
    public fun deleteForward(): EditorState =
        if (!selection.isCollapsed) {
            apply(replaceSelection(text, selection, ""))
        } else {
            val start = selection.start.coerceIn(0, text.length)
            if (start >= text.length) this
            else {
                val clusterEnd = text.nextGraphemeBoundary(start)
                apply(Delete(TextRange(start, clusterEnd), text.substring(start, clusterEnd)))
            }
        }

    /** Moves the caret, clamped into the document. */
    public fun moveCaretTo(offset: Int): EditorState =
        copy(selection = TextRange(offset.coerceIn(0, text.length), offset.coerceIn(0, text.length)))

    /** Selects [range], clamped into the document. */
    public fun select(range: TextRange): EditorState = selectRange(range.start, range.end)

    /**
     * Selects from [start] to [end], clamped and ordered.
     *
     * Takes two offsets rather than a [TextRange] because a reversed selection is
     * a normal thing for a host to produce from a drag, and [TextRange] rejects it
     * at construction. Normalising here is where the drag ends.
     */
    public fun selectRange(start: Int, end: Int): EditorState {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(0, text.length)
        return copy(selection = TextRange(minOf(from, to), maxOf(from, to)))
    }

    public fun withScroll(scroll: ScrollState): EditorState = copy(scroll = scroll)

    public companion object {
        public fun of(text: String, caret: Int = text.length): EditorState =
            EditorState(
                text = TextBuffer.of(text),
                selection = TextRange(caret.coerceIn(0, text.length), caret.coerceIn(0, text.length)),
            )
    }
}

/** Clamps this range into a document of [length] characters. */
public fun TextRange.clampedTo(length: Int): TextRange {
    val start = start.coerceIn(0, length)
    val end = end.coerceIn(0, length)
    return TextRange(minOf(start, end), maxOf(start, end))
}
