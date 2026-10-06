package dev.fude.core

/**
 * Undo as operations, rather than as a stack.
 *
 * The same discipline a Compose `NavHost` applies to its back stack: the owner
 * holds the mutable history, and everything else is handed a handle rather than
 * the thing itself. Screens never touch a `NavController`'s entry list, and
 * nothing outside `MarkdownEditor` should be able to reach an [UndoStack]'s.
 *
 * The reason this exists rather than the stack being passed directly: [UndoStack]
 * carries `record`, `undo`, `redo` and `canUndo`, which is behaviour, alongside
 * `undoDepth`, `redoDepth` and `clear`, which are diagnostics. Passing the class
 * to get at four of its members would make an internal bookkeeping object part of
 * the public surface, and public API in this library is expensive to remove later.
 *
 * Two things this makes possible that were not before:
 *
 * - **A host can ask whether undo is available.** With the stack private to
 *   `MarkdownEditor`, `canUndo` was unreachable, so a host building an Edit menu
 *   had no way to enable or disable the item. Passing the controller exposes the
 *   question without exposing the history.
 * - **A host can share one history across editors,** or decorate it, or wrap it in
 *   something that counts keystrokes, without the library's concrete stack becoming
 *   part of its API. This was the original reason: three separate callers each needed
 *   a different subset of [UndoStack], and passing the class to reach them would have
 *   published all of it.
 *
 * Deliberately narrow. There is no `begin`/`end`, no batching, no coalescing
 * control: [UndoGrouping] owns when edits merge, and a caller that could also
 * influence that would have two answers to the same question. A no-op
 * implementation is a valid collaborator, which is what a read-only editor wants.
 */
interface UndoController {

    /** Whether there is anything to undo. For a host enabling a menu item. */
    val canUndo: Boolean

    /** Whether there is anything to redo. */
    val canRedo: Boolean

    /**
     * Records [edit] as applied to [stateBefore], producing [stateAfter].
     *
     * Coalescing is [UndoStack]'s decision, not the caller's: consecutive typing
     * merges by the rule in [UndoGrouping] whether or not the caller groups them.
     */
    fun record(stateBefore: EditorState, stateAfter: EditorState, edit: Edit)

    /**
     * Returns the state with the most recent group undone, or null when there is
     * nothing to undo.
     *
     * Null rather than the same state, so a caller cannot mistake "no history"
     * for "undone" — which is what lets `MarkdownEditor` consume the key while
     * changing nothing, instead of handing it to the platform whose own
     * snapshot undo would resurrect text this history says is undone.
     */
    fun undo(state: EditorState): EditorState?

    /** Returns the state with the most recent undo redone, or null when there is none. */
    fun redo(state: EditorState): EditorState?
}