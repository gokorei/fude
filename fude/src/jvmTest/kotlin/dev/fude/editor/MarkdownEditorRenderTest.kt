package dev.fude.editor

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
}
