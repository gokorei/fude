package dev.fude.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import dev.fude.core.TextRange as CoreTextRange
import dev.fude.markdown.BlockNode
import dev.fude.markdown.BlockQuoteNode
import dev.fude.markdown.BlockViewState
import dev.fude.markdown.CodeFenceNode
import dev.fude.markdown.EmphasisNode
import dev.fude.markdown.HeadingNode
import dev.fude.markdown.HostBlockNode
import dev.fude.markdown.HostInlineNode
import dev.fude.markdown.IncrementalMarkdownParser
import dev.fude.markdown.InlineNode
import dev.fude.markdown.LinkNode
import dev.fude.markdown.ListItemNode
import dev.fude.markdown.ParsedDocument
import dev.fude.markdown.RenderMode
import dev.fude.markdown.TableNode
import dev.fude.markdown.ThematicBreakNode
import dev.fude.markdown.ToggleCoordinator
import dev.fude.syntax.SyntaxExtension

/**
 * The live-preview renderer.
 *
 * Three things the spike established that shape this file:
 *
 * 1. Compose hands `OutputTransformation` a **fresh buffer every frame**, so
 *    decoration cannot be cached across frames and has to be recomputed from the
 *    text each time. That is why decoration below is a pure function of the parse.
 *    Note what that does *not* make per-frame: recognising host syntax. A
 *    `SyntaxExtension` is asked once per block per **reparse**, not once per frame,
 *    because recognition happens in the parser and the parse is block-bounded. A
 *    frame re-reads the parse's answer; it does not re-ask the question.
 * 2. The text in the buffer is the user's source, undecorated. Styling is applied
 *    on top and never rewrites it — "source is the truth" is not a slogan here,
 *    it is what the buffer contains.
 * 3. Layout of a 5,000-line note costs ~110 ms per keystroke and decoration adds
 *    ~76 ms on top. Both are measured in `KeystrokeBudgetTest`. Neither is near a
 *    frame, which is why block-bounded reparse is a correctness requirement rather
 *    than an optimisation.
 *
 * There is deliberately no layout cache here. One existed, keyed by content hash, and
 * it was invalidated on every keystroke while never being read: `put` and `get` had no
 * production caller, so the map was permanently empty and the invalidation walked it to
 * remove nothing. What it cost was real, though — a content hash for every block in the
 * document on every edit. Compose already caches layout for the text it renders, via
 * `Paragraph`/`MultiParagraph`, so a second cache in front of it duplicated an
 * allocation without saving a layout.
 *
 * **Decorations a host contributes.**
 *
 * [decorations] are applied after the library's own, and win where they overlap.
 * Both halves of that are deliberate. *After* because Compose resolves a conflicting
 * span attribute in favour of whichever was added last. *Win* because the case that
 * motivates it is one where the host's answer is more specific than the library's
 * default: a `[[wikilink]]` whose target exists is not the same thing as one whose
 * target has not been written yet, and only the host knows which. Drawing them alike
 * makes an unfinished note look broken, and it is the only feedback a writer gets
 * that a link is dangling.
 *
 * Ranges come from [SyntaxExtension.recogniseInline], so a host recognises a
 * construct once and then styles and reacts to it:
 *
 * ```
 * MarkdownEditor(
 *     state = state,
 *     decorations = resolved.map { Decoration(it.range, SpanStyle(color = green)) },
 *     onDecorationClick = { openTarget(it) },
 * )
 * ```
 *
 * Decorations are **not** state. They are recomputed each frame from the same parse
 * the library's own spans come from, which is what keeps a decoration bug
 * reproducible in a unit test rather than only as a screenshot somebody has to notice.
 * The cost is that a decoration cannot survive a frame on its own: if a host holds a
 * [dev.fude.core.InlineRange] across an edit, that offset is stale and the span lands
 * wherever it now falls. Recompute from the parse each time.
 *
 * **How a click is reported.** [onDecorationClick] fires when a tap lands inside a
 * decoration's range, and the [Decoration] passed is that decoration. A decoration's
 * own [Decoration.onClick] takes precedence over it, so a host that wants a different
 * reaction for one particular range can say so per-decoration rather than by
 * inspecting the argument.
 *
 * The offset comes from the caret Compose places, not from a coordinate hit-test:
 * this version exposes no `TextLayoutResult` through the text field's buffer or state,
 * so there is no way to turn a tap position into an offset. Since a tap inside a link
 * is a click on the link either way, and a *programmatic* caret move never goes
 * through a pointer handler, this fires when the user taps a decoration and not when
 * the caret merely moves into one. A host that needs tap-position semantics rather than
 * decoration semantics will have to wait for the layout result to be reachable.
 *
 * **The dispatch rule is tested; the gesture is not.** Which decoration a given offset
 * reports is `decorationAt`, a pure function, and it has tests. That the tap arrives at
 * all does not: `performClick` on a text field node does not reach a `pointerInput`
 * modifier on it in this version's test harness, so there is currently no headless way
 * to drive this path. The gesture wiring is therefore unverified by the suite, which is
 * a real gap rather than a stylistic one — the demo at `fude-demo` is where it is
 * exercised.
 *
 * **Per-block source/rendered mode.**
 *
 * Which blocks show as raw Markdown is per-block view state, and it lives in a
 * [BlockViewState] the host supplies:
 *
 * ```
 * val view = remember { BlockViewState() }
 * MarkdownEditor(state = state, view = view)
 * // later, from anywhere:
 * val mode = view.toggle(blockStart)
 * ```
 *
 * The parameter defaults to a fresh instance per composition, which is what a host
 * that does not care about source mode should get — the default is correct, not a
 * trap. A host that *does* care must hold the same instance across recompositions,
 * since a new one every frame would discard every toggle. That is why this is a
 * parameter rather than something the composable keeps to itself: the state belongs
 * to whoever has to remember it, and the composable cannot remember it on the host's
 * behalf.
 *
 * **Plain mode: no Markdown styling at all.**
 *
 * [EditorConfig.showMarkdownDecorations] turns the library's own styling off for
 * the whole document — headings, emphasis, code, links, quotes, lists, tables:
 * everything [decorationSpans] would otherwise emit. What is left is the source
 * text in the host's [EditorConfig.textStyle], which is the mode for editing
 * Markdown as text rather than reading it rendered.
 *
 * This is the document-wide version of toggling every block to source, without
 * touching the per-block state: [view] is neither read for this decision nor
 * modified by it, so flipping the flag off and on again restores exactly the
 * toggles the host had. It also never marks the document modified — like a
 * toggle, it changes what is drawn, not what is there.
 *
 * Host [decorations] are unaffected. They are passed per frame and applied last,
 * so they are the host's own answer about its own syntax rather than the
 * library's — and they keep their click behavior, which plain mode must not
 * remove. (Host *syntax* recognised through a `SyntaxExtension` is styled by
 * the library's own pass, so it hides with everything else; only the explicit
 * list survives.)
 *
 * **The host owns the document-swap lifecycle.** Keys are block start offsets, so every
 * one of them is stale the moment a different note is loaded. An *edit* is handled here:
 * [ToggleCoordinator.afterEdit] shifts the keys and drops any that no longer name a
 * block, so a toggle follows its block as text above it moves. A *swap* cannot be, and
 * the reason is worth stating rather than leaving as a surprise — the composable sees
 * both arrive as "the text changed", and only the host knows it put a different document
 * in. So loading another note is the host's call:
 *
 * ```
 * view.clear()   // a different note: no toggle carries over
 * ```
 *
 * That is almost always right, since which blocks show source is a property of one
 * note. A host doing a wholesale *replace* of the same document may prefer
 * [BlockViewState.retainOnly] against the new parse's block starts. Either way it is a
 * choice, made where the knowledge is.
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
fun MarkdownEditor(
    state: EditorState,
    modifier: Modifier = Modifier,
    config: EditorConfig = EditorConfig(),
    syntaxExtensions: List<SyntaxExtension> = emptyList(),
    undo: dev.fude.core.UndoController = remember { dev.fude.core.UndoStack() },
    view: BlockViewState = remember { BlockViewState() },
    onChange: (String) -> Unit = {},
    decorations: List<Decoration> = emptyList(),
    onDecorationClick: ((Decoration) -> Unit)? = null,
) {

    // The Compose-side text state. The pure model is the source of truth for
    // selection and undo; this holds what Compose renders.
    val textState = rememberTextFieldState(state.text)
    // Undo history over the edits Compose reports.
    //
    // Compose owns the buffer during a gesture and hands over the whole new string,
    // not a keystroke. `editBetween` recovers the single Edit from that report so the
    // same grouping rules apply here as on the model API — which is what makes a
    // typed word one undo step in a real session rather than only in a test.
    //
    // The stack is created by the public overload's default argument and reaches
    // this function as an `UndoController`, so it never becomes public API. A host
    // that wants one history shared across editors, a decorator, or a no-op for a
    // read-only field passes its own — the same discipline a `NavHost` applies to
    // its back stack. It also means `canUndo` is answerable by a host, which it was
    // not while the stack was private to this function.
    // Keyed on the dialect, not on the list instance: a parser built without the
    // host's extensions can never produce a host-defined block, so a genuinely
    // different dialect rebuilds the parser — but a host rebuilding `listOf(...)`
    // every recomposition must not. New instances of the same dialect classes with
    // the same ids reuse the parser, so typing never pays a full reparse for an
    // allocation. (Swapping implementations under an identical class+id keeps the
    // old parser; a dialect change that reuses both is a new-dialect event the
    // key cannot see, and the host should change the id with it.)
    val extensionKey = syntaxExtensions.map { it::class to it.id }
    val parser = remember(extensionKey) { IncrementalMarkdownParser(syntaxExtensions) }
    // The parse is layout input, not composition input. It changes on every
    // keystroke, so reading it as a `by` delegate during composition would
    // recompose this whole function — recreating the transformation and the
    // gesture detector below — per character, which is the flicker. A State
    // holder read only at layout/gesture/event time updates the screen without
    // recomposing it; composition subscribes to the toggle snapshot instead,
    // which moves rarely.
    val parsedHolder = remember(extensionKey) { mutableStateOf(parser.parse(state.text)) }

    // One effect does the whole per-keystroke pipeline, in order:
    //   1. mirror the field's text and selection into the pure model
    //   2. record the edit in undo history
    //   3. reparse, bounded to the affected block
    //   4. keep block toggle keys pointing at the same blocks
    //   5. invalidate only the layout entries whose content changed
    //
    // Doing these in separate effects would let them interleave across frames and
    // decorate a stale parse.
    LaunchedEffect(textState.text, textState.selection) {
        val current = textState.text.toString()
        var gestureEdit: dev.fude.core.Edit? = null
        // The model is captured before mirroring: `state` is an observable shell,
        // so aliasing it and reading `before.model` after `applyEdit` hands record
        // (new, new) and history stays empty while the text visibly updates.
        val beforeText = state.text
        val beforeModel = state.model
        if (current != beforeText) {
            val edit = dev.fude.core.editBetween(beforeText, current)
            state.applyEdit(
                current,
                CoreTextRange(textState.selection.start, textState.selection.end),
            )
            // Recorded after the model has the new text, so the entry stores the
            // selection the edit actually produced rather than the one it replaced.
            //
            // Null when the text is unchanged — a pure selection move — which is
            // correctly not an edit and must not become an undo step.
            if (edit != null) {
                undo.record(beforeModel, state.model, edit)
                gestureEdit = edit
            }
            onChange(current)
        }

        val previous = parsedHolder.value
        if (previous.text != current) {
            // The recovered gesture edit spans what the user actually changed. A
            // synthetic empty insert collapses to a point, so the reusable-tail
            // filter keeps blocks the edit destroyed and correctness rests on the
            // fallback. Only valid while the parse still describes the text the
            // edit was recovered from; otherwise an honest spanning edit between
            // the two texts (e.g. a host document swap with no gesture).
            val reparseEdit = if (gestureEdit != null && previous.text == beforeText) {
                gestureEdit
            } else {
                dev.fude.core.editBetween(previous.text, current)
            }
            if (reparseEdit != null) {
                val next = parser.reparse(current, reparseEdit)
                parsedHolder.value = next
                val range = reparseEdit.affectedRange
                val editDelta = reparseEdit.replacement.length - (range.end - range.start)
                ToggleCoordinator.afterEdit(view, range.start, editDelta, ToggleCoordinator.blockStarts(next.blocks))
            }
        }
    }

    // Push the model's selection onto Compose's, so the caret stays where the pure
    // model says it is. Without this the two drift and the caret lands wrong after
    // any programmatic move.
    //
    // Selection is written through `edit`, not assigned: `TextFieldState.selection`
    // is a val, and mutating it directly is not how the platform expects a
    // programmatic caret move to arrive.
    // Single model->field pipeline: text then selection, in order, so the two
    // cannot interleave across frames and decorate a stale caret.
    LaunchedEffect(state.text, state.selection) {
        if (state.text != textState.text.toString()) {
            val replacement = state.text
            textState.edit { replace(0, length, replacement) }
        }
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

    // Undo and redo are handled here rather than left to the platform.
    //
    // `BasicTextField` has no undo of its own on every target we support, and where
    // it does have one it operates on its own whole-text snapshots, bypassing the
    // grouping rules the ticket specifies. Interception also means history follows
    // the same path no matter which platform produced the edit.
    val handleUndoKey: (Boolean) -> Boolean = { redo ->
        applyUndoStep(undo, state, textState, redo)
    }

    // Shift-Tab outdents the list the caret is in. Tab is deliberately left alone:
    // indentation on the way in is not implemented (4YWC9NV2), and swallowing Tab
    // without replacing it would trap keyboard users in a field they cannot leave.
    // Reporting `false` for an un-outdentable line lets the platform move focus,
    // which is what Shift-Tab on prose should do anyway.
    val handleOutdentKey: (Boolean) -> Boolean = { shift ->
        // Read at event time, not composition time: the parse moves every
        // keystroke and must not recompose the field to reach this handler.
        applyOutdentStep(parsedHolder.value, textState, shift)
    }

    // Which blocks show source, as a value. Read during composition so a toggle
    // recomposes (rare); the parse itself stays out of composition (every
    // keystroke) and is read from its holder at layout time instead.
    val toggleSnapshot = view.sourceBlocks()
    // Plain mode, hoisted so every consumer below reads one value. A field on
    // the config rather than a parameter: it is display state the host holds
    // next to `readOnly` and `maxLines`, flipped with `config.copy(...)`.
    val showMarkdownDecorations = config.showMarkdownDecorations
    // Latest host values without recreating the transformation below when the
    // host rebuilds its lists every recomposition. New list instance, same
    // content: no reason to touch the field.
    val decorationsHolder = rememberUpdatedState(decorations)
    val decorationClickHolder = rememberUpdatedState(onDecorationClick)
    // One transformation for the life of the toggle set and the plain-mode flag,
    // not one per frame. Recreating `OutputTransformation` every recomposition
    // re-applies decoration over the whole document per character; this one reads
    // the latest parse at layout time, so keystrokes redecorate without rebuilding
    // it. The flag is a key rather than a holder read because flipping it must
    // redecorate even when the text did not move.
    val decorationTransformation = remember(toggleSnapshot, showMarkdownDecorations) {
        OutputTransformation {
            applyDecoration(this, parsedHolder.value, view, decorationsHolder.value, showMarkdownDecorations)
        }
    }

    // Single Box, not Column > Box: one child needs one layout, not two nested
    // full-size containers.
    Box(modifier = modifier.fillMaxSize()) {            // The placeholder sits *behind* the field rather than inside it.
            //
            // `BasicTextField` in this version takes its decoration as a
            // `TextFieldDecorator`, and replacing that is how a library loses the
            // platform's own decoration — the cursor handle, the selection colours,
            // whatever the host's theme put there. Overlaying costs none of that, and
            // it is also the arrangement Material uses, so the caret sits on top of
            // the hint exactly where the user expects when they start typing.
            //
            // The field draws no background of its own, so the hint shows through.
            // `textState.text` rather than `state.text`: what is visible is the
            // buffer, and it is already read as a `LaunchedEffect` key above, so this
            // costs no recomposition the composable was not already paying for.
            val placeholder = config.placeholder
            if (placeholder != null && textState.text.isEmpty()) {
                BasicText(
                    text = placeholder,
                    style = placeholderStyle(config),
                    modifier = Modifier.padding(config.contentPadding),
                )
            }

            BasicTextField(
                state = textState,

                modifier = Modifier
                    // `fillMaxSize` and `maxLines` are contradictory instructions to
                    // a layout: one asks the field to take whatever height it is given,
                    // the other to take at most N lines, and the first wins silently —
                    // which is how `maxLines` spent its life as a field that was read
                    // and did nothing. So a host that sets it gets a field sized to its
                    // content up to that many lines, and only a host that leaves it
                    // unbounded gets the fill-the-container default.
                    .then(if (config.maxLines == Int.MAX_VALUE) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                    .padding(config.contentPadding)
                    .testTag(TAG_EDITOR)
                    .then(
                        if (onDecorationClick == null && decorations.none { it.onClick != null }) {
                            Modifier
                        } else {
                            // Keyed on the toggle set and the plain-mode flag, not on
                            // the parse or the host's lists: restarting this
                            // detector every keystroke drops the gesture in flight
                            // and the tap never reports. Latest values come from
                            // holders at gesture time.
                            Modifier.pointerInput(toggleSnapshot, showMarkdownDecorations) {
                                // `requireUnconsumed = false` because the text field
                                // consumes the first down itself to place the caret. A
                                // tap is still a tap, and we want to hear about it --
                                // but only once it has actually become one, hence the
                                // wait for the up rather than firing on the down.
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val released = waitForUpOrCancellation() != null
                                    if (!released) return@awaitEachGesture
                                    // The caret is where Compose put it for this tap.
                                    // See the KDoc on why this is not a hit-test.
                                    val offset = textState.selection.start
                                    val hit = decorationAt(decorationSpans(parsedHolder.value, view, decorationsHolder.value, showMarkdownDecorations), offset)
                                    val click = hit?.onClick
                                    if (click != null) click()
                                    else if (hit != null) decorationClickHolder.value?.invoke(hit)
                                }
                            }
                        },
                    )
                    .onPreviewKeyEvent { event ->
                        // KeyDown for everything. `onPreviewKeyEvent` sees both halves
                        // of a press, and acting on KeyUp as well would fire twice per
                        // tap. Undo previously fired on KeyUp while outdent fired on
                        // KeyDown, so releasing ⌘Z felt laggy and the two paths
                        // disagreed about which phase owns a press.
                        //
                        // Repeat policy: every KeyDown is consumed, including OS
                        // auto-repeat while the keys are held. That matches a native
                        // field, where holding ⌘Z keeps undoing one step per repeat.
                        // KeyUp is never consumed here, so the platform still sees
                        // the release.
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key == Key.Tab) {
                            return@onPreviewKeyEvent handleOutdentKey(event.isShiftPressed)
                        }
                        val command = event.isMetaPressed || event.isCtrlPressed
                        if (!command) return@onPreviewKeyEvent false
                        // Shift is the platform's own convention for the other direction — ⇧⌘Z on macOS,
                        // Ctrl+Y on Windows — so it is honoured rather than second-guessed.
                        when (event.key) {
                            Key.Z -> handleUndoKey(event.isShiftPressed)
                            Key.Y -> handleUndoKey(true)
                            else -> false
                        }
                    },
                textStyle = config.textStyle,
                readOnly = config.readOnly,
                // A viewport height, not a content limit. `BasicTextField` scrolls, so
                // a document longer than this is whole and editable — you see the first
                // `maxLines` lines and scroll for the rest, and nothing is truncated.
                // `EditorConfig.maxLines` says so as well, because the name reads the
                // other way and a host expecting truncation would be badly surprised.
                lineLimits = if (config.maxLines == Int.MAX_VALUE) {
                    TextFieldLineLimits.Default
                } else {
                    TextFieldLineLimits.MultiLine(
                        minHeightInLines = 1,
                        maxHeightInLines = config.maxLines,
                    )
                },
                // Decoration is reapplied here, per frame, from the current parse. The
                // ranges come from the block tree, so a reparse and a decoration pass
                // cannot disagree about where anything is. The transformation object
                // itself is remembered above and reads the latest parse at layout
                // time, so this parameter stays stable across keystrokes.
                outputTransformation = decorationTransformation,
            )
    }
}

/**
 * The style `EditorConfig.placeholder` is drawn in.
 *
 * The hint's own text style, with a colour faded to 60% so it reads as a hint and
 * not as content. When the host set an explicit colour that one is faded; when it
 * did not, a mid grey stands in, because an unspecified colour would leave
 * `BasicText` to fall back to the ambient content colour and the hint would be
 * drawn in exactly the weight of real text.
 *
 * That grey is fixed rather than themed, and it is a small deliberate compromise:
 * reading the ambient content colour means depending on Material's
 * `LocalContentColor`, and this library depends on foundation and ui only. A host
 * that wants a themed hint sets [EditorConfig.textStyle]'s colour and gets it.
 */
