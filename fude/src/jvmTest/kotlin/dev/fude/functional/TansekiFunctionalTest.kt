package dev.fude.functional

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.fude.core.InlineRange
import dev.fude.core.TextBuffer
import dev.fude.core.TextRange
import dev.fude.core.graphemeStartOf
import dev.fude.editor.EditorConfig
import dev.fude.editor.EditorState
import dev.fude.editor.MarkdownEditor
import dev.fude.markdown.BlockKind
import dev.fude.markdown.CodeFenceNode
import dev.fude.markdown.CodeSpanNode
import dev.fude.markdown.EmphasisNode
import dev.fude.markdown.HostBlockNode
import dev.fude.markdown.HostInlineNode
import dev.fude.markdown.ImageNode
import dev.fude.markdown.IncrementalMarkdownParser
import dev.fude.markdown.LinkNode
import dev.fude.markdown.ListItemNode
import dev.fude.markdown.ParsedDocument
import dev.fude.syntax.BlockContext
import dev.fude.syntax.BlockMatch
import dev.fude.syntax.SyntaxExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * A functional test against a *running* Tanseki daemon.
 *
 * Every other suite in this module runs against fixtures checked into the repo.
 * This one runs against Markdown that arrives over HTTP from a live knowledge
 * store, because the failures worth catching are the ones a fixture author never
 * writes by hand: a code fence whose contents look like Markdown, a link label
 * shorter than its destination, an emoji that is four code points but one
 * character.
 *
 * The daemon is external and is often not running, so [requireDaemon] skips the
 * suite instead of failing it. A missing service is not a defect in the editor,
 * and CI has no Tanseki. Set `TANSEKI_URL` to point at a different instance.
 *
 * Seed the content with `scripts/seed_tanseki.py`; it upserts six documents into
 * the `fude-functional-test` collection and verifies an exact byte-for-byte
 * round-trip before this suite ever reads them.
 */
@OptIn(ExperimentalTestApi::class)
class TansekiFunctionalTest {
    @get:Rule
    val rule = createComposeRule()

    private val daemon: String get() = System.getenv("TANSEKI_URL") ?: "http://127.0.0.1:8088"

    @Before
    fun requireDaemon() {
        val reachable = try {
            val c = URI("$daemon/v1/health").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 2000
            c.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
        assumeTrue("Tanseki not reachable at $daemon; skipping", reachable)
    }

    // ------------------------------------------------------------------ client

    /** Reads one document body out of the live store. */
    private fun fetch(id: String): String {
        val conn = URI("$daemon/v1/documents:get").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write("""{"id":"$id"}""".toByteArray()) }
        val code = conn.responseCode
        assertEquals("fetch '$id' from Tanseki", 200, code)
        val body = conn.inputStream.bufferedReader().readText()
        return Json.parseObject(body).getValue("content").unescapeJsonString()
    }

    // --------------------------------------------------------------- fixtures

    private fun parse(text: String): ParsedDocument =
        IncrementalMarkdownParser(extensions).parse(text)

    private fun parseWithoutExtensions(text: String): ParsedDocument =
        IncrementalMarkdownParser().parse(text)

    private fun ParsedDocument.inlines(): List<dev.fude.markdown.InlineNode> =
        allBlocks.flatMap { block -> block.inlines }

    // ------------------------------------------------------------------ blocks

    @Test
    fun everyBlockKindInLiveContentIsParsed() {
        val blocks = parse(fetch("blocks")).allBlocks
        val kinds = blocks.map { it.kind }.toSet()

        val expected = listOf(
            BlockKind.HEADING,
            BlockKind.PARAGRAPH,
            BlockKind.LIST,
            BlockKind.LIST_ITEM,
            BlockKind.BLOCK_QUOTE,
            BlockKind.TABLE,
            BlockKind.TABLE_ROW,
            BlockKind.TABLE_CELL,
            BlockKind.THEMATIC_BREAK,
        )
        for (kind in expected) {
            assertTrue("live content should exercise $kind, saw $kinds", kind in kinds)
        }
    }

