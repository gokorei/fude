package dev.fude.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fude.core.EditorState as CoreEditorState
import dev.fude.core.InlineRange
import dev.fude.core.TextRange as CoreTextRange
import dev.fude.core.replaceSelection
import dev.fude.syntax.SyntaxExtension

/**
 * Fude — a live-preview Markdown editor.
 *
 * The library edits a string it is given. Where that string comes from, where it
 * is stored, and what its lines *mean* are the host's business. That boundary is
 * the point, and it is what keeps Fude free of any document model.
 */
public object Fude {
    public const val VERSION: String = "0.1.0"
}

/** A styled run, and optionally what happens when it is clicked. */
@Immutable
public data class Decoration(
    val range: InlineRange,
    val spanStyle: SpanStyle = SpanStyle(),
    val onClick: (() -> Unit)? = null,
)

/**
 * The document as plain text, plus where the caret is.
 *
 * A thin observable shell over the pure [CoreEditorState] rather than a
 * reimplementation of it. There is exactly one implementation of "what an edit
 * does to a selection"; a second one is a second answer, and the two eventually
 * disagree — usually in caret mapping, which is where the disagreement is hardest
 * to see.
 *
 * [text] is the canonical value. Decoration is a projection of it and never
 * rewrites it.
 */
@Stable
public class EditorState private constructor(
    initial: CoreEditorState,
) {
    private val core = mutableStateOf(initial)

    /** The pure state model, for hosts that want to reason about it directly. */
    public val model: CoreEditorState get() = core.value

    public val text: String get() = core.value.text.text

    public val selection: CoreTextRange get() = core.value.selection

    public fun applyEdit(newText: String, newSelection: CoreTextRange) {
        // Rebuild through the pure model so selection mapping stays in one place.
        val rebuilt = core.value.replace(core.value.selection, "")
        core.value = rebuilt.selectRange(newSelection.start, newSelection.end)
            .let { CoreEditorState(dev.fude.core.TextBuffer.of(newText), it.selection, it.scroll) }
    }

    public fun moveCaretTo(offset: Int) {
        core.value = core.value.moveCaretTo(offset)
    }

    public fun selectRange(start: Int, end: Int) {
        core.value = core.value.selectRange(start, end)
    }

    public companion object {
        public fun of(text: String, caret: Int = text.length): EditorState =
            EditorState(CoreEditorState.of(text, caret))
    }
}

/** Immutable configuration for one editor instance. */
@Immutable
public data class EditorConfig(
    val textStyle: TextStyle = TextStyle.Default,
    val contentPadding: Dp = 12.dp,
    val maxLines: Int = Int.MAX_VALUE,
    val readOnly: Boolean = false,
    val placeholder: String? = null,
)

/**
 * A live-preview Markdown editor.
 *
 * Rendering is a pure function of [state]: nothing here rewrites the user's
 * Markdown, and toggling a block is a view change that leaves the text alone.
 *
 * @param state the document and the caret. Owned by the caller, because the
 *   caller's persistence decides when and whether it is saved.
 * @param syntaxExtensions the host's dialect, applied after built-in Markdown.
 * @param onChange fired after every edit with the new text.
 * @param onDecorationClick called when a decorated range is clicked. Resolution
 *   is the host's: the library has no idea what a mention or a wikilink means.
 */
@Composable
public fun MarkdownEditor(
    state: EditorState,
    modifier: Modifier = Modifier,
    config: EditorConfig = EditorConfig(),
    syntaxExtensions: List<SyntaxExtension> = emptyList(),
    onChange: (String) -> Unit = {},
    onDecorationClick: ((Decoration) -> Unit)? = null,
) {
    // The renderer lands in the next commit; the state, parser, extension point,
    // toggle and layout layers are in place and tested headlessly.
}