private fun placeholderStyle(config: EditorConfig): TextStyle {
    val specified = config.textStyle.color
    val base = if (specified == Color.Unspecified) PLACEHOLDER_COLOR else specified
    return config.textStyle.copy(color = base.copy(alpha = 0.6f))
}

private val PLACEHOLDER_COLOR: Color = Color(0xFF9A9A9A)

/**
 * Every span the current parse calls for, in the order they must be applied.
 *
 * **Pure in (parse, view, showMarkdownDecorations).** The same arguments always
 * produce the same spans, with no reference to a buffer, a frame or a font. That
 * is what "rendering is a pure function of state" has to mean if it is to mean
 * anything, and it is what makes a decoration bug reproducible in a unit test
 * instead of only as a screenshot somebody has to notice.
 *
 * Order matters and is the traversal order, because Compose resolves overlapping
 * spans by the order they were added. Collecting them into a list preserves it
 * exactly; the only difference from adding them as they were found is that the list
 * now exists, which is the point.
 */
internal fun decorationSpans(
    parsed: ParsedDocument,
    view: BlockViewState,
    host: List<Decoration> = emptyList(),
    showMarkdownDecorations: Boolean = true,
): List<Decoration> {
    val spans = mutableListOf<Decoration>()
    // Plain mode: the library styles nothing. The host's own decorations below
    // still apply — they are the host's answer about its own syntax, not
    // Markdown's, and they carry click behavior plain mode must not remove.
    if (showMarkdownDecorations) {
        for (block in parsed.blocks) {
            // A block showing source is deliberately left undecorated: the user is
            // editing Markdown and wants to see it as written.
            if (view.modeOf(block.range.start) == RenderMode.SOURCE) continue
            collectBlockSpans(spans, block, parsed.text)
        }
    }

    // Host inline syntax deliberately gets no pass of its own.
    //
    // `parseInline` already runs every registered extension over each block's inline
    // run, resolves the overlaps through `resolveMatches` and emits a
    // `HostInlineNode` per survivor, which `collectInlineSpans` styles above. So the
    // extension point is honoured once, per block, in the same order as the library's
    // own Markdown.
    //
    // There used to be a second loop here that called `recogniseInline` over
    // `parsed.contentRange()` — the whole document, every frame — and added a span
    // for every range it got back. It was vestigial, and wrong three ways at once: it
    // scanned the document instead of the block the user typed in, it styled blocks
    // the user had explicitly toggled to source, and it bypassed `resolveMatches`, so
    // two overlapping extensions both got their background and whichever came last in
    // the list won. It applied the same `0x1A4A90D9` the `HostInlineNode` branch
    // applies, which is the tell that it was redundant rather than complementary: it
    // existed to style something the parse had stopped producing, and once the parse
    // produced it the loop was a second and disagreeing answer to a question already
    // answered. Its comment claimed it was giving a mention precedence over Markdown,
    // which is the opposite of what iterating a list in order does.
    //
    // The cost consequence is the part worth keeping: `recogniseInline` now runs once
    // per block per reparse rather than once per document per frame, so a host
    // recogniser's cost is bounded by the block being edited rather than by the size
    // of the note. See `docs/extending.md` for the contract a host can rely on.

    // A host's own decorations go last, and that ordering *is* the precedence rule.
    // Compose resolves a conflicting span attribute in favour of whichever was added
    // last, so appending here is what makes a host's answer beat the library's default
    // on the same range. That is the case that matters: a wikilink whose target exists
    // is not the same thing as one whose target has not been written yet, only the
    // host knows which, and drawing both alike makes a half-finished note look broken.
    spans.addAll(host)
    return spans
}