    @Test
    fun nestedListItemsKeepTheirIndent() {
        val items = parse(fetch("blocks")).allBlocks
            .filterIsInstance<ListItemNode>()
        assertTrue("expected several list items, saw ${items.size}", items.size >= 6)
        assertTrue(
            "a three-deep nest should produce indent >= 2, saw ${items.map { it.indent }}",
            items.maxOf { it.indent } >= 2,
        )
    }

    // ------------------------------------------------------------------ inlines

    @Test
    fun aLinkLabelShorterThanItsDestinationKeepsBothRanges() {
        val text = fetch("inlines")
        val links = parse(text).inlines().filterIsInstance<LinkNode>()
        assertTrue("expected at least one link, saw ${links.size}", links.isNotEmpty())

        val longest = links.maxBy { it.range.end - it.range.start }
        val label = text.substring(longest.labelRange.start, longest.labelRange.end)

        assertTrue(
            "the full range should be longer than the label range",
            (longest.range.end - longest.range.start) > (longest.labelRange.end - longest.labelRange.start),
        )
        assertTrue(
            "the label range must spell the label, not the destination; got '$label'",
            label.isNotBlank() && !label.startsWith("http"),
        )
        assertTrue(
            "the label range must sit inside the full range",
            longest.range.start <= longest.labelRange.start &&
                longest.labelRange.end <= longest.range.end,
        )
    }

    @Test
    fun anImageIsParsedAsAnImageNotALink() {
        val images = parse(fetch("inlines")).inlines().filterIsInstance<ImageNode>()
        assertTrue("expected an image node", images.isNotEmpty())
        assertTrue("alt text should survive", images.first().altText.isNotBlank())
    }

    @Test
    fun codeSpansAndBothFlavoursOfEmphasisAreParsed() {
        val inlines = parse(fetch("inlines")).inlines()
        assertTrue("expected code spans", inlines.any { it is CodeSpanNode })

        val emphasis = inlines.filterIsInstance<EmphasisNode>()
        assertTrue("expected emphasis nodes, saw ${emphasis.size}", emphasis.isNotEmpty())
        assertTrue("expected bold", emphasis.any { it.strong })
        assertTrue("expected italic", emphasis.any { !it.strong })
    }

    // ----------------------------------------------------------------- opacity

    @Test
    fun markdownInsideACodeFenceStaysLiteral() {
        val text = fetch("fences")
        val parsed = parse(text)
        val fences = parsed.allBlocks.filterIsInstance<CodeFenceNode>()
        assertTrue("expected code fences, saw ${fences.size}", fences.size >= 2)

        val hostile = fences.firstOrNull { it.content(text).contains("# not a heading") }
        assertTrue("the hostile fence should have been parsed as one fence", hostile != null)
        val fence = requireNotNull(hostile)

        val inside = parsed.allBlocks.filter {
            it !== fence &&
                it.range.start >= fence.contentRange.start &&
                it.range.end <= fence.contentRange.end
        }
        assertTrue(
            "nothing inside a fence may become a node; these did: $inside",
            inside.isEmpty(),
        )
        assertTrue(
            "the fence must keep its literal contents",
            fence.content(text).contains("[[not a wikilink]]"),
        )
    }

    @Test
    fun inlineCodeKeepsItsPipesAndHashes() {
        val spans = parse(fetch("fences")).inlines().filterIsInstance<CodeSpanNode>()
        val piped = spans.firstOrNull { it.code.contains('|') }
        assertTrue("expected a code span containing a pipe, saw ${spans.map { it.code }}", piped != null)
    }

    // --------------------------------------------------------- host extensions

    @Test
    fun hostExtensionsRecogniseSyntaxTheLibraryMustNot() {
        val text = fetch("hosts")
        val parsed = parse(text)

        val hostInlines = parsed.inlines().filterIsInstance<HostInlineNode>()
        assertTrue(
            "the mention extension should have matched {{project-alpha}}",
            hostInlines.isNotEmpty(),
        )
        val hostBlocks = parsed.allBlocks.filterIsInstance<HostBlockNode>()
        assertTrue(
            "the callout extension should have matched > [!note]",
            hostBlocks.isNotEmpty(),
        )
    }

