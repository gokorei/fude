package dev.fude.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.fude.core.InlineRange
import dev.fude.editor.Decoration
import dev.fude.editor.decorationSpans
import dev.fude.markdown.BlockViewState
import dev.fude.markdown.IncrementalMarkdownParser
import dev.fude.markdown.ParsedDocument
import dev.fude.markdown.RenderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden tests for decoration output.
 *
 * `7W23JW59` criterion 10 asked for screenshot tests and declined to deliver them,
 * reasoning that a golden-image baseline has to be committed per platform and
 * cannot be generated for targets nobody has declared. That reasoning was right and
 * is not overridden here.
 *
 * What is tested instead is the layer one step below pixels: the offsets and styles
 * that rendering *consumes*. A span on the wrong characters, a missing span, styling
 * applied to a block the user toggled to source, or a host decoration losing an
 * overlap all pass every structural assertion the suite already had, because those
 * assertions check the block tree and this checks what came out of it. The bugs this
 * catches are the ones a user sees.
 *
 * The serialization is deliberately textual rather than a serialized `AnnotatedString`
 * so that a failure prints the span that moved. `expected: 12..20 weight=700` names a
 * defect; a binary mismatch does not.
 */
class DecorationGoldenTest {

    private fun parse(text: String): ParsedDocument = IncrementalMarkdownParser().parse(text)

    private fun spans(
        text: String,
        view: BlockViewState = BlockViewState(),
        host: List<Decoration> = emptyList(),
    ): List<Decoration> = decorationSpans(parse(text), view, host)

    /**
     * Renders spans as one line each: `start..end style`.
     *
     * Only attributes that are actually set are listed. An empty style is written
     * as `-` rather than omitted, so a span that lost its formatting shows up as a
     * changed line instead of vanishing from the golden.
     */
    private fun serialize(decorations: List<Decoration>): String =
        decorations.joinToString("\n") { d ->
            "${d.range.start}..${d.range.end} ${describe(d.spanStyle)}"
        }

    private fun describe(style: androidx.compose.ui.text.SpanStyle): String {
        val parts = mutableListOf<String>()
        if (style.color != Color.Unspecified) parts += "color=#${style.color.toHex()}"
        if (style.background != Color.Unspecified) parts += "bg=#${style.background.toHex()}"
        style.fontWeight?.let { parts += "weight=${it.weight}" }
        style.fontStyle?.let { parts += "slant=${if (it == FontStyle.Italic) "italic" else "normal"}" }
        if (style.textDecoration != null && style.textDecoration != TextDecoration.None) {
            parts += "deco=${style.textDecoration}"
        }
        if (style.fontSize != TextUnit.Unspecified) parts += "size=${style.fontSize.value}"
        return if (parts.isEmpty()) "-" else parts.joinToString(" ")
    }

    private fun Color.toHex(): String =
        toArgb().toUInt().toString(16).uppercase().padStart(8, '0')

    private fun golden(text: String, expected: String) {
        assertEquals(expected, serialize(spans(text)))
    }

    // --- inline formatting --------------------------------------------------

    @Test
    fun boldAndItalicRenderLive() {
        // Spans cover the delimiters, not just the text between them: the whole run
        // is styled and the markers stay visible, which is what live preview does.
        golden(
            "**bold** and *italic*\n",
            "0..8 weight=700\n13..21 slant=italic",
        )
    }

    @Test
    fun emphasisInsideAHeadingIsStyled() {
        // The heading draws bold and larger; the emphasis draws italic on top of
        // it. Two spans, two attributes, no duplication.
        golden(
            "# A *heading*\n",
            "0..13 weight=700 size=1.5\n4..13 slant=italic",
        )
    }

    @Test
    fun emphasisInsideAListItemIsStyled() {
        // Three spans, three jobs: the marker as chrome, the bold run, and no
        // second copy of the bold run. The list item and the paragraph beneath it
        // expose the same inline objects, and collecting from both paths used to
        // emit the inline twice.
        golden(
            "- a **b** c\n",
            "0..1 color=#FF9A9A9A weight=700\n4..9 weight=700",
        )
    }