/**
 * The topmost decoration covering [offset], or null.
 *
 * "Topmost" is the last one in the list, which is the same answer Compose gives when
 * it resolves overlapping spans — so the decoration a click reports is the one the user
 * actually sees drawn there, not merely the first that happens to contain the offset.
 *
 * Half-open, like every range in the library: a decoration covering `[0, 9)` contains
 * offset 8 and not offset 9. A tap exactly on the boundary between two adjacent
 * decorations therefore reports the second, which is the one under the caret's visual
 * right edge.
 *
 * Pure, and deliberately so. The gesture that produces [offset] needs a frame and a
 * tap, but this decision does not, and putting it here means the rule is checkable
 * without either.
 */
internal fun decorationAt(spans: List<Decoration>, offset: Int): Decoration? =
    spans.lastOrNull { offset >= it.range.start && offset < it.range.end }

/** Appends the spans for one block and, recursively, its children. */
private fun collectBlockSpans(spans: MutableList<Decoration>, block: BlockNode, text: String) {
    when (block) {
        is CodeFenceNode -> {
            // Opaque by construction. A fence is a place the user has said is not
            // Markdown, so nothing inside it is decorated — including host syntax,
            // which `parseInline` does parse inside the fence but which is
            // deliberately not drawn. Highlighting a mention in a code sample would
            // tell the user their fence was being interpreted.
            spans += Decoration(block.range, SpanStyle(color = Color(0xFF6A9955)))
        }

        is HostBlockNode -> {
            // A construct the host claimed and the library has no opinion about.
            // Tinting the whole block is what makes a callout visibly a callout; the
            // library knows the block exists and nothing else about it, which is the
            // whole arrangement.
            spans += Decoration(block.range, SpanStyle(background = Color(0x147A9E7E)))
            for (inline in block.inlines) collectInlineSpans(spans, inline)
        }

        is HeadingNode -> {
            // Bold and larger by level, over the whole block including the `#`
            // markers. Markers stay visible: source is the truth and this library
            // decorates in place rather than rewriting the buffer, so hiding them
            // the way Obsidian does is not available. Inlines are the heading's
            // own (a heading has no block children) and are collected once, here.
            spans += Decoration(block.range, headingStyle(block.level))
            for (inline in block.inlines) collectInlineSpans(spans, inline)
        }

        is BlockQuoteNode -> {
            // A tint over the whole quote, markers included, for the same reason
            // as the heading: the `>` stays visible and the tint is what says
            // "quote". Children recurse normally; a quote holds blocks, not
            // inlines, so nothing here can double-collect.
            spans += Decoration(block.range, SpanStyle(background = QUOTE_BACKGROUND))
            for (child in block.children) collectBlockSpans(spans, child, text)
        }

        is ListItemNode -> {
            // The marker only — `-`, `+`, `*`, or the typed `1.` — drawn as chrome
            // rather than content, while the marker itself stays visible for the
            // reason above. Ordered numbers are the typed numbers: this library
            // does not renumber lists, so `3.` after `1.` draws as `3.`.
            //
            // Inlines are deliberately NOT collected here. The item carries the
            // same inline objects as the paragraph beneath it, so collecting from
            // both paths emits every span twice; the child paragraph below is the
            // one path that collects them.
            listMarkerRange(text, block.range)?.let { marker ->
                spans += Decoration(marker, LIST_MARKER_STYLE)
            }
            for (child in block.children) collectBlockSpans(spans, child, text)
        }

        is TableNode -> {
            // Structure is the header standing apart from the body: the header row
            // draws bold over its whole range, delimiter row and pipes included.
            // Pipes stay visible for the source-in-place reason above; the table
            // reads as a table because its header does not read as a row.
            if (block.hasHeader) {
                block.children.firstOrNull()?.let { header ->
                    spans += Decoration(header.range, SpanStyle(fontWeight = FontWeight.Bold))
                }
            }
            for (child in block.children) collectBlockSpans(spans, child, text)
        }

        is ThematicBreakNode -> {
            // The dashes draw faded rather than literal. There is no rule to draw
            // — a SpanStyle cannot draw a line — so a faded marker is the honest
            // rendering of "a break lives here".
            spans += Decoration(block.range, SpanStyle(color = Color(0xFFBDBDBD)))
        }

        else -> {
            // Inlines are collected from leaves only.
            //
            // A `ListItemNode` carries the same `InlineNode` objects as the
            // `ParagraphNode` beneath it, so walking both paths emits every inline in
            // a list twice — the same range, the same style, twice per frame. It is
            // invisible on screen because `addStyle` is idempotent, and it is still a
            // defect: it doubles decoration cost on exactly the documents where lists
            // dominate, and it makes the returned span list a lie about how many spans
            // the parse produced.
            //
            // The rule is that inlines live on leaves. A container's copy is a
            // duplicate view of its content, not a second thing to draw.
            //
            // Paragraphs are deliberately unstyled: a paragraph is the default, so a
            // span on every one would be cost with no information. This branch covers
            // paragraphs, plain list containers, table rows and cells — the last two
            // recurse to (or are) leaves whose inlines are collected exactly once.
            if (block.children.isEmpty()) {
                for (inline in block.inlines) collectInlineSpans(spans, inline)
            }
            for (child in block.children) collectBlockSpans(spans, child, text)
        }
    }
}

