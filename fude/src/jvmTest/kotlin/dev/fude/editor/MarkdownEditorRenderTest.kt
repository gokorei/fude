package dev.fude.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.fude.core.TextRange as CoreTextRange
import dev.fude.markdown.ToggleCoordinator
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The renderer, through a real Compose frame.
 *
 * These check the things the spike could not: that decoration reaches the screen,
 * that typing reaches the model, and that a toggle does not touch the text. The
 * caret's *visual* position inside decorated output still needs a screenshot
 * comparison and is not asserted here — a headless test can say a span was
 * rendered, but not that it looked right.
 */
@OptIn(ExperimentalTestApi::class)
class MarkdownEditorRenderTest {

    /** The offset the heading in [aHostSuppliedBlockViewStateCanBeToggledFromOutsideTheComposable] starts at. */
    private val HEADING_START = 0
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theEditorRendersTheHostsText() {
        rule.setContent {
            MarkdownEditor(state = EditorState.of("hello world"))
        }
        rule.onNodeWithTag(TAG_EDITOR).assertIsDisplayed()
        rule.onNodeWithTag(TAG_EDITOR).assertTextContains("hello world")
    }

    @Test
    fun markdownSyntaxIsRenderedUndecoratedInTheBuffer() {
        val state = EditorState.of("# Title\n\nbody")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()

        // Source is the truth: what the buffer holds is exactly what the host gave
        // it, markers and all. Decoration is a projection, not a rewrite.
        assertEquals("# Title\n\nbody", state.text)
    }

    @Test
    fun typingReachesTheHostThroughOnChange() {
        var reported: String? = null
        val state = EditorState.of("")
        rule.setContent {
            MarkdownEditor(state = state, onChange = { reported = it })
        }
        rule.onNodeWithTag(TAG_EDITOR).performTextInput("typed")
        rule.waitForIdle()

        assertEquals("typed", reported)
        assertEquals("typed", state.text)
    }

