package dev.fude.demo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.fude.editor.EditorConfig
import dev.fude.editor.EditorState
import dev.fude.editor.MarkdownEditor
import dev.fude.syntax.BlockContext
import dev.fude.syntax.BlockMatch
import dev.fude.syntax.SyntaxExtension

/**
 * A desktop host for Fude.
 *
 * Exists to answer a question tests cannot: does the editor *feel* right when a
 * person drives it? The automated tests prove spans are rendered and text is
 * preserved; they cannot say whether the caret lands where the eye expects.
 *
 * Also the worked example the extension-point ticket asks for — `{{mention}}`
 * syntax the library has never heard of, registered by the host and resolved by
 * the host.
 */
internal class MentionSyntax : SyntaxExtension {
    override val id: String = "mention"
    override val priority: Int = 10

    val resolved: MutableList<String> = mutableListOf()

    override fun recogniseInline(context: BlockContext): List<dev.fude.core.InlineRange> {
        val text = context.content.toString()
        val matches = mutableListOf<dev.fude.core.InlineRange>()
        var open = text.indexOf("{{")
        while (open >= 0) {
            val close = text.indexOf("}}", open + 2)
            if (close < 0) break
            val body = text.substring(open + 2, close)
            if (body.isNotEmpty() && !body.any { it.isWhitespace() }) {
                matches += dev.fude.core.InlineRange(context.range.start + open, context.range.start + close + 2)
            }
            open = text.indexOf("{{", close + 2)
        }
        return matches
    }

    fun resolve(range: dev.fude.core.InlineRange, text: String): String {
        val handle = text.substring(range.start, range.end).removeSurrounding("{{", "}}")
        resolved += handle
        return handle
    }
}

/** A block construct the library has no built-in knowledge of. */
/**
 * The demo's block-level dialect: `> [!note]` is a callout, not a block quote.
 *
 * Internal rather than private so `CalloutSyntaxTest` can drive the real class
 * through the real parser. A demo construct nothing can reach is exactly the thing
 * that silently stops working.
 */
internal class CalloutSyntax : SyntaxExtension {
    override val id: String = "callout"

    override fun recogniseBlocks(context: BlockContext): List<BlockMatch> {
        val text = context.content.toString()
        if (!text.startsWith("> [!note]")) return emptyList()
        return listOf(
            BlockMatch(
                range = dev.fude.core.InlineRange(context.range.start, context.range.start + text.length),
                ownsTerminator = false,
            ),
        )
    }
}

internal val sampleDocument = """
    # Fude

    A live-preview Markdown editor. Type Markdown and the formatted result
    appears in place, while the raw syntax stays editable.

    ## What to try

    Type **bold**, *italic*, `code` or a [[Wikilink]] into a paragraph.

    Mention someone with {{double braces}} — the library does not know what a
    mention is; this host does.

    > [!note]
    > This block came from a host extension.

    - A list item
        - nested three levels deep
            - and here is a fourth
    - Another item with a [link](https://example.com)

    1. Ordered
    2. Items

    | Block | View | Notes |
    |-------|------|-------|
    | `abc` | **R** | toggled per block |
    | `def` | *S* | not the whole note |

    ```kotlin
    // Nothing in here is parsed as Markdown.
    val heading = "# not a heading"
    val link = "[[not a wikilink]]"
    ```

    ## Unicode

    Emoji ZWJ: 👨‍👩‍👧‍👦 · flag: 🇯🇵 · skin tone: 👋🏽
    Combining marks: é à ö ñ
    CJK: 日本語のテキスト、中文字符、한국어
    RTL: مرحبا بالعالم
""".trimIndent()

/**
 * Loads the document to edit.
 *
 * `FUDE_DEMO_FILE`, or the `fude.demo.file` system property, names a path — so the
 * demo can be pointed at real Markdown, a file pulled out of a running Tanseki
 * say, instead of only the sample baked in below. The sample exercises syntax on
 * purpose; a real file is how you find out what the editor does with the syntax
 * nobody thought to put in a fixture.
 *
 * Fails loudly rather than falling back to the sample. A demo that silently opens
 * the wrong document costs more time than one that refuses to open.
 */
private fun loadDocument(): String {
    val fromEnv = System.getenv("FUDE_DEMO_FILE")
    val fromProperty = System.getProperty("fude.demo.file")
    val path = fromEnv ?: fromProperty ?: return sampleDocument

    val origin = if (fromEnv != null) "FUDE_DEMO_FILE" else "-Dfude.demo.file"
    val file = java.io.File(path)
    require(file.isFile) { "$origin is not a file: $path" }
    return file.readText()
}

@Composable
private fun App() {
    val mention = remember { MentionSyntax() }
    var status by remember { mutableStateOf("Ready.") }
    var lastText by remember { mutableStateOf("") }

    val state = remember { EditorState.of(loadDocument()) }

    Column(modifier = Modifier.fillMaxSize()) {
        MarkdownEditor(
            state = state,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            config = EditorConfig(textStyle = TextStyle(fontSize = 15.sp)),
            syntaxExtensions = listOf(mention, CalloutSyntax()),
            onChange = { lastText = it },
            onDecorationClick = { decoration ->
                // Resolution is the host's job, deliberately.
                val handle = mention.resolve(decoration.range, lastText)
                status = "Resolved mention: $handle"
            },
        )
        // The status line is the host's, not the library's.
        StatusBar(text = status)
    }
}

@Composable
private fun StatusBar(text: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        androidx.compose.foundation.text.BasicText(text = text)
    }
}

/**
 * Runs the desktop host, or one keystroke measurement when `FUDE_SWEEP_LINES` is set.
 *
 * The measurement needs a real window: its whole point is that the headless harness
 * rasterises on the CPU and folds every phase into one number, neither of which is
 * true of a window on a user's screen.
 *
 * One configuration per process, driven by `scripts/keystroke_sweep.sh`. Sweeping
 * from inside a single window was tried and could not sequence its variants without
 * restarting the effect that was doing the measuring.
 */
fun main() {
    val sweepLines = System.getenv("FUDE_SWEEP_LINES")?.trim()?.toIntOrNull()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = if (sweepLines != null) "Fude — measuring" else "Fude — live-preview Markdown",
            state = rememberWindowState(width = 1000.dp, height = 760.dp),
        ) {
            if (sweepLines != null) {
                SweepHarness(
                    lines = sweepLines,
                    variant = Variant.parse(System.getenv("FUDE_SWEEP_VARIANT")),
                    onFinished = ::exitApplication,
                )
            } else {
                App()
            }
        }
    }
}