/** Bold and larger by heading level, h1 largest. Exact steps, not arithmetic, so goldens are exact. */
internal fun headingStyle(level: Int): SpanStyle = SpanStyle(
    fontWeight = FontWeight.Bold,
    fontSize = when (level) {
        1 -> 1.5f
        2 -> 1.4f
        3 -> 1.3f
        4 -> 1.2f
        5 -> 1.1f
        else -> 1.0f
    }.em,
)

/** The tint a block quote is drawn with. */
internal val QUOTE_BACKGROUND: Color = Color(0x0F000000)

/** A list marker drawn as chrome rather than content. */
internal val LIST_MARKER_STYLE: SpanStyle = SpanStyle(
    fontWeight = FontWeight.Bold,
    color = Color(0xFF9A9A9A),
)

/**
 * The range of a list item's marker (`-` or the typed `1.`), or null when the
 * item's range does not start with one.
 *
 * Read from the source text rather than the block tree because the tree records
 * the marker only implicitly (ordered vs unordered): renumbering is not
 * implemented, so the honest marker is the typed one. Bounds are clamped to the
 * document; a parser bug must not become an index crash here any more than in
 * [addStyle].
 */
internal fun listMarkerRange(text: String, itemRange: dev.fude.core.InlineRange): dev.fude.core.InlineRange? {
    var i = itemRange.start.coerceIn(0, text.length)
    val end = itemRange.end.coerceIn(0, text.length)
    while (i < end && (text[i] == ' ' || text[i] == '\t')) i++
    if (i >= end) return null
    val markerEnd = when {
        text[i] == '-' || text[i] == '+' || text[i] == '*' -> i + 1
        text[i].isDigit() -> {
            var j = i
            while (j < end && text[j].isDigit()) j++
            if (j < end && (text[j] == '.' || text[j] == ')')) j + 1 else return null
        }
        else -> return null
    }
    return dev.fude.core.InlineRange(i, markerEnd)
}

