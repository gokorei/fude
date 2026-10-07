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
import dev.fude.core.ScrollState
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
object Fude {
    public const val VERSION: String = "0.1.0"
}

/** A styled run, and optionally what happens when it is clicked. */
@Immutable
data class Decoration(
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
 *
 * [model] also carries a [ScrollState], and this shell now preserves it across
 * [applyEdit] — see that method for why the document-swap case is a parameter
 * rather than the default.
 */
@Stable
class EditorState private constructor(
    initial: CoreEditorState,
) {
    private val core = mutableStateOf(initial)

    /** The pure state model, for hosts that want to reason about it directly. */
    val model: CoreEditorState get() = core.value

    val text: String get() = core.value.text.text

    val selection: CoreTextRange get() = core.value.selection

    /**
     * Replaces the document with [newText] and the caret with [newSelection].
     *
     * This is how the platform's text field reports what the user did: Compose
     * owns the buffer during a gesture and reports the result, so the library
     * applies the whole change rather than reconstructing individual edits from
     * keystrokes. The trade is that undo groups by *change* rather than by
     * keystroke, so a platform that reports a whole IME word as one buffer change
     * already produces one undo step — which is the outcome an `ImeCommitter` was
     * written to force, obtained here for free.
     *
     * **An out-of-range selection is clamped, not rejected.**
     *
     * A selection belonging to the *previous* document is the normal case here
     * rather than a caller error: the field reports the selection it had when the
     * text was swapped underneath it, so its offsets are routinely past the end of
     * the text now in hand. Throwing there would take the editor down on the act of
     * loading a note, so both ends are coerced into the new text and the document
     * is always left in a usable state.
     *
     * This matches [moveCaretTo] and [selectRange], which have always clamped. It
     * is the throw-on-garbage argument that loses, and deliberately so: a caret the
     * user cannot see is a bug report, and a crash on a core path is worse than a
     * caret at the end of the document.
     *
     * **Scroll is carried across, and that is a decision rather than an omission.**
     *
     * This used to rebuild the state through [CoreEditorState.of], which defaults
     * scroll to zero, so every keystroke threw the viewport back to the top of the
     * document. The pure model never had that bug — [CoreEditorState.apply] copies,
     * and therefore preserves — and the loss was entirely in this shell. Scroll is
     * now carried by default, which is the right answer for an edit: the document
     * moved under the caret, not the caret's place in the viewport.
     *
     * The case that should *not* preserve it is a host swapping documents, and it is
     * the reason [scroll] is a parameter rather than a private detail. Loading a
     * different note should start at the top, and it used to — by accident, because
     * everything reset. A host that swaps documents through this method now has to
     * say so:
     *
     * ```
     * state.applyEdit(otherNote, CoreTextRange(0, 0), scroll = ScrollState.ZERO)
     * ```
     *
     * which is the same discipline [MarkdownEditor]'s KDoc applies to the caret:
     * "the caret jumped to the top when I opened a note" and "the caret stayed where
     * I left it in another note" are both user-visible behaviour that has to be
     * chosen rather than inherited by accident. Scroll deserves the same treatment,
     * and now gets it.
     *
     * Note what this does *not* do: it does not scroll the screen. Scroll position
     * lives inside `BasicTextField`, which owns it and does not surface a round-trip,
     * so carrying it here keeps the model's value honest without pretending the
     * composable can drive the viewport. A host that needs to restore a position on
     * load passes it through here as well.
     */
    fun applyEdit(
        newText: String,
        newSelection: CoreTextRange,
        scroll: ScrollState = core.value.scroll,
    ) {
        val candidate = CoreEditorState
            .of(newText, newSelection.start.coerceIn(0, newText.length))
            .withScroll(scroll)
        core.value = candidate.selectRange(newSelection.start, newSelection.end)
    }

    fun moveCaretTo(offset: Int) {
        core.value = core.value.moveCaretTo(offset)
    }

    fun selectRange(start: Int, end: Int) {
        core.value = core.value.selectRange(start, end)
    }

    companion object {
        fun of(text: String, caret: Int = text.length): EditorState =
            EditorState(CoreEditorState.of(text, caret))
    }
}

/**
 * Immutable configuration for one editor instance.
 *
 * Every field here is read by [MarkdownEditor]. That is not a coincidence and it
 * is not automatic: a `public val` on a config class that no composable reads is
 * invisible to the compiler, to the IDE and to every reviewer, because nothing
 * about it is an error. Two of these — `maxLines` and `placeholder` — were exactly
 * that for a while, declared and documented and settable, and silently doing
 * nothing. See the README's *Known gaps* for why the build does not catch it yet.
 */
@Immutable
data class EditorConfig(
    /** The style the document is drawn in. */
    val textStyle: TextStyle = TextStyle.Default,

    /** Space between the field's edge and its text, applied on all four sides. */
    val contentPadding: Dp = 12.dp,

    /**
     * How many lines of the document are shown at once.
     *
     * **This is a viewport height, not a limit on content.** `BasicTextField`
     * scrolls, so a document longer than `maxLines` is fully present, fully
     * editable and fully rendered — you see the first `maxLines` lines and scroll
     * for the rest. Nothing is truncated, and `maxLines` is not a reading-width
     * constraint either: setting it does not stop long lines wrapping.
     *
     * It also changes how the field is *sized*. Left unbounded, the field fills its
     * container; set to a number, it is sized to its content up to that many lines,
     * because a field told to fill its container cannot also be told to stop at ten.
     * That is the only reason the two are not in conflict.
     *
     * The name invites the opposite reading, which is why this says so at length.
     * The default is [Int.MAX_VALUE], which means unbounded.
     */
    val maxLines: Int = Int.MAX_VALUE,

    /** Whether the document can be edited. Rendered either way. */
    val readOnly: Boolean = false,

    /**
     * Text shown while the document is empty, in place of it.
     *
     * Invisible the moment there is any text at all, including a single space,
     * because the condition is emptiness rather than emptiness-looking-to-the-user:
     * a document the user has deliberately blanked is blanked, and replacing it
     * with a hint would misrepresent what they did.
     */
    val placeholder: String? = null,

    /**
     * Whether the library styles Markdown at all.
     *
     * On by default. Turned off, the document is drawn as plain text in
     * [textStyle] — no heading sizes, no emphasis, no code or link styling —
     * which is the mode for editing Markdown as text rather than reading it
     * rendered. Per-block source/rendered toggles are ignored while it is off
     * but left untouched, so flipping it back restores exactly what the host
     * had; like a toggle, it never marks the document modified.
     *
     * Host [Decoration]s are unaffected: they are the host's own per-frame
     * answer about its own syntax, applied last, and they keep their click
     * behavior.
     */
    val showMarkdownDecorations: Boolean = true,
)