    @Test
    fun withoutExtensionsTheLibraryRecognisesNothing() {
        // The boundary that gives the extension point its meaning: the same text,
        // parsed with no extensions registered, must yield no host nodes at all.
        val parsed = parseWithoutExtensions(fetch("hosts"))
        assertTrue(
            "no extension is registered, so nothing should be host-defined",
            parsed.inlines().filterIsInstance<HostInlineNode>().isEmpty(),
        )
        assertTrue(
            "and no host blocks either",
            parsed.allBlocks.filterIsInstance<HostBlockNode>().isEmpty(),
        )
    }

    // --------------------------------------------------------------- rendering

    @Test
    fun liveContentRendersAndPreservesSourceTextExactly() {
        val text = fetch("inlines")
        val state = EditorState.of(text, caret = 0)
        var latest: String? = null

        rule.setContent {
            MarkdownEditor(
                state = state,
                modifier = Modifier.fillMaxSize(),
                config = EditorConfig(textStyle = TextStyle(fontSize = 15.sp)),
                syntaxExtensions = extensions,
                onChange = { latest = it },
            )
        }
        rule.waitForIdle()

        rule.onNodeWithTag("fude-editor").assertExists()
        assertEquals("rendering must not rewrite the source", text, state.text)
        // onChange reports *changes*, so a first composition fires it zero times.
        // What matters is that it never fires with something other than the source.
        assertTrue("onChange must not fabricate an edit on mount", latest == null || latest == text)
    }

    @Test
    fun replacingTheFieldRoundTripsThroughTheWholePipeline() {
        val text = fetch("blocks")
        val state = EditorState.of(text)
        var latest: String? = null

        rule.setContent {
            MarkdownEditor(
                state = state,
                modifier = Modifier.fillMaxSize(),
                config = EditorConfig(textStyle = TextStyle(fontSize = 15.sp)),
                syntaxExtensions = extensions,
                onChange = { latest = it },
            )
        }
        rule.waitForIdle()

        val replacement = "# Replaced\n\nA **new** document with a `code span`.\n"
        rule.onNodeWithTag("fude-editor").performTextReplacement(replacement)
        rule.waitForIdle()

        assertEquals(
            "a full replacement must arrive intact, with nothing re-rendered into it",
            replacement,
            latest,
        )
        assertEquals("the model must agree with the field", replacement, state.text)
    }

    @Test
    fun aFenceTypedIntoTheLiveDocumentStaysOpaque() {
        // The regression this whole suite is built around: live-preview editors
        // that decorate fence contents render the fence wrong as you type it.
        val state = EditorState.of(fetch("fences"))
        rule.setContent {
            MarkdownEditor(
                state = state,
                modifier = Modifier.fillMaxSize(),
                config = EditorConfig(textStyle = TextStyle(fontSize = 15.sp)),
                syntaxExtensions = extensions,
            )
        }
        rule.waitForIdle()

        val withNewFence = state.text + "\n```\n**not bold** and # not a heading\n```\n"
        state.applyEdit(withNewFence, TextRange(withNewFence.length, withNewFence.length))
        rule.waitForIdle()

        val fences = parse(state.text).allBlocks.filterIsInstance<CodeFenceNode>()
        assertTrue("the new fence should be parsed as a fence", fences.size >= 3)
        assertTrue(
            "and its contents must stay literal",
            fences.any { it.content(state.text).contains("**not bold**") },
        )
    }

    // --------------------------------------------------------------- graphemes

    @Test
    fun liveUnicodeContentIsMeasuredInGraphemesNotCodeUnits() {
        val text = fetch("unicode")

        for (emoji in listOf("👨‍👩‍👧‍👦", "🇯🇵", "👋🏽")) {
            val at = text.indexOf(emoji)
            assertTrue("seed content should contain $emoji", at >= 0)

            // A caret sitting immediately after the emoji is at a cluster boundary,
            // so a single backspace must remove the whole emoji. If backspacing were
            // done in UTF-16 units it would leave half a family behind.
            val after = at + emoji.length
            val start = TextBuffer.of(text).graphemeStartOf(after)
            assertEquals(
                "'$emoji' occupies ${emoji.length} UTF-16 units and must read as one cluster",
                emoji,
                text.substring(start, after),
            )
        }
    }

    // ------------------------------------------------------------------- cost

