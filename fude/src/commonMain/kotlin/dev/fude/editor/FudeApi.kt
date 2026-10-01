package dev.fude.editor

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Fude — a live-preview Markdown editor.
 *
 * The library edits a string it is given. Where that string comes from, where it
 * is stored, and what its lines *mean* are the host's business. That boundary is
 * the whole point, and it is what keeps the library free of any document model.
 *
 * Rendering is a pure function of [EditorState]. Nothing here rewrites the
 * user's Markdown: what they typed is the canonical value, and decoration is a
 * visual projection of it.
 */
public object Fude {
    public const val VERSION: String = "0.1.0-spike"
}

/** An inclusive-exclusive character range in the document. */
@Immutable
public data class InlineRange(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "start must be non-negative, was $start" }
        require(end >= start) { "end ($end) must not precede start ($start)" }
    }
}

/** Which side of the source/rendered toggle a block is currently showing. */
public enum class BlockView {
    /** The Markdown the user typed, undecorated. */
    SOURCE,

    /** The parsed result, decorated. */
    RENDERED,
}

/** The block kinds Fude's Markdown parser produces. */
public enum class BlockKind {
    PARAGRAPH,
    HEADING,
    LIST,
    LIST_ITEM,
    BLOCK_QUOTE,
    CODE_FENCE,
    TABLE,
    THEMATIC_BREAK,
    FRONTMATTER,
}

/**
 * A block of the document.
 *
 * [range] covers the block's source text, markers included. [view] is per block
 * because a per-block toggle is the model worth copying: toggling one list must
 * not toggle the rest of the note.
 */
@Immutable
public data class Block(
    val range: InlineRange,
    val view: BlockView,
    val kind: BlockKind?,
)

/** A styled run the renderer should draw, produced by a syntax extension. */
@Immutable
public data class Decoration(
    val range: InlineRange,
    val spanStyle: SpanStyle = SpanStyle(),
    val onClick: (() -> Unit)? = null,
)

/**
 * A host-supplied dialect extension.
 *
 * Fude ships Markdown. A host that needs `[[wikilinks]]` registers recognition
 * here rather than forking the parser, which is what keeps this a reusable
 * library rather than a Musubime component with a misleading name.
 */
@Stable
public interface SyntaxExtension {
    /** A stable identifier, used in diagnostics and tests. */
    public val id: String

    /**
     * Recognises this extension's syntax within [range].
     *
     * Called per frame, over the affected blocks only. Implementations must be
     * pure: the same range must yield the same result for the same text.
     */
    public fun recognise(text: CharSequence, range: InlineRange): List<InlineRange>

    /**
     * Turns a recognised range into something to draw.
     *
     * Returning `null` means "recognised, but nothing to draw", which is how a
     * host makes a marker subtle rather than invisible.
     */
    public fun render(range: InlineRange, text: CharSequence): Decoration?
}

/**
 * The document as plain text, plus where the caret is.
 *
 * Deliberately not a rich document model. Text plus selection is enough to make
 * rendering pure; anything richer would put a document model inside a Markdown
 * editor, which is the mistake this library exists to avoid.
 */
@Stable
public class EditorState(
    initialText: String = "",
    initialSelection: TextRange = TextRange.Zero,
) {
    public var text: String by mutableStateOf(initialText)
        private set

    public var selection: TextRange by mutableStateOf(initialSelection)
        private set

    /** The document split into blocks, each with its own source/rendered toggle. */
    public var blocks: List<Block> by mutableStateOf(emptyList())
        internal set

    /**
     * Applies an edit.
     *
     * @throws IllegalArgumentException if [newSelection] falls outside [newText],
     *   which would put the caret somewhere the user cannot see or fix.
     */
    public fun edit(newText: String, newSelection: TextRange = TextRange(newText.length)) {
        require(newSelection.start >= 0) { "selection start must be non-negative: $newSelection" }
        require(newSelection.end <= newText.length) {
            "selection end ${newSelection.end} is outside a document of length ${newText.length}"
        }
        text = newText
        selection = newSelection
    }
}

/** Immutable configuration for one editor instance. */
@Immutable
public data class EditorConfig(
    val textStyle: TextStyle = TextStyle.Default,
    val contentPadding: Dp = 8.dp,
    val maxLines: Int = Int.MAX_VALUE,
    val readOnly: Boolean = false,
    val placeholder: String? = null,
    val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
)

/**
 * A live-preview Markdown editor.
 *
 * @param state the document and the caret. Owned by the caller, because the
 *   caller's persistence decides when and whether it is saved.
 * @param syntaxExtensions the host's dialect, applied after built-in Markdown.
 * @param onChange fired after every edit with the new text.
 */
@Composable
public fun MarkdownEditor(
    state: EditorState,
    modifier: Modifier = Modifier,
    config: EditorConfig = EditorConfig(),
    syntaxExtensions: List<SyntaxExtension> = emptyList(),
    inputTransformation: InputTransformation? = null,
    onChange: (String) -> Unit = {},
) {
    // Sketch only. The spike established that Compose can decorate in place, and
    // that decoration must be recomputed on every frame from a fresh buffer.
    // Implementation lands in ticket 7W23JW59.
}
