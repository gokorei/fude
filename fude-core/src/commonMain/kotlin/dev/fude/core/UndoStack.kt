package dev.fude.core

/**
 * Undo history over [Edit] values.
 *
 * Two things this gets right that editors usually get wrong.
 *
 * **It stores edits, not snapshots.** A snapshot of a 5,000-line note on every
 * keystroke is an O(n) copy per character typed.
 *
 * **It coalesces.** Typing a word is one undo step, not one per character. The
 * rule lives in [UndoGrouping] and is tested directly, because it is the part
 * users notice.
 */
public class UndoStack(
    private val grouping: UndoGrouping = UndoGrouping(),
    /** Deep enough for a normal editing session; older entries fall off the bottom. */
    private val limit: Int = 500,
) {
    private data class Entry(
        val edits: List<Edit>,
        val selectionBefore: TextRange,
        val selectionAfter: TextRange,
    )

    private val undoEntries = mutableListOf<Entry>()
    private val redoEntries = mutableListOf<Entry>()

    /** Set while undo or redo is applied, so applying does not re-record itself. */
    private var applying = false

    public val canUndo: Boolean get() = undoEntries.isNotEmpty()
    public val canRedo: Boolean get() = redoEntries.isNotEmpty()
    public val undoDepth: Int get() = undoEntries.size
    public val redoDepth: Int get() = redoEntries.size

    /**
     * Records [edit] as applied to [stateBefore], producing [stateAfter].
     *
     * A fresh edit abandons the redo branch, which is what every editor does and
     * what users expect. Re-applying the identical edit to the same place after an
     * undo restores the branch instead, so a coalesced typing run behaves.
     */
    public fun record(stateBefore: EditorState, stateAfter: EditorState, edit: Edit) {
        if (applying) return
        if (edit.isNoOp(stateBefore.text)) return

        // A selection change is not an edit and does not belong in history.
        if (stateBefore.text == stateAfter.text) return

        handleRedoBranch(edit)

        val previous = undoEntries.lastOrNull()
        val mergeable = previous != null && grouping.shouldMerge(
            previous = previous.edits.last(),
            incoming = edit,
            selectionAfterPrevious = previous.selectionAfter,
            selectionBeforeIncoming = stateBefore.selection,
        )

        if (mergeable) {
            undoEntries[undoEntries.lastIndex] = previous.copy(
                edits = previous.edits + edit,
                selectionAfter = stateAfter.selection,
            )
        } else {
            undoEntries += Entry(listOf(edit), stateBefore.selection, stateAfter.selection)
            grouping.recordEditTime()
            if (undoEntries.size > limit) undoEntries.removeAt(0)
        }
    }

    private fun handleRedoBranch(edit: Edit) {
        val lastRedo = redoEntries.lastOrNull() ?: return
        if (lastRedo.edits.last() == edit) {
            // Redo of exactly this edit: pop the branch rather than discarding it.
            redoEntries.removeAt(redoEntries.lastIndex)
        } else {
            redoEntries.clear()
        }
    }

    /**
     * Undoes the most recent entry, returning the state to restore.
     *
     * Returns `null` when there is nothing to undo, rather than quietly returning
     * the same state, so a caller cannot mistake "no history" for "undone".
     */
    public fun undo(state: EditorState): EditorState? {
        val entry = undoEntries.removeLastOrNull() ?: return null
        applying = true
        try {
            // Undo in reverse: the last edit's inverse must be applied first.
            var result = state
            for (edit in entry.edits.asReversed()) {
                result = result.apply(edit.inverse())
            }
            val restored = result.select(entry.selectionBefore)
            redoEntries += entry
            return restored
        } finally {
            applying = false
        }
    }

    /** Redoes the most recently undone entry, or returns `null` if there is none. */
    public fun redo(state: EditorState): EditorState? {
        val entry = redoEntries.removeLastOrNull() ?: return null
        applying = true
        try {
            var result = state
            for (edit in entry.edits) {
                result = result.apply(edit)
            }
            undoEntries += entry
            return result.select(entry.selectionAfter)
        } finally {
            applying = false
        }
    }

    public fun clear() {
        undoEntries.clear()
        redoEntries.clear()
        grouping.recordEditTime()
    }
}