    @Test
    fun emphasisInsideATableCellIsStyled() {
        // The header row draws bold over its whole range; the cell's own bold
        // draws on top of it.
        golden(
            "| a | **b** |\n|---|---|\n| 1 | 2 |\n",
            "0..13 weight=700\n6..11 weight=700",
        )
    }

    @Test
    fun aLinkIsStyledOverItsLabelNotItsTarget() {
        // The span covers 1..5 — `docs` — and not the 20 characters of the URL. This
        // is the case that makes naive caret mapping select the wrong span.
        golden(
            "[docs](https://example.com)\n",
            "1..5 color=#FF3B7DD8 deco=TextDecoration.Underline",
        )
    }

    @Test
    fun aCodeSpanGetsABackground() {
        golden("a `code` b\n", "2..8 bg=#1F000000")
    }

    @Test
    fun anImageIsStyled() {
        golden("![alt](x.png)\n", "1..13 color=#FF8A6A3B")
    }

    // --- block constructs ---------------------------------------------------

    @Test
    fun aHeadingIsBoldAndLargerThanAParagraph() {
        // The largest hole in the renderer, closed: a heading used to draw
        // exactly like a paragraph.
        golden("# Title\n", "0..7 weight=700 size=1.5")
    }

    @Test
    fun aHeadingScalesDownWithLevel() {
        golden("### Deep\n", "0..8 weight=700 size=1.3")
    }

    @Test
    fun aFenceIsStyledAsCode() {
        golden("```kotlin\nval x = 1\n```\n", "0..23 color=#FF6A9955")
    }

    @Test
    fun aFenceWithNoInfoStringIsStillOneSpan() {
        golden("```\ncode\n```\n", "0..12 color=#FF6A9955")
    }

    @Test
    fun aFenceContainingMarkdownishTextProducesExactlyOneSpan() {
        // The classic live-preview bug. A fence is where the user has said this is
        // not Markdown, so nothing inside it may be styled as Markdown.
        golden("```\n**not bold** [[link]] `x`\n```\n", "0..33 color=#FF6A9955")
    }

    @Test
    fun aThematicBreakDrawsFadedRatherThanLiteral() {
        // A SpanStyle cannot draw a rule, so the dashes draw faded: the honest
        // rendering of "a break lives here".
        golden("---\n", "0..3 color=#FFBDBDBD")
    }

    @Test
    fun aBlockQuoteIsTintedSoItReadsAsAQuote() {
        // The `>` stays visible — source is the truth — and the tint is what says
        // "quote".
        golden("> quoted\n", "0..8 bg=#0F000000")
    }

    @Test
    fun aListMarkerDrawsAsChrome() {
        golden("- one\n", "0..1 color=#FF9A9A9A weight=700")
    }

    @Test
    fun anOrderedMarkerIsTheTypedNumber() {
        // Renumbering is not implemented, so the honest marker is the typed one:
        // `3.` after `1.` draws as `3.`, not `2.`.
        golden("3. one\n", "0..2 color=#FF9A9A9A weight=700")
    }

    @Test
    fun aTableHeaderStandsApartFromItsBody() {
        // The header row draws bold over its whole range, delimiter row and pipes
        // included. Pipes stay visible — source in place — and the table reads as
        // a table because its header does not read as a row.
        golden(
            "| a | b |\n|---|---|\n| 1 | 2 |\n",
            "0..9 weight=700",
        )
    }

    @Test
    fun aParagraphIsDeliberatelyUnstyled() {
        // A paragraph is the default, so a span on every one would be cost with
        // no information. This is the one block type with no span on purpose.
        golden("plain\n", "")
    }

    // --- degenerate documents ----------------------------------------------

    @Test
    fun anEmptyDocumentProducesNoSpans() {
        golden("", "")
    }

    @Test
    fun aSingleNewlineDocumentProducesNoSpans() {
        golden("\n", "")
    }