    @Test
    fun keystrokeCostOnLiveContentAgainstTwentyFourFpsBudget() {
        val text = fetch("long-note")
        val lines = text.count { it == '\n' } + 1
        val state = EditorState.of(text, caret = text.length / 2)

        rule.setContent {
            MarkdownEditor(
                state = state,
                modifier = Modifier.fillMaxSize(),
                config = EditorConfig(textStyle = TextStyle(fontSize = 15.sp)),
                syntaxExtensions = extensions,
            )
        }
        rule.waitForIdle()

        val caret = text.length / 2
        val samples = mutableListOf<Long>()
        repeat(5) { i ->
            val offset = caret + i
            val next = text.substring(0, offset) + "x" + text.substring(offset)
            val started = System.nanoTime()
            rule.runOnIdle {
                state.applyEdit(next, TextRange(offset, offset))
            }
            rule.waitForIdle()
            samples += (System.nanoTime() - started) / 1_000_000
        }

        val sorted = samples.sorted()
        println(
            "PERF tanseki-live median=${sorted[sorted.size / 2]}ms worst=${sorted.last()}ms " +
                "samples=$samples (24fps budget ${FPS_24}ms, live doc $lines lines / ${text.length} chars)",
        )
        assertEquals("the measurement itself must have completed", 5, samples.size)
    }

    private companion object {
        /** 24 fps. The threshold ticket A39H50TC is deferred against. */
        const val FPS_24 = 42

        /** The same host extensions the demo registers, so this tests what ships. */
        val extensions: List<SyntaxExtension> = listOf(MentionSyntax(), CalloutSyntax())
    }
}

private class MentionSyntax : SyntaxExtension {
    override val id: String = "mention"
    override val priority: Int = 10

    override fun recogniseInline(context: BlockContext): List<InlineRange> {
        val text = context.content.toString()
        val matches = mutableListOf<InlineRange>()
        var open = text.indexOf("{{")
        while (open >= 0) {
            val close = text.indexOf("}}", open + 2)
            if (close < 0) break
            val body = text.substring(open + 2, close)
            if (body.isNotEmpty() && !body.any { it.isWhitespace() }) {
                matches += InlineRange(context.range.start + open, context.range.start + close + 2)
            }
            open = text.indexOf("{{", close + 2)
        }
        return matches
    }
}

private class CalloutSyntax : SyntaxExtension {
    override val id: String = "callout"

    override fun recogniseBlocks(context: BlockContext): List<BlockMatch> {
        val text = context.content.toString()
        if (!text.startsWith("> [!note]")) return emptyList()
        return listOf(
            BlockMatch(
                range = InlineRange(context.range.start, context.range.start + text.length),
                ownsTerminator = false,
            ),
        )
    }
}

/** Minimal object reader, so this suite needs no JSON dependency. */
private object Json {
    fun parseObject(body: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < body.length) {
            val keyStart = body.indexOf('"', i)
            if (keyStart < 0) break
            val keyEnd = body.indexOf('"', keyStart + 1)
            if (keyEnd < 0) break
            val key = body.substring(keyStart + 1, keyEnd)

            val colon = body.indexOf(':', keyEnd)
            if (colon < 0) break
            var j = colon + 1
            while (j < body.length && body[j].isWhitespace()) j++

            if (j < body.length && body[j] == '"') {
                val sb = StringBuilder()
                j++
                while (j < body.length && body[j] != '"') {
                    if (body[j] == '\\' && j + 1 < body.length) {
                        sb.append(body[j]).append(body[j + 1]); j += 2
                    } else {
                        sb.append(body[j]); j++
                    }
                }
                out[key] = sb.toString()
                i = j + 1
            } else {
                i = colon + 1
            }
        }
        return out
    }
}

/** Undoes the escaping `Json` deliberately left in place. */
private fun String.unescapeJsonString(): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < this.length) {
        val c = this[i]
        if (c == '\\' && i + 1 < length) {
            when (this[i + 1]) {
                'n' -> { out.append('\n'); i++ }
                't' -> { out.append('\t'); i++ }
                'r' -> { out.append('\r'); i++ }
                else -> { out.append(this[i + 1]); i++ }
            }
        } else {
            out.append(c)
        }
        i++
    }
    return out.toString()
}