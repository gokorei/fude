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
class UndoStack(
    private val grouping: UndoGrouping = UndoGrouping(),
    /** Deep enough for a normal editing session; older entries fall off the bottom. */
    private val limit: Int = 500,
    /** Total retained characters across entries; a single huge paste cannot pin memory. */
    private val maxRetainedChars: Int = 1_000_000,
) : UndoController {
    private data class Entry(
        val edits: List<Edit>,
        val selectionBefore: TextRange,
        val selectionAfter: TextRange,
    ) {
        /** Characters retained by this entry: removed + replacement text. */
        val retainedChars: Int get() = edits.sumOf { it.affectedRange.length + it.replacement.length }
    }

    private val undoEntries = mutableListOf<Entry>()
    private val redoEntries = mutableListOf<Entry>()

    /** Set while undo or redo is applied, so applying does not re-record itself. */
    private var applying = false

    override val canUndo: Boolean get() = undoEntries.isNotEmpty()
    override val canRedo: Boolean get() = redoEntries.isNotEmpty()

    /** Diagnostics, deliberately not on [UndoController] — nobody behaves differently on these. */
    val undoDepth: Int get() = undoEntries.size
    val redoDepth: Int get() = redoEntries.size
    /** Total retained characters; bounded alongside [undoDepth] so pastes cannot pin memory. */
    val retainedChars: Int get() = undoEntries.sumOf { it.retainedChars }

    /**
     * Records [edit] as applied to [stateBefore], producing [stateAfter].
     *
     * A fresh edit abandons the redo branch, which is what every editor does and
     * what users expect. Re-applying the identical edit to the same place after an
     * undo restores the branch instead, so a coalesced typing run behaves.
     */
    override fun record(stateBefore: EditorState, stateAfter: EditorState, edit: Edit) {
        if (!shouldRecord(stateBefore, stateAfter, edit)) return

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
        }
        evictBeyondBounds()

        // Refresh the grouping clock on *every* edit, not only when a new undo entry
        // opens. `idleTimeoutMillis` is a limit on the gap between two keystrokes, so
        // it has to be measured from the last one; refreshing only on a break measures
        // it from the start of the run instead, which splits any word that takes longer
        // to type than the limit — a run that never paused, broken mid-word.
        grouping.recordEditTime()
    }

    /** Whether [edit] belongs in history: not while applying, not a no-op, not selection-only. */
    private fun shouldRecord(stateBefore: EditorState, stateAfter: EditorState, edit: Edit): Boolean {
        if (applying) return false
        if (edit.isNoOp(stateBefore.text)) return false
        // A selection change is not an edit and does not belong in history.
        if (stateBefore.text == stateAfter.text) return false
        return true
    }

    /** Evicts oldest entries while over the count or retained-size bound. */
    private fun evictBeyondBounds() {
        while (undoEntries.size > limit || undoEntries.sumOf { it.retainedChars } > maxRetainedChars) {
            if (undoEntries.isEmpty()) break
            undoEntries.removeAt(0)
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
    override fun undo(state: EditorState): EditorState? {
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
    override fun redo(state: EditorState): EditorState? {
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

    /**
     * Drops all history.
     *
     * The grouping clock is refreshed so a cleared stack opens a fresh grouping
     * window. Today that is belt and braces — with both entry lists empty the next
     * edit has nothing to merge with anyway — but it keeps the invariant local:
     * `lastEditAt` never describes an edit that is no longer in [undoEntries], which
     * is what makes recording the time on *every* edit safe.
     */
    fun clear() {
        undoEntries.clear()
        redoEntries.clear()
        grouping.recordEditTime()
    }
}
