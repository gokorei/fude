package dev.fude.input

import dev.fude.core.EditorState as CoreEditorState
import dev.fude.core.TextRange
import dev.fude.core.UndoStack

/**
 * IME composition, modelled so it produces exactly one edit.
 *
 * The problem this solves: an IME reports intermediate composition updates as the
 * user picks characters, then commits once. If each update became an edit, a
 * five-character Japanese word would be five undo steps, and the user would need
 * five undos to get back where they started.
 *
 * So composition updates are held in this object and only turned into an edit on
 * [commit]. Nothing reaches the document or the undo stack until then.
 */
public class CompositionSession(
    /** Where the composition begins in the document. */
    private val start: Int,
) {
    /** The composing text as last reported by the IME. */
    public var composing: String = ""
        private set

    /** The range the composition occupies, relative to the current buffer. */
    public val range: TextRange
        get() = TextRange(start, start + composing.length)

    public val isActive: Boolean get() = composing.isNotEmpty()

    /**
     * Records an intermediate composition update.
     *
     * Returns null rather than an edit: nothing has been committed, so there is
     * nothing for the document to do yet.
     */
    public fun update(text: String): CoreEditorState? = null.also { composing = text }

    /**
     * The single edit a committed composition produces, or null when empty.
     *
     * The range is against the document as it was when the session started, so the
     * commit replaces whatever the composition had been previewing.
     */
    public fun commit(): Pair<TextRange, String>? {
        if (!isActive) return null
        val committed = TextRange(start, start + composing.length) to composing
        composing = ""
        return committed
    }

    /** Discards the composition without committing it. */
    public fun cancel() {
        composing = ""
    }

    public companion object {
        /** A session covering [start]. */
        public fun at(start: Int): CompositionSession = CompositionSession(start)
    }
}

/**
 * Applies a committed composition to [state] and records exactly one undo step.
 *
 * Kept separate from [KeyHandler] because an IME commit arrives from the platform,
 * not from a key event, and conflating the two is how "five characters, five
 * undos" happens.
 */
public class ImeCommitter(private val undo: UndoStack) {

    /**
     * Commits [text] at [start], replacing the [previewLength] characters the
     * composition was previewing there.
     *
     * @param previewLength how many characters the IME had inserted before
     *   committing. Zero when the platform reports composition without inserting it.
     * @return the new state, or the same state when the commit was empty.
     */
    public fun commit(
        state: CoreEditorState,
        start: Int,
        text: String,
        previewLength: Int = 0,
    ): CoreEditorState {
        if (text.isEmpty()) return state
        val end = (start + previewLength).coerceIn(start, state.text.length)
        val before = state.selectRange(start, end)
        val edit = dev.fude.core.replaceSelection(before.text, TextRange(start, end), text)
        val after = before.apply(edit).moveCaretTo(start + text.length)
        // One edit, therefore one undo step, whatever the IME reported on the way.
        undo.record(before, after, edit)
        return after
    }

}