private fun collectInlineSpans(spans: MutableList<Decoration>, inline: InlineNode) {
    when (inline) {
        is EmphasisNode -> {
            spans += Decoration(
                inline.range,
                if (inline.strong) {
                    SpanStyle(fontWeight = FontWeight.Bold)
                } else {
                    SpanStyle(fontStyle = FontStyle.Italic)
                },
            )
            for (child in inline.children) collectInlineSpans(spans, child)
        }

        is dev.fude.markdown.CodeSpanNode ->
            spans += Decoration(inline.range, SpanStyle(background = Color(0x1F000000)))

        is LinkNode ->
            spans += Decoration(
                inline.labelRange,
                SpanStyle(
                    color = Color(0xFF3B7DD8),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                ),
            )

        is dev.fude.markdown.ImageNode ->
            spans += Decoration(inline.range, SpanStyle(color = Color(0xFF8A6A3B)))

        is HostInlineNode -> spans += Decoration(inline.range, HOST_INLINE_SPAN)

        else -> Unit
    }
}

/** The style a host inline match is drawn with, whatever syntax produced it. */
internal val HOST_INLINE_SPAN: SpanStyle = SpanStyle(background = Color(0x1A4A90D9))

/**
 * Replays [decorationSpans] onto [buffer].
 *
 * The guard is the reason this is a separate function at all: `OutputTransformation`
 * runs during layout, while the parse is refreshed by a `LaunchedEffect` that can only
 * run once the frame is being applied. So on the first frame after the buffer's text
 * changes, the parse is still the previous document's. When the new text is shorter
 * the old tree's ranges point past the end of the buffer, and `addStyle` throws — a
 * crash on the commonest editor path there is, a host loading a document.
 *
 * Comparing lengths first keeps the mismatch case O(1). The full comparison only runs
 * once the lengths already agree, which is the frame-to-frame case.
 */
