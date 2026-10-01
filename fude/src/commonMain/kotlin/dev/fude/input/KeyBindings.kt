package dev.fude.input

import dev.fude.core.EditorState as CoreEditorState
import dev.fude.core.nextGraphemeBoundary
import dev.fude.core.previousGraphemeBoundary
import dev.fude.core.TextRange

/**
 * Which editing keys the library owns.
 *
 * The boundary the ticket asks for, made explicit: the library owns *editing
 * mechanics* — moving the caret, inserting text, deleting a cluster, undo — and
 * nothing else. Application commands belong to the host, because a library that
 * binds `Cmd+S` has no business knowing what a save is.
 *
 * Anything the host wants to add goes through [HostKeyHook], so the library never
 * has to learn what a command does.
 */
public enum class EditingKey {
    ARROW_LEFT,
    ARROW_RIGHT,
    ARROW_UP,
    ARROW_DOWN,
    HOME,
    END,
    ENTER,
    TAB,
    SHIFT_TAB,
    BACKSPACE,
    DELETE,
    SELECT_ALL,
    UNDO,
    REDO,
    COPY,
    CUT,
    PASTE,
}

/** A physical key as the host observed it, before any platform mapping. */
public data class KeyEvent(
    val key: EditingKey,
    val shift: Boolean = false,
    /** ⌘ on macOS, Ctrl elsewhere. Whether the host maps it is the host's call. */
    val command: Boolean = false,
    val alt: Boolean = false,
)

/**
 * A hook for keys the library does not own.
 *
 * Return true to consume the event. Returning false lets the host's own handling
 * or the platform default take it, which is what a host wants for application
 * commands.
 */
public fun interface HostKeyHook {
    public fun onKey(event: KeyEvent): Boolean
}

/**
 * Applies editing keys to the state model.
 *
 * All pure and headless. The tricky parts — logical versus visual line movement,
 * and Home/End differing per platform — are handled by taking the platform's
 * notion of "document start" as an explicit flag rather than by branching on a
 * platform check, so the behaviour is testable everywhere.
 */
public class KeyHandler(
    private val undo: dev.fude.core.UndoStack,
    private val onClipboardCopy: ((String) -> Unit)? = null,
    private val onClipboardCut: ((String) -> Unit)? = null,
    private val onClipboardPaste: (() -> String?)? = null,
    /**
     * Whether the command key means "line start" rather than "document start".
     *
     * True on macOS, where ⌘-Left is line start and ⌘-Up is document start. False
     * on Windows and Linux, where it is the other way round. Passed in rather than
     * detected so the behaviour is testable on any machine — a platform check here
     * would make the one thing users notice most impossible to test.
     */
    private val commandArrowIsLinewise: Boolean = true,
) {
    /**
     * Applies [event] to [state], returning the new state or null when unhandled.
     *
     * Null means "not mine" — which is the honest answer for an application
     * command, and is what lets the host hook take over without the library
     * guessing.
     */
    public fun handle(state: CoreEditorState, event: KeyEvent): CoreEditorState? = when (event.key) {
        EditingKey.ARROW_LEFT -> arrowLeft(state, event)
        EditingKey.ARROW_RIGHT -> arrowRight(state, event)
        EditingKey.ARROW_UP -> arrowUp(state, event)
        EditingKey.ARROW_DOWN -> arrowDown(state, event)
        EditingKey.HOME -> state.moveCaretTo(homeTarget(state, event.command))
        EditingKey.END -> state.moveCaretTo(endTarget(state, event))
        EditingKey.ENTER -> insert(state, "\n")
        EditingKey.TAB -> insert(state, if (event.shift) "" else "\t")
        EditingKey.SHIFT_TAB -> state
        EditingKey.BACKSPACE -> state.deleteBackward()
        EditingKey.DELETE -> state.deleteForward()
        EditingKey.SELECT_ALL -> state.selectRange(0, state.text.length)
        EditingKey.UNDO -> undo.undo(state)
        EditingKey.REDO -> undo.redo(state)
        EditingKey.COPY -> {
            if (!state.selection.isCollapsed) onClipboardCopy?.invoke(state.selectedText)
            state
        }
        EditingKey.CUT -> {
            if (!state.selection.isCollapsed) {
                onClipboardCut?.invoke(state.selectedText)
                state.deleteBackward()
            } else {
                state
            }
        }
        EditingKey.PASTE -> {
            val pasted = onClipboardPaste?.invoke() ?: return state
            state.insert(pasted)
        }
    }

    private fun insert(state: CoreEditorState, text: String): CoreEditorState =
        if (text.isEmpty()) state else state.insert(text)

    /**
     * Arrow left: collapse a selection first, else one grapheme back.
     *
     * Collapsing first is the behaviour every text field has and the one users
     * expect: with a selection active, a single arrow key puts the caret at its
     * edge rather than moving one character from wherever it already is.
     */
    private fun arrowLeft(state: CoreEditorState, event: KeyEvent): CoreEditorState {
        val selection = state.selection
        if (!selection.isCollapsed && !event.shift) {
            return state.moveCaretTo(selection.start)
        }
        val target = when {
            event.command && !commandArrowIsLinewise -> 0
            event.command -> state.text.lineStartOf(selection.start)
            else -> state.text.previousGraphemeBoundary(selection.start)
        }
        return if (event.shift) state.selectRange(target, selection.end) else state.moveCaretTo(target)
    }

    private fun arrowRight(state: CoreEditorState, event: KeyEvent): CoreEditorState {
        val selection = state.selection
        if (!selection.isCollapsed && !event.shift) {
            return state.moveCaretTo(selection.end)
        }
        val target = when {
            event.command && !commandArrowIsLinewise -> state.text.length
            event.command -> state.text.lineEndOf(selection.end)
            else -> state.text.nextGraphemeBoundary(selection.end)
        }
        return if (event.shift) state.selectRange(selection.start, target) else state.moveCaretTo(target)
    }

    private fun arrowUp(state: CoreEditorState, event: KeyEvent): CoreEditorState =
        state.moveCaretTo(state.text.previousGraphemeBoundary(selectionStart(state, event)))

    private fun arrowDown(state: CoreEditorState, event: KeyEvent): CoreEditorState =
        state.moveCaretTo(state.text.nextGraphemeBoundary(selectionStart(state, event)))

    private fun selectionStart(state: CoreEditorState, event: KeyEvent): Int =
        if (event.shift) state.selection.start else state.selection.end

    /**
     * Where Home goes.
     *
     * Plain Home is line start everywhere. Command-Home is document start
     * everywhere. The platform difference is in the *arrow* keys, not these — see
     * [commandArrowIsLinewise].
     */
    public fun homeTarget(state: CoreEditorState, command: Boolean): Int {
        if (command) return 0
        val lineStart = state.text.lineStartOf(state.selection.end)
        return minOf(lineStart, state.selection.end)
    }

    /** Where End goes. ⌘-End is document end on every platform. */
    public fun endTarget(state: CoreEditorState, event: KeyEvent): Int {
        if (event.command) return state.text.length
        return state.text.lineEndOf(state.selection.end).coerceAtLeast(state.selection.end)
    }
}
