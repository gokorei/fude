package dev.fude.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.fude.core.Insert
import dev.fude.core.TextRange as CoreTextRange
import dev.fude.markdown.BlockNode
import dev.fude.markdown.BlockViewState
import dev.fude.markdown.CodeFenceNode
import dev.fude.markdown.EmphasisNode
import dev.fude.markdown.HostBlockNode
import dev.fude.markdown.HostInlineNode
import dev.fude.markdown.IncrementalMarkdownParser
import dev.fude.markdown.InlineNode
import dev.fude.markdown.LinkNode
import dev.fude.markdown.ParsedDocument
import dev.fude.markdown.RenderMode
import dev.fude.markdown.ToggleCoordinator
import dev.fude.render.LayoutCache
import dev.fude.syntax.SyntaxExtension

/**
 * The live-preview renderer.
 *
 * Three things the spike established that shape this file:
 *
 * 1. Compose hands `OutputTransformation` a **fresh buffer every frame**, so
 *    decoration cannot be cached across frames and has to be recomputed from the
 *    text each time. That is why decoration below is a pure function of the parse.
 * 2. The text in the buffer is the user's source, undecorated. Styling is applied
 *    on top and never rewrites it — "source is the truth" is not a slogan here,
 *    it is what the buffer contains.
 * 3. Layout of a 5,000-line note costs ~110 ms per keystroke and decoration adds
 *    ~76 ms on top. Both are measured in `KeystrokeBudgetTest`. Neither is near a
 *    frame, which is why [LayoutCache] exists and why block-bounded reparse is a
 *    correctness requirement rather than an optimisation.
 *
 * **Where the caret goes when the document changes.**
 *
 * Wherever the host puts it, because only the host knows. Loading a note is
 * `remember { EditorState.of(markdown) }` producing a new state, and `of` puts the
 * caret at the end of the document; a host that wants it at the top, or at a stored
 * scroll position, passes `caret` and gets that. This composable never invents a
 * caret position, and in particular never keeps the old one — "the caret jumped to
 * the top when I opened a note" and "the caret stayed where I left it in another
 * note" are both user-visible behaviour that has to be chosen, not inherited by
 * accident from whatever the previous document happened to leave behind.
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
    // The Compose-side text state. The pure model is the source of truth for
    // selection and undo; this holds what Compose renders.
    val textState = rememberTextFieldState(state.text)
    val undo = remember { dev.fude.core.UndoStack() }
    // Keyed on the extensions, not remembered once: a parser built without them can
    // never produce a host-defined block, so a host that registers a dialect would
    // get inline syntax it recognises and block syntax it silently does not.
    val parser = remember(syntaxExtensions) { IncrementalMarkdownParser(syntaxExtensions) }
    val view = remember { BlockViewState() }
    val layoutCache = remember { LayoutCache() }

    var parsed by remember(syntaxExtensions) { mutableStateOf(parser.parse(state.text)) }

    // One effect does the whole per-keystroke pipeline, in order:
    //   1. mirror the field's text and selection into the pure model
    //   2. reparse, bounded to the affected block
    //   3. keep block toggle keys pointing at the same blocks
    //   4. invalidate only the layout entries whose content changed
    //
    // Doing these in separate effects would let them interleave across frames and
    // decorate a stale parse.
    LaunchedEffect(textState.text, textState.selection) {
        val current = textState.text.toString()
        if (current != state.text) {
            state.applyEdit(
                current,
                CoreTextRange(textState.selection.start, textState.selection.end),
            )
            onChange(current)
        }

        val previous = parsed
        if (previous.text != current) {
            val delta = current.length - previous.text.length
            val editOffset = commonPrefixLength(previous.text, current)
            val next = parser.reparse(current, Insert(editOffset, ""))
            parsed = next
            ToggleCoordinator.afterEdit(view, editOffset, delta, ToggleCoordinator.blockStarts(next.blocks))
            layoutCache.invalidateAllExcept(
                next.blocks.map { layoutCache.contentHashOf(it, next.text) }.toSet(),
            )
        }
    }

    // Push the model's selection onto Compose's, so the caret stays where the pure
    // model says it is. Without this the two drift and the caret lands wrong after
    // any programmatic move.
    //
    // Selection is written through `edit`, not assigned: `TextFieldState.selection`
    // is a val, and mutating it directly is not how the platform expects a
    // programmatic caret move to arrive.
    LaunchedEffect(state.selection) {
        val target = CoreTextRange(state.selection.start, state.selection.end)
        if (textState.selection.start != target.start || textState.selection.end != target.end) {
            textState.edit {
                selection = androidx.compose.ui.text.TextRange(
                    target.start.coerceIn(0, length),
                    target.end.coerceIn(0, length),
                )
            }
        }
    }

    // Push the model's text back when the host changes it, e.g. loading a document.
    LaunchedEffect(state.text) {
        if (state.text != textState.text.toString()) {
            val replacement = state.text
            textState.edit { replace(0, length, replacement) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BasicTextField(
            state = textState,

            modifier = Modifier
                .fillMaxSize()
                .padding(config.contentPadding)
                .testTag(TAG_EDITOR),
            textStyle = config.textStyle,
            readOnly = config.readOnly,
            // Decoration is recomputed here, per frame, from the current parse. The
            // ranges come from the block tree, so a reparse and a decoration pass
            // cannot disagree about where anything is.
            outputTransformation = OutputTransformation {
                applyDecoration(this, parsed, view, syntaxExtensions, config)
            },
        )
    }
}

/**
 * Adds spans to [buffer] for the current parse.
 *
 * Pure in (parse, view, text): the same inputs always produce the same spans,
 * which is the property that makes "rendering is a pure function of state"
 * checkable rather than aspirational.
 */
