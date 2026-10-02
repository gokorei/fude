package dev.fude.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.fude.core.TextRange as CoreTextRange
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
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
}