    @Test
    fun aProgrammaticReplacementReachesTheEditor() {
        val state = EditorState.of("before")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()

        rule.runOnIdle { state.applyEdit("after", CoreTextRange(5, 5)) }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_EDITOR).assertTextContains("after")
    }

    @Test
    fun aLargeDocumentRendersWithoutCollapsing() {
        val large = (0 until 2_000).joinToString("\n\n") { "Paragraph $it with **bold**." }
        val state = EditorState.of(large)
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()

        assertEquals(large, state.text, "the document is untouched by rendering")
        rule.onNodeWithTag(TAG_EDITOR).assertIsDisplayed()
    }

    @Test
    fun anEmptyDocumentRendersWithoutCrashing() {
        val state = EditorState.of("")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_EDITOR).assertIsDisplayed()
    }

    @Test
    fun aDocumentOfOnlyANewlineRendersWithoutCrashing() {
        val state = EditorState.of("\n")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_EDITOR).assertIsDisplayed()
    }

    @Test
    fun markdownWithEveryBlockKindRenders() {
        val text = """
            # Heading

            A paragraph with **bold**, *italic*, `code`, [a link](x) and an ![image](y).

            - one
                - nested
            1. first
            2. second

            > quoted

            | a | b |
            |---|---|
            | 1 | 2 |

            ---

            ```kotlin
            val x = "# not a heading"
            ```
        """.trimIndent()
        val state = EditorState.of(text)
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()
        assertEquals(text, state.text)
    }

    @Test
    fun hostExtensionsDoNotBreakRendering() {
        val extension = object : dev.fude.syntax.SyntaxExtension {
            override val id = "test-dialect"
            override fun recogniseInline(context: dev.fude.syntax.BlockContext) =
                listOfNotNull(
                    dev.fude.core.InlineRange(
                        context.range.start,
                        context.range.start + 4,
                    ),
                )
        }
        val state = EditorState.of("abcd and more text")
        rule.setContent {
            MarkdownEditor(state = state, syntaxExtensions = listOf(extension))
        }
        rule.waitForIdle()
        assertEquals("abcd and more text", state.text, "an extension decorates; it never edits")
    }

    @Test
    fun aReadOnlyEditorStillRenders() {
        val state = EditorState.of("read only content")
        rule.setContent {
            MarkdownEditor(state = state, config = EditorConfig(readOnly = true))
        }
        rule.waitForIdle()
        // A read-only field publishes no editable text, so assert on the rendered
        // node existing rather than on its text: Compose deliberately withholds
        // EditableText from a non-editable field.
        rule.onNodeWithTag(TAG_EDITOR).assertIsDisplayed()
        assertEquals("read only content", state.text)
    }

    @Test
    fun performanceProbeNoteLoadsAndRenders() {
        val fixture = dev.fude.spike.SpikeDocument.full
        val state = EditorState.of(fixture)
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()
        assertTrue(state.text.length > 100_000, "the 5,000-line fixture is in play")
    }

    /**
     * Opening one note and then another, which is what a host does when the user
     * clicks a different document.
     *
     * This is the path that crashed. Not because the selection was rejected — it is
     * clamped, and always was — but because the renderer decorated the *previous*
     * document's block tree against the new, shorter buffer, and every range in it
     * past the new end threw out of `addStyle`. The failure needed a real second
     * document with a table in it, because that is where a span far enough down the
     * old text comes from.
     */
    @Test
    fun loadingASecondShorterDocumentDoesNotCrash() {
        val first = """
            # First note

            A paragraph with **bold** text, `code`, and [a link](https://example.com).

            - An item
                - A nested item

            | Column A | Column B |
            |----------|----------|
            | `code`  | **bold** |

            ---

            The last paragraph, long enough that the table's spans sit well past the
            offset where the second document ends.
        """.trimIndent() + "\n"

        var state by mutableStateOf(EditorState.of(first))
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()
        assertEquals(first, state.text)

        val second = "# Second\n\nShort.\n"
        // A host swaps the whole state object, which is what `remember { EditorState.of(..) }`
        // does when its key changes.
        rule.runOnIdle { state = EditorState.of(second) }
        rule.waitForIdle()

        assertEquals(second, state.text, "the host's document wins")
        rule.onNodeWithTag(TAG_EDITOR).assertTextContains("Second", substring = true)

        // The documented caret contract: the host owns it, and `EditorState.of`
        // puts it at the end of the document it was handed. Nothing here carries
        // over from the first note.
        assertEquals(
            CoreTextRange(second.length, second.length),
            state.selection,
            "loading a document puts the caret at its end, wherever the previous one left it",
        )
    }

    /**
     * The same crash from the gesture side: select all, then paste over it.
     *
     * Worth its own test because it arrives through a different path — the platform's
     * replacement gesture rather than a host state swap — and it is the one a user
     * can reach without a host cooperating.
     */
    @Test
    fun pastingOverASelectAllInALongDocumentDoesNotCrash() {
        val long = (0 until 300).joinToString("\n\n") { "Paragraph $it with **bold** and `code`." }
        val state = EditorState.of(long)
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_EDITOR).performTextReplacement("replaced")
        rule.waitForIdle()

        assertEquals("replaced", state.text)
        rule.onNodeWithTag(TAG_EDITOR).assertTextContains("replaced")
    }

    /**
     * A selection belonging to the document the field *used* to hold.
     *
     * The field reports the selection it had when its text was swapped underneath it,
     * so out-of-range offsets are the normal case on this path rather than a caller
     * mistake. Clamping is the contract; the assertion is here so that a future
     * decision to throw instead has to change this test rather than happen by
     * accident.
     */
    @Test
    fun aStaleSelectionFromThePreviousDocumentIsClampedNotRejected() {
        val state = EditorState.of("a document of some length")
        state.selectRange(5, 9)

        val replacement = "# Short\n"
        state.applyEdit(replacement, CoreTextRange(5, 9))

        assertEquals(replacement, state.text)
        assertTrue(
            state.selection.end <= replacement.length,
            "the caret must land inside the new text, was ${state.selection}",
        )
        assertEquals(
            8,
            state.selection.end,
            "and at the end of it, which is where 9 clamps to in an 8-character document",
        )
    }

    // ---------------------------------------------------------------------
    // Host inline decoration, and where it is deliberately *not* applied.
    //
    // These exist because the renderer used to run every extension's
    // `recogniseInline` over the whole document on every frame, styling whatever came
    // back regardless of the block's source/rendered mode. The parse already resolves
    // host matches per block through `resolveMatches` and emits `HostInlineNode`s, so
    // that pass was a second and disagreeing answer.
    //
    // They assert against `decorationSpans` rather than through a frame. That is the
    // point of that function existing: the decoration decision is a pure function of
    // (parse, view), and a frame cannot show you a span that is *absent* — an
    // `assertTextDoesNotContain`-shaped test would pass just as happily with the
    // decoration silently broken, which is exactly the failure these are for.
    // ---------------------------------------------------------------------

    /** `{{name}}`, the host syntax the demo and `docs/extending.md` both use. */
    private class MentionExtension : dev.fude.syntax.SyntaxExtension {
        override val id: String = "render-test-mention"
        override val priority: Int = 10

        override fun recogniseInline(context: dev.fude.syntax.BlockContext): List<dev.fude.core.InlineRange> {
            val text = context.content.toString()
            val matches = mutableListOf<dev.fude.core.InlineRange>()
            var open = text.indexOf("{{")
            while (open >= 0) {
                val close = text.indexOf("}}", open + 2)
                if (close < 0) break
                matches += dev.fude.core.InlineRange(context.range.start + open, context.range.start + close + 2)
                open = text.indexOf("{{", close + 2)
            }
            return matches
        }
    }

    private fun parse(
        document: String,
        extension: dev.fude.syntax.SyntaxExtension = MentionExtension(),
    ): dev.fude.markdown.ParsedDocument =
        dev.fude.markdown.IncrementalMarkdownParser(listOf(extension)).parse(document)

    /**
     * The source text of every highlighted host match, in document order.
     *
     * Text rather than offsets: an offset assertion is arithmetic the test has to get
     * right before it can say anything, and getting it wrong once already did. What
     * matters is *which* matches were highlighted, not what their offsets happen to be.
     */
    private fun highlightedHostText(
        document: String,
        view: dev.fude.markdown.BlockViewState,
    ): List<String> =
        decorationSpans(parse(document), view)
            .filter { it.spanStyle == HOST_INLINE_SPAN }
            .map { document.substring(it.range.start, it.range.end) }

    /**
     * The surviving path: a host inline match in a block showing rendered output is
     * highlighted, with exactly the colour the `HostInlineNode` branch uses.
     *
     * If this fails, the whole-document pass was not redundant after all — nothing
     * else in the file styles host syntax.
     */
    @Test
    fun hostInlineSyntaxIsHighlightedInsideARenderedBlock() {
        val document = "Hello {{alice}} and {{bob}} here."
        assertEquals(
            listOf("{{alice}}", "{{bob}}"),
            highlightedHostText(document, dev.fude.markdown.BlockViewState()),
        )
    }

    /**
     * The toggle, honoured. A block the user set to SOURCE gets nothing decorated
     * inside it — Markdown or host syntax — which is what the block loop's own
     * comment states, and what the whole-document pass used to contradict within a
     * single block: the Markdown undecorated and the mention highlighted.
     */
    @Test
    fun aBlockToggledToSourceHasNoHostSyntaxHighlightedInsideIt() {
        val document = "Hello {{alice}} here.\n\nSecond {{bob}} paragraph."
        val starts = ToggleCoordinator.blockStarts(parse(document).blocks).sorted()
        assertEquals(2, starts.size, "the fixture is two blocks, so this test is about the first one")

        val view = dev.fude.markdown.BlockViewState(
            mapOf(starts[0] to dev.fude.markdown.RenderMode.SOURCE),
        )
        assertEquals(
            listOf("{{bob}}"),
            highlightedHostText(document, view),
            "only the mention in the block still showing rendered output survives",
        )

        view.set(starts[0], dev.fude.markdown.RenderMode.RENDERED)
        assertEquals(
            listOf("{{alice}}", "{{bob}}"),
            highlightedHostText(document, view),
            "and the first block's comes back when it is toggled again",
        )
    }

    /** Toggling a block must not have cost the decoration inside it either. */
    @Test
    fun aSourceBlockLosesItsMarkdownDecorationToo() {
        val document = "A paragraph with **bold** in it.\n\nAnother one."
        val starts = ToggleCoordinator.blockStarts(parse(document).blocks).sorted()
        val rendered = decorationSpans(parse(document), dev.fude.markdown.BlockViewState())
        val sourced = decorationSpans(
            parse(document),
            dev.fude.markdown.BlockViewState(mapOf(starts[0] to dev.fude.markdown.RenderMode.SOURCE)),
        )
        assertTrue(rendered.isNotEmpty(), "the fixture has something to decorate to begin with")
        assertEquals(rendered.size - sourced.size, 1, "exactly the bold span in the first block")
    }

    /**
     * The second thing the whole-document pass styled that the parse deliberately
     * does not: host syntax inside a code fence.
     *
     * `parseInline` does run inside a fence, so a `HostInlineNode` exists there — but
     * a `CodeFenceNode` is treated as opaque and its inlines are not drawn, because a
     * fence is a place the user has said is not Markdown.
     */
    @Test
    fun hostSyntaxInsideACodeFenceIsNotHighlighted() {
        val document = "```kotlin\nval who = \"{{alice}}\"\n```\n"
        assertEquals(
            emptyList<String>(),
            highlightedHostText(document, dev.fude.markdown.BlockViewState()),
            "a fence is opaque: nothing inside it is decorated as anything",
        )
    }

    /**
     * Precedence is resolved, not list order.
     *
     * Two extensions matching the same range used to both get a background, with
     * whichever was later in the list winning by accident. `resolveMatches` picks one:
     * longest match, then priority, then id. Reversing the registration order must not
     * change a single span.
     */
    @Test
    fun overlappingHostSyntaxIsResolvedNotListOrdered() {
        val nested = MentionExtension()
        val greedy = object : dev.fude.syntax.SyntaxExtension {
            override val id: String = "greedy"
            override val priority: Int = 1
            override fun recogniseInline(context: dev.fude.syntax.BlockContext) =
                listOf(dev.fude.core.InlineRange(context.range.start, context.range.end))
        }
        val document = "a {{alice}} b"

        val forwards = decorationSpans(
            dev.fude.markdown.IncrementalMarkdownParser(listOf(nested, greedy)).parse(document),
            dev.fude.markdown.BlockViewState(),
        ).filter { it.spanStyle == HOST_INLINE_SPAN }
        val backwards = decorationSpans(
            dev.fude.markdown.IncrementalMarkdownParser(listOf(greedy, nested)).parse(document),
            dev.fude.markdown.BlockViewState(),
        ).filter { it.spanStyle == HOST_INLINE_SPAN }

        assertEquals(1, forwards.size, "one winner, not two overlapping backgrounds")
        assertEquals(forwards, backwards, "and the order extensions were registered in does not decide it")
    }

    /** The parse is described as a pure function of state; this is that, checked. */
    @Test
    fun decorationSpansAreAPureFunctionOfTheParse() {
        val document = "# Head\n\nA **bold** and `code` paragraph with {{alice}}.\n\n- one\n- two\n"
        val first = decorationSpans(parse(document), dev.fude.markdown.BlockViewState())
        val second = decorationSpans(parse(document), dev.fude.markdown.BlockViewState())
        assertEquals(first, second)
        assertTrue(first.isNotEmpty(), "and it is not vacuously empty")
    }

    // ---------------------------------------------------------------------
    // EditorConfig: the two fields that were declared and read by nothing.
    // ---------------------------------------------------------------------

    /**
     * `maxLines` bounds how tall the field is drawn.
     *
     * Asserted as a comparison against the same document rendered unbounded rather
     * than against an absolute pixel height, because the interesting claim is the
     * *difference* — and a test that hard-codes a line height breaks the first time
     * anyone changes the default font size.
     *
     * The buffer is asserted whole afterwards, which is the other half of the claim:
     * bounding the viewport truncated nothing.
     */
    @Test
    fun maxLinesBoundsTheFieldToFewerLines() {
        val document = (1..12).joinToString("\n") { "Paragraph number $it." }
        val state = EditorState.of(document)
        var config by mutableStateOf(EditorConfig(maxLines = 3))
        rule.setContent { MarkdownEditor(state = state, config = config) }
        rule.waitForIdle()

        val bounded = rule.onNodeWithTag(TAG_EDITOR).fetchSemanticsNode().size.height
        rule.runOnIdle { config = EditorConfig() }
        rule.waitForIdle()
        val unbounded = rule.onNodeWithTag(TAG_EDITOR).fetchSemanticsNode().size.height

        assertTrue(
            unbounded > bounded,
            "12 lines unbounded should be taller than 3 lines bounded, was $unbounded vs $bounded",
        )
        assertEquals(document, state.text, "and bounding the viewport truncated nothing")
        assertEquals(12, document.lines().size)
    }

    /**
     * The placeholder is drawn behind the field as its own text node, so it is found
     * by its content rather than through the field's own semantics — which is also
     * the honest thing to assert, because what the user sees is a string on screen,
     * not a property of the editor.
     */
    @Test
    fun thePlaceholderIsVisibleWhileTheDocumentIsEmpty() {
        val state = EditorState.of("")
        rule.setContent {
            MarkdownEditor(state = state, config = EditorConfig(placeholder = "Write something"))
        }
        rule.waitForIdle()

        rule.onNodeWithText("Write something").assertIsDisplayed()
    }

    @Test
    fun thePlaceholderDisappearsOnTheFirstKeystroke() {
        val state = EditorState.of("")
        rule.setContent {
            MarkdownEditor(state = state, config = EditorConfig(placeholder = "Write something"))
        }
        rule.onNodeWithTag(TAG_EDITOR).performTextInput("a")
        rule.waitForIdle()

        assertEquals("a", state.text)
        assertEquals(
            0,
            rule.onAllNodesWithText("Write something").fetchSemanticsNodes().size,
            "the hint is gone as soon as there is a document to show instead",
        )
    }

    /** Nothing appears in an empty editor when no placeholder was asked for. */
    @Test
    fun noPlaceholderIsShownByDefault() {
        val state = EditorState.of("")
        rule.setContent { MarkdownEditor(state = state) }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_EDITOR).assertIsDisplayed()
        assertEquals("", state.text)
    }

    // ---------------------------------------------------------------------
    // Scroll across the shell.
    //
    // The pure model always preserved it; `EditorState.applyEdit` did not, because it
    // rebuilt the state through `CoreEditorState.of`, which starts at zero. So every
    // keystroke reset the viewport to the top.
    // ---------------------------------------------------------------------

    @Test
    fun anEditKeepsTheScrollOffset() {
        val state = EditorState.of("hello")
        state.model.let { _ ->
            state.applyEdit("hello!", CoreTextRange(6, 6))
        }
        assertEquals(0f, state.model.scroll.offset)

        val scrolled = EditorState.of("hello")
        scrolled.applyEdit("hello!", CoreTextRange(6, 6), scroll = dev.fude.core.ScrollState(120f, 900f))
        assertEquals(120f, scrolled.model.scroll.offset, "a passed offset is carried, not reset")
    }

    @Test
    fun scrollSurvivesTheCaretsOwnOperations() {
        val state = EditorState.of("hello world")
        state.applyEdit("hello world", CoreTextRange(0, 0), scroll = dev.fude.core.ScrollState(64f, 500f))
        state.moveCaretTo(4)
        assertEquals(64f, state.model.scroll.offset, "moving the caret is not scrolling")

        state.selectRange(0, 5)
        assertEquals(64f, state.model.scroll.offset, "nor is selecting")
    }

    @Test
    fun aFreshDocumentStartsAtTheTopDeliberately() {
        // What a host does when the user opens a different note. `EditorState.of` is
        // the documented entry point and starts at zero, and now `applyEdit` says so
        // out loud by taking the offset as a parameter rather than resetting by
        // accident.
        val state = EditorState.of("a whole other note")
        assertEquals(0f, state.model.scroll.offset)

        state.applyEdit("another note entirely", CoreTextRange(0, 0), scroll = dev.fude.core.ScrollState.ZERO)
        assertEquals(0f, state.model.scroll.offset)
    }

    // ---------------------------------------------------------------------
    // Undo injection.
    //
    // The stack is created by the public overload's default and reaches the
    // composable as an `UndoController`, so it never becomes public API. These
    // exist because the failure mode of the alternative is silent: the stack gets
    // inlined back into the composable, everything still works, and nobody notices
    // that a host can once again neither share, decorate, nor observe it.
    // ---------------------------------------------------------------------

    /**
     * Records every call, and delegates nothing.
     *
     * A real [dev.fude.core.UndoStack] would make "did the composable use mine?"
     * unanswerable, because a private one would behave identically. This answers
     * it: if `recorded` is empty the composable built its own stack and ignored the
     * one it was handed.
     */
    private class SpyUndoController : dev.fude.core.UndoController {
        var recorded = 0
        var undoCalls = 0
        var redoCalls = 0
        var stubbedUndoable = false

        override val canUndo: Boolean get() = stubbedUndoable
        override val canRedo: Boolean get() = false

        override fun record(
            stateBefore: dev.fude.core.EditorState,
            stateAfter: dev.fude.core.EditorState,
            edit: dev.fude.core.Edit,
        ) {
            recorded++
            stubbedUndoable = true
        }

        override fun undo(state: dev.fude.core.EditorState): dev.fude.core.EditorState? {
            undoCalls++
            return null
        }

        override fun redo(state: dev.fude.core.EditorState): dev.fude.core.EditorState? {
            redoCalls++
            return null
        }
    }

    @Test
    fun aHostSuppliedUndoControllerIsTheOneThatGetsUsed() {
        val undo = SpyUndoController()
        val state = EditorState.of("")
        rule.setContent { MarkdownEditor(state = state, undo = undo) }

        rule.onNodeWithTag(TAG_EDITOR).performTextInput("typed")
        rule.waitForIdle()

        assertEquals(
            1,
            undo.recorded,
            "the composable built a private stack instead of using the one it was given",
        )
    }

    /**
     * The capability the injection buys.
     *
     * With the stack private to the composable, `canUndo` had no path to a host at
     * all — so a host building an Edit menu could neither enable nor disable Undo.
     * A controller it holds answers the question itself.
     */
    @Test
    fun aHostCanAskWhetherUndoIsAvailable() {
        val undo = SpyUndoController()
        val state = EditorState.of("")
        rule.setContent { MarkdownEditor(state = state, undo = undo) }
        rule.waitForIdle()

        assertFalse(undo.canUndo, "nothing typed yet")

        rule.onNodeWithTag(TAG_EDITOR).performTextInput("typed")
        rule.waitForIdle()

        assertTrue(undo.canUndo, "the host's own controller reports its own state")
    }

    /**
     * A no-op controller is a valid collaborator, which is what a read-only or
     * host-managed-history editor wants.
     */
    @Test
    fun aNoOpControllerIsAValidCollaborator() {
        val state = EditorState.of("fixed")
        rule.setContent {
            MarkdownEditor(
                state = state,
                undo = object : dev.fude.core.UndoController {
                    override val canUndo: Boolean get() = false
                    override val canRedo: Boolean get() = false
                    override fun record(
                        stateBefore: dev.fude.core.EditorState,
                        stateAfter: dev.fude.core.EditorState,
                        edit: dev.fude.core.Edit,
                    ) = Unit

                    override fun undo(state: dev.fude.core.EditorState) = null
                    override fun redo(state: dev.fude.core.EditorState) = null
                },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_EDITOR).assertIsDisplayed()
    }

    @Test
    fun undoStackSatisfiesTheControllerPort() {
        // Compile-time evidence, asserted so the relationship is documented rather
        // than incidental: the three callers need three methods between them, and
        // the rest of UndoStack is diagnostics nobody branches on.
        val stack: dev.fude.core.UndoController = dev.fude.core.UndoStack()
        assertFalse(stack.canUndo)
        assertNull(stack.undo(dev.fude.core.EditorState.of("x")))
    }
    /**
     * A host can hold the [dev.fude.markdown.BlockViewState] and toggle a block itself.
     *
     * `view` used to be created inside the composable and kept private, so the
     * per-block source/rendered toggle — a documented feature, and an input to every
     * decoration decision in the render path — had no path from a host at all. There
     * was an internal overload with a different arity purely so a test could reach it,
     * which is what made the gap invisible: the symbol was referenced, just never by
     * anything a host could call.
     *
     * This drives it the way a host would: create the state, pass it in, call `toggle`
     * from outside the composable, and observe the change.
     */
    @Test
    fun aHostSuppliedBlockViewStateCanBeToggledFromOutsideTheComposable() {
        val document = "# Heading {{alice}}\n\nA paragraph.\n"
        val state = EditorState.of(document)
        val view = dev.fude.markdown.BlockViewState()

        rule.setContent {
            MarkdownEditor(state = state, view = view)
        }
        rule.waitForIdle()

        assertFalse(view.isSource(HEADING_START), "a block is rendered until something says otherwise")
        assertEquals(listOf("{{alice}}"), highlightedHostText(document, view), "the fixture has something to lose")

        val toggled = view.toggle(HEADING_START)
        rule.waitForIdle()

        assertEquals(dev.fude.markdown.RenderMode.SOURCE, toggled, "toggle returns the mode it switched to")
        assertTrue(view.isSource(HEADING_START), "and the state records it")
        assertEquals(0, view.collisions, "toggling one block collides with nothing")

        // The join, not just the state. Everything above would pass if the composable
        // were reading a BlockViewState of its own and ignoring this one -- the host
        // would be toggling a copy. What proves otherwise is that the *rendering*
        // changes, driven by the very instance the host holds.
        assertEquals(
            emptyList(),
            highlightedHostText(document, view),
            "the heading's decoration is gone, so the host's toggle reached the renderer",
        )

        view.toggle(HEADING_START)
        rule.waitForIdle()
        assertEquals(
            listOf("{{alice}}"),
            highlightedHostText(document, view),
            "and it comes back on a second toggle",
        )
    }

    /**
     * Per-block view state survives an edit above it, because [ToggleCoordinator] shifts
     * the keys. Asserted through the public API here so the guarantee is stated where a
     * host would look for it, rather than only in `BlockToggleTest` against the type in
     * isolation.
     */
    @Test
    fun aHostSuppliedViewStateFollowsItsBlockAcrossAnEditAboveIt() {
        val document = "# Heading\n\nA paragraph {{alice}}.\n"
        val state = EditorState.of(document)
        val view = dev.fude.markdown.BlockViewState()

        rule.setContent {
            MarkdownEditor(state = state, view = view)
        }
        rule.waitForIdle()

        val paragraphStart = document.indexOf("A paragraph")
        view.toggle(paragraphStart)
        rule.waitForIdle()
        assertTrue(view.isSource(paragraphStart))

        // Type at the very start, pushing the paragraph down by one character.
        state.applyEdit(document.replaceRange(0, 0, "x"), state.selection)
        rule.onNodeWithTag("fude-editor").performTextReplacement("x$document")
        rule.waitForIdle()

        val moved = paragraphStart + 1
        assertTrue(
            view.isSource(moved),
            "the toggle followed its block to $moved rather than staying at the stale offset",
        )
        assertFalse(view.isSource(paragraphStart), "and the old offset is no longer claimed")
    }

    /**
     * Loading another note clears the toggles — but the host has to do it.
     *
     * Keys are block start offsets, so every one is stale against a different document.
     * The composable cannot detect the swap: an edit and a swap both arrive as "the text
     * changed", and only the host knows it loaded something else. `ToggleCoordinator`
     * handles the edit case; the swap is documented as the host's call in the `view`
     * parameter's KDoc. This pins the mechanism that call relies on.
     */
    @Test
    fun clearingTheViewStateDropsEveryToggle() {
        val view = dev.fude.markdown.BlockViewState()
        view.toggle(0)
        view.toggle(12)
        assertEquals(setOf(0, 12), view.sourceBlocks(), "two blocks are showing source")

        view.clear()

        assertTrue(view.isDefault, "and after a clear there is no per-block state at all")
        assertEquals(emptySet(), view.sourceBlocks())
        assertFalse(view.isSource(0), "a stale offset no longer claims anything")
        assertFalse(view.isSource(12))
    }

    /**
     * The default is a working value, not a trap.
     *
     * A host that does not care about source mode passes nothing and gets a correct
     * instance. What it must not get is a compile error, which is why `view` carries a
     * default rather than being required.
     */
    @Test
    fun omittingTheViewStateStillRenders() {
        rule.setContent {
            MarkdownEditor(state = EditorState.of("# Heading\n\nBody.\n"))
        }
        rule.onNodeWithTag("fude-editor").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------
    // Host-supplied decorations, and click reporting.
    //
    // A host could receive decoration clicks but had no way to supply a decoration,
    // and no Decoration was ever constructed anywhere -- so onDecorationClick could
    // not fire. There were also two types for one idea: a public Decoration and an
    // internal DecorationSpan. Both are why these tests exist now rather than before.
    // ---------------------------------------------------------------------

    private fun spansFor(document: String, host: List<Decoration> = emptyList()) =
        decorationSpans(parse(document), dev.fude.markdown.BlockViewState(), host)

    @Test
    fun aHostDecorationIsEmittedAndIsVisible() {
        val document = "Hello {{alice}} here."
        val range = dev.fude.core.InlineRange(document.indexOf("{{alice}}"), document.indexOf("here"))
        val host = listOf(Decoration(range, androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF00FF00))))

        val emitted = spansFor(document, host)
        assertTrue(
            emitted.any { it.range == range && it.spanStyle == host.first().spanStyle },
            "the host's decoration is in the emitted list",
        )
    }

    @Test
    fun aHostDecorationIsEmittedLastSoItWinsOnOverlap() {
        // The precedence rule, stated as a property rather than prose: a host
        // decoration covering a range the library also decorates must come after it,
        // because Compose resolves a conflicting attribute in favour of the last span.
        val document = "A **bold** word."
        val word = document.indexOf("bold")
        val librarySpan = spansFor(document).first { it.range.start <= word && word < it.range.end }
        val host = listOf(
            Decoration(librarySpan.range, androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF00FF00))),
        )

        val emitted = spansFor(document, host)
        val libraryAt = emitted.indexOf(librarySpan)
        val hostAt = emitted.indexOfFirst { it.spanStyle == host.first().spanStyle }

        assertTrue(libraryAt >= 0, "the library decorates the bold run")
        assertTrue(hostAt > libraryAt, "and the host's decoration for the same range comes after it")
    }

    @Test
    fun hostDecorationsDoNotDisplaceTheOnesAroundThem() {
        val document = "A **bold** word and {{alice}}."
        val plain = spansFor(document)
        val withHost = spansFor(document, listOf(Decoration(dev.fude.core.InlineRange(0, 1))))

        assertEquals(
            plain.size + 1,
            withHost.size,
            "a host decoration is additive; it replaces nothing it does not overlap",
        )
    }

    @Test
    fun decorationAtReportsTheTopmostDecorationUnderAnOffset() {
        val outer = Decoration(dev.fude.core.InlineRange(0, 10))
        val inner = Decoration(dev.fude.core.InlineRange(4, 6))

        assertEquals(outer, decorationAt(listOf(outer), 2), "only one candidate")
        assertEquals(
            inner,
            decorationAt(listOf(outer, inner), 5),
            "with two overlapping, the later one wins -- the one drawn on top",
        )
        assertEquals(
            outer,
            decorationAt(listOf(outer, inner), 1),
            "and outside the inner one, the outer is still the answer",
        )
        assertNull(decorationAt(listOf(outer, inner), 10), "past the end of both")
        assertNull(decorationAt(emptyList(), 0), "no decorations, no answer")
    }

    @Test
    fun aHalfOpenRangeReportsTheDecorationToItsRightOnTheBoundary() {
        val left = Decoration(dev.fude.core.InlineRange(0, 4))
        val right = Decoration(dev.fude.core.InlineRange(4, 8))

        assertEquals(left, decorationAt(listOf(left, right), 3), "the last offset of the left run")
        assertEquals(right, decorationAt(listOf(left, right), 4), "and the first offset of the right one")
    }

}