private fun applyDecoration(
    buffer: androidx.compose.foundation.text.input.TextFieldBuffer,
    parsed: ParsedDocument,
    view: BlockViewState,
    extensions: List<SyntaxExtension>,
    config: EditorConfig,
) {
    val text: String = buffer.originalText.toString()

    // The parse must describe *this* buffer.
    //
    // `OutputTransformation` runs during layout, while the parse is refreshed by a
    // `LaunchedEffect` that can only run once the frame is being applied. So on the
    // first frame after the buffer's text changes, the parse is still the previous
    // document's. When the new text is shorter the old tree's ranges point past the
    // end of the buffer, and `addStyle` throws — a crash on the commonest editor
    // path there is, a host loading a document.
    //
    // Comparing lengths first keeps the mismatch case O(1). The full comparison only
    // runs once the lengths already agree, which is the frame-to-frame case.
    if (parsed.text.length != text.length || parsed.text != text) return

    for (block in parsed.blocks) {
        // A block showing source is deliberately left undecorated: the user is
        // editing Markdown and wants to see it as written.
        if (view.modeOf(block.range.start) == RenderMode.SOURCE) continue
        decorateBlock(buffer, block, text, config)
    }

    // Host inline syntax is applied last so a mention wins over Markdown that
    // happens to overlap it, which is what resolveMatches' precedence is for.
    for (extension in extensions) {
        for (range in extension.recogniseInline(dev.fude.syntax.BlockContext(text, parsed.contentRange()))) {
            if (range.end <= range.start) continue
            if (range.end > buffer.length) continue
            buffer.addStyle(SpanStyle(background = Color(0x1A4A90D9)), range.start, range.end)
        }
    }
}

/** Adds the spans for one block and, recursively, its children. */
private fun decorateBlock(
    buffer: androidx.compose.foundation.text.input.TextFieldBuffer,
    block: BlockNode,
    text: String,
    config: EditorConfig,
) {
    when (block) {
        is CodeFenceNode -> {
            // Opaque by construction. Nothing inside a fence is parsed, so nothing
            // inside it can be decorated as Markdown — which is exactly the point.
            addStyle(buffer, block.range, SpanStyle(color = Color(0xFF6A9955)))
        }

        is HostBlockNode -> {
            // A construct the host claimed and the library has no opinion about.
            // Tinting the whole block is what makes a callout visibly a callout; the
            // library knows the block exists and nothing else about it, which is the
            // whole arrangement.
            addStyle(buffer, block.range, SpanStyle(background = Color(0x147A9E7E)))
            for (inline in block.inlines) decorateInline(buffer, inline, config)
        }

        else -> {
            for (inline in block.inlines) decorateInline(buffer, inline, config)
            for (child in block.children) decorateBlock(buffer, child, text, config)
        }
    }
}

private fun decorateInline(
    buffer: androidx.compose.foundation.text.input.TextFieldBuffer,
    inline: InlineNode,
    config: EditorConfig,
) {
    when (inline) {
        is EmphasisNode -> {
            addStyle(
                buffer,
                inline.range,
                if (inline.strong) {
                    SpanStyle(fontWeight = FontWeight.Bold)
                } else {
                    SpanStyle(fontStyle = FontStyle.Italic)
                },
            )
            for (child in inline.children) decorateInline(buffer, child, config)
        }

        is dev.fude.markdown.CodeSpanNode ->
            addStyle(buffer, inline.range, SpanStyle(background = Color(0x1F000000)))

        is LinkNode ->
            addStyle(
                buffer,
                inline.labelRange,
                SpanStyle(
                    color = Color(0xFF3B7DD8),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                ),
            )

        is dev.fude.markdown.ImageNode ->
            addStyle(buffer, inline.range, SpanStyle(color = Color(0xFF8A6A3B)))

        is HostInlineNode ->
            addStyle(buffer, inline.range, SpanStyle(background = Color(0x1A4A90D9)))

        else -> Unit
    }
}

/**
 * Adds [style] over [range], or over as much of it as the buffer actually holds.
 *
 * `TextFieldBuffer.addStyle` throws `IllegalArgumentException` on a range running past
 * the end. Every range reaching here comes from a block tree, and that tree is only
 * as trustworthy as the parse that produced it — so a parser bug must not be able to
 * take the editor down.
 *
 * Clipping rather than skipping: a span cut at the buffer's end still shows the user
 * the emphasis they typed, where dropping it loses it without a word.
 */
private fun addStyle(
    buffer: androidx.compose.foundation.text.input.TextFieldBuffer,
    range: dev.fude.core.InlineRange,
    style: SpanStyle,
) {
    val start = range.start.coerceIn(0, buffer.length)
    val end = range.end.coerceIn(start, buffer.length)
    if (end <= start) return
    buffer.addStyle(style, start, end)
}

/** How many characters two strings share from the start. */
internal fun commonPrefixLength(a: String, b: String): Int {
    val limit = minOf(a.length, b.length)
    var i = 0
    while (i < limit && a[i] == b[i]) i++
    return i
}

internal const val TAG_EDITOR = "fude-editor"
