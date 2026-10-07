package dev.fude.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.fude.core.ScrollState
import dev.fude.core.TextRange as CoreTextRange
import dev.fude.editor.EditorConfig
import dev.fude.editor.EditorState
import dev.fude.editor.MarkdownEditor
import dev.fude.markdown.BlockViewState
import kotlinx.browser.document
import kotlin.js.JsExport

/**
 * The JavaScript boundary of the editor.
 *
 * Plain top-level functions, not an exported object: `@JsExport` on this
 * toolchain applies to functions, so the boundary is shaped as functions
 * anyway — which suits it, since an opaque numeric handle crosses
 * `postMessage` and JSON untouched while a class identity would not. Every
 * value that crosses is a string, a boolean, or an int:
 *
 * ```
 * const editor = fudeMount("editor-root", "# Hello\n", false, (text) => save(text));
 * fudeSetText(editor, "# Hello\nmore\n");
 * fudeSetPlainMode(editor, true);
 * ```
 *
 * The bundle loads asynchronously: `window["fude-web"]` is a promise that
 * resolves to these functions once the runtime is up. A host page awaits it
 * rather than calling on load — awaiting is also what orders the host after
 * the export bindings, which are assigned as the last step of initialisation:
 *
 * ```
 * window["fude-web"].then((Fude) => {
 *   const editor = Fude.fudeMount("editor-root", markdown, false, save);
 * });
 * ```
 *
 * The two writers are the two cases the library distinguishes everywhere, and
 * they are separate functions here for the same reason: an edit and a document
 * swap both arrive as "the text changed", and only the host knows which one it
 * meant.
 *
 * **Mount once per page load.** [ComposeViewport] returns nothing to dispose,
 * so a mounted editor lives until the page goes away. A host that re-renders
 * its DOM around the editor (LiveView patching, client-side navigation) must
 * leave the container element alone — `phx-update="ignore"` on the container
 * is the LiveView form of that — or every re-mount leaks the previous
 * composition. There is deliberately no unmount: one that removed the canvas
 * without disposing the composition would look like cleanup while leaking the
 * recomposer, which is worse than an honest absence.
 *
 * Single-threaded, like the page: all of this runs on the browser's main
 * thread, and `onChange` is called synchronously from the frame that applied
 * the edit.
 */
@JsExport
@OptIn(ExperimentalComposeUiApi::class)
fun fudeMount(
    rootId: String,
    markdown: String,
    plain: Boolean,
    onChange: ((String) -> Unit)?,
): Int {
    if (document.getElementById(rootId) == null) {
        throw IllegalArgumentException("fudeMount: no element with id '$rootId'")
    }
    val state = EditorState.of(markdown)
    val view = BlockViewState()
    val plainMode = mutableStateOf(plain)
    ComposeViewport(rootId) {
        MarkdownEditor(
            state = state,
            view = view,
            // Inverted on purpose: `plain` hides styling, the flag shows it.
            config = EditorConfig(showMarkdownDecorations = !plainMode.value),
            onChange = { text -> onChange?.invoke(text) },
        )
    }
    val id = nextEditorId++
    editors[id] = EditorSession(state, view, plainMode)
    return id
}

/** The current document, exactly as the user has it. */
@JsExport
fun fudeGetText(id: Int): String = session(id).state.text

/**
 * Replaces the document as an *edit*: per-block toggles follow their blocks
 * through the change, and the caret stays where it was (clamped into the new
 * text when the old position no longer exists).
 */
@JsExport
fun fudeSetText(id: Int, text: String) {
    val state = session(id).state
    state.applyEdit(text, state.selection)
}

/**
 * Replaces the document as a *swap* — opening another note. Toggles are block
 * offsets, so every one of them is stale against a different document: they
 * are cleared, the caret goes to the end, and the scroll goes to the top. A
 * host that edits through this function instead loses nothing visibly and
 * keeps toggles that name blocks that no longer exist.
 */
@JsExport
fun fudeLoadDocument(id: Int, text: String) {
    val session = session(id)
    session.view.clear()
    session.state.applyEdit(text, CoreTextRange(text.length, text.length), ScrollState.ZERO)
}

/** Turns raw-Markdown mode on or off, without touching the document. */
@JsExport
fun fudeSetPlainMode(id: Int, plain: Boolean) {
    session(id).plainMode.value = plain
}

/** Everything one mounted editor owns, keyed by the handle JS holds. */
private class EditorSession(
    val state: EditorState,
    val view: BlockViewState,
    val plainMode: MutableState<Boolean>,
)

private val editors = mutableMapOf<Int, EditorSession>()

private var nextEditorId = 1

private fun session(id: Int): EditorSession =
    editors[id] ?: throw IllegalArgumentException("unknown editor id: $id")