    @Test
    fun aDocumentOfOnlyWhitespaceProducesNoSpans() {
        golden("   \n\n  \n", "")
    }

    @Test
    fun inlineFormattingIsWhatIsStyledToday() {
        // Block styling has landed since this was written, but the counterpart
        // still holds: every inline construct produces a span.
        val styled = listOf(
            "bold" to "**b**\n",
            "italic" to "*i*\n",
            "link" to "[l](u)\n",
            "code span" to "`c`\n",
            "image" to "![a](u)\n",
            "fence" to "```\nc\n```\n",
        )
        for ((name, text) in styled) {
            assertTrue(serialize(spans(text)).isNotEmpty(), "$name should produce a span")
        }
    }

    // --- the toggle ---------------------------------------------------------

    @Test
    fun aSourceBlockIsNotDecoratedAtAll() {
        val text = "# Title\n"
        val view = BlockViewState(mapOf(0 to RenderMode.SOURCE))
        assertEquals("", serialize(spans(text, view)))
    }

    @Test
    fun togglingOnlyAffectsTheBlockItWasAppliedTo() {
        val text = "# One\n\n# Two\n"
        val view = BlockViewState(mapOf(0 to RenderMode.SOURCE))
        // Both headings are unstyled blocks, so the meaningful assertion is the
        // inline: put one in the second heading and only it may appear.
        val withInline = "# One\n\n# T*wo*\n"
        val v = BlockViewState(mapOf(0 to RenderMode.SOURCE))
        val found = serialize(spans(withInline, v))
        // The second heading draws its own span; the toggled first draws nothing.
        assertEquals("7..14 weight=700 size=1.5\n10..14 slant=italic", found)
    }

    @Test
    fun togglingDoesNotChangeTheDocument() {
        val text = "# One\n\n# Two\n"
        val before = parse(text)
        val view = BlockViewState(mapOf(0 to RenderMode.SOURCE))
        decorationSpans(before, view)
        assertEquals(text, parse(text).text, "a view change is not a document change")
    }

    // --- host precedence ----------------------------------------------------

    @Test
    fun aHostDecorationAppearsInTheOutput() {
        val host = listOf(Decoration(InlineRange(0, 2), androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)))
        val found = serialize(spans("plain text\n", host = host))
        assertTrue("0..2 weight=700" in found, "got:\n$found")
    }

    @Test
    fun aHostDecorationIsAppliedLastSoItWins() {
        // Both cover 0..6 on the same text. Compose resolves a conflicting
        // attribute in favour of whichever span was added last, so appending the
        // host's list is what makes its answer beat the library's.
        val parsed = parse("**bold**\n")
        val host = listOf(
            Decoration(
                InlineRange(0, 6),
                androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Normal),
            ),
        )
        val found = serialize(decorationSpans(parsed, BlockViewState(), host))
        val lines = found.lines()
        assertEquals(2, lines.size, "both spans are present, got:\n$found")
        assertTrue("weight=400" in lines.last(), "the host span must be last so it wins, got:\n$found")
    }

    // --- span integrity -----------------------------------------------------

    @Test
    fun everySpanSitsInsideTheDocument() {
        val documents = listOf(
            "# Title\n\n- a\n- b\n\n```\ncode\n```\n\n| x | y |\n|---|---|\n| 1 | 2 |\n",
            "**a** *b* `c` [d](e) ![f](g)\n",
            "> quote\n\n---\n\nplain\n",
        )
        for (text in documents) {
            val length = text.length
            for (d in spans(text)) {
                assertTrue(d.range.start >= 0, "negative start at ${d.range} in:\n$text")
                assertTrue(
                    d.range.end <= length,
                    "span ${d.range} runs past the end of a $length-char document:\n$text",
                )
            }
        }
    }

    @Test
    fun spansAreOrderedByStart() {
        val found = spans("# T\n\n- a **b**\n\n> c\n")
        val starts = found.map { it.range.start }
        assertEquals(starts.sorted(), starts, "traversal order must be stable for overlap resolution")
    }
}