private fun applyDecoration(
    buffer: androidx.compose.foundation.text.input.TextFieldBuffer,
    parsed: ParsedDocument,
    view: BlockViewState,
    host: List<Decoration>,
    showMarkdownDecorations: Boolean,
) {
    val text: String = buffer.originalText.toString()
    if (parsed.text.length != text.length || parsed.text != text) return
    for (span in decorationSpans(parsed, view, host, showMarkdownDecorations)) {
        addStyle(buffer, span.range, span.spanStyle)
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

/** How many characters two strings share from the start. Same scan as `editBetween`'s prefix; stdlib delegate. */
internal fun commonPrefixLength(a: String, b: String): Int = a.commonPrefixWith(b).length

internal const val TAG_EDITOR = "fude-editor"

/**
 * Undo unit: applies one undo/redo step through model then field.
 *
 * Null means there was nothing to undo, and the key is still consumed.
 * That reverses what this used to do — report `false` so the platform could
 * try its own path — and the reversal is load-bearing: `BasicTextField` has
 * its own undo over whole-text snapshots that bypasses grouping, and letting
 * it run resurrects text this history says is undone. Two histories answering
 * the same key is the second-path rot `EditorState.apply` exists to prevent.
 */
internal fun applyUndoStep(
    undo: dev.fude.core.UndoController,
    state: EditorState,
    textState: TextFieldState,
    redo: Boolean,
): Boolean {
    val target = if (redo) undo.redo(state.model) else undo.undo(state.model)
    if (target == null) return true
    // Written through the model rather than straight into the field, so the
    // undo entry's recorded selection and the visible caret cannot disagree.
    state.applyEdit(target.text.text, target.selection)
    textState.edit {
        replace(0, length, target.text.text)
        selection = androidx.compose.ui.text.TextRange(
            target.selection.start.coerceIn(0, target.text.text.length),
            target.selection.end.coerceIn(0, target.text.text.length),
        )
    }
    return true
}

/** Outdent unit: Shift-Tab outdents the list the caret is in, else `false` for focus move. */
internal fun applyOutdentStep(
    parsed: ParsedDocument,
    textState: TextFieldState,
    shift: Boolean,
): Boolean {
    if (!shift) return false
    val selection = textState.selection
    val edit = dev.fude.markdown.ListIndent.outdentRange(
        parsed,
        minOf(selection.start, selection.end),
        maxOf(selection.start, selection.end),
    ) ?: return false
    textState.edit {
        replace(edit.affectedRange.start, edit.affectedRange.end, edit.replacement)
    }
    return true
}
