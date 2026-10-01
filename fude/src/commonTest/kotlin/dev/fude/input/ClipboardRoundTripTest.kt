package dev.fude.input

import dev.fude.core.EditorState as CoreEditorState
import dev.fude.core.TextRange
import dev.fude.core.UndoStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Clipboard behaviour, against an in-memory clipboard.
 *
 * The real system clipboard cannot be exercised in a headless test reliably, so
 * the contract is pinned against a fake that a platform implementation must be
 * able to satisfy. That is the point: `SystemClipboard` is one implementation of
 * this contract, not the definition of it.
 *
 * Rich-text-to-Markdown conversion is deliberately **not** tested here, and the
 * reason matters: Fude supplies no HTML parser. `ClipboardPayload.wasRichText`
 * tells the host that a richer flavour existed so the host can convert structure
 * itself. A test asserting "HTML becomes clean Markdown" would be asserting
 * something the library does not do.
 */
class ClipboardRoundTripTest {
    private class FakeClipboard(initial: String? = null) : ClipboardAccess {
        var contents: String? = initial
        var copyCount = 0
        var cutCount = 0
        var richFlavourOffered = false

        override fun copy(text: String) {
            contents = text
            copyCount++
        }

        override fun cut(text: String) {
            contents = text
            cutCount++
        }

        override fun paste(): ClipboardPayload? =
            contents?.let { ClipboardPayload(it, wasRichText = richFlavourOffered) }
    }

    private fun handler(clipboard: ClipboardAccess, undo: UndoStack = UndoStack()) =
        KeyHandler(
            undo = undo,
            onClipboardCopy = clipboard::copy,
            onClipboardCut = clipboard::cut,
            onClipboardPaste = { clipboard.paste()?.markdown },
        )

    @Test
    fun plainTextRoundTrips() {
        val source = "# Heading\n\nbody **bold**"
        val clipboard = FakeClipboard(source)

        val pasted = handler(clipboard).handle(CoreEditorState.of("", 0), KeyEvent(EditingKey.PASTE))!!
        clipboard.copy(pasted.text.text)

        assertEquals(source, clipboard.contents, "paste then copy returns what was pasted")
    }

    @Test
    fun copyThenPastePreservesTheExactSource() {
        val clipboard = FakeClipboard()
        val original = "line one\n\n- item with `code`\n- [[Wikilink]]"
        val state = CoreEditorState.of(original, 0).selectRange(0, original.length)

        val afterCopy = handler(clipboard).handle(state, KeyEvent(EditingKey.COPY))!!
        assertEquals(original, clipboard.contents)
        assertEquals(original, afterCopy.text.text, "copying does not change the document")

        val pasted = handler(clipboard).handle(CoreEditorState.of("", 0), KeyEvent(EditingKey.PASTE))
        assertEquals(original, pasted?.text?.text, "the round trip is exact")
    }

    @Test
    fun pastedMarkdownArrivesAsSourceNotRenderedOutput() {
        val clipboard = FakeClipboard("**bold** and `code` and [[Wikilink]]")
        val pasted = handler(clipboard).handle(CoreEditorState.of("", 0), KeyEvent(EditingKey.PASTE))

        val text = pasted!!.text.text
        assertTrue(text.contains("**bold**"), "asterisks survive as source")
        assertTrue(text.contains("`code`"), "backticks survive as source")
        assertTrue(text.contains("[[Wikilink]]"), "the host dialect survives as source")
        assertTrue(!text.contains("bold and code"), "nothing was rendered into the text")
    }

    @Test
    fun cutRemovesTheSelectionAfterCopyingIt() {
        val clipboard = FakeClipboard()
        val state = CoreEditorState.of("hello world").selectRange(6, 11)
        val after = handler(clipboard).handle(state, KeyEvent(EditingKey.CUT))

        assertEquals("world", clipboard.contents)
        assertEquals("hello ", after?.text?.text)
        assertEquals(1, clipboard.cutCount)
    }

    @Test
    fun copyAcrossARenderedBoundaryStillCopiesSource() {
        // The selection spans a link's label and the text after it. Copying must
        // yield the Markdown the user typed, including the destination they never
        // saw rendered.
        val clipboard = FakeClipboard()
        val source = "see [docs](https://example.com) now"
        val labelEnd = source.indexOf("](") + 1
        val state = CoreEditorState.of(source, 0).selectRange(4, source.indexOf(" now"))

        handler(clipboard).handle(state, KeyEvent(EditingKey.COPY))
        assertEquals("[docs](https://example.com)", clipboard.contents)
    }

    @Test
    fun aCollapsedCaretCopiesNothing() {
        val clipboard = FakeClipboard()
        handler(clipboard).handle(CoreEditorState.of("hello", 2), KeyEvent(EditingKey.COPY))
        assertNull(clipboard.contents, "a caret is not a selection")
    }

    @Test
    fun pastingIntoAMiddleSelectionReplacesIt() {
        val clipboard = FakeClipboard("X")
        val state = CoreEditorState.of("hello world", 0).selectRange(0, 5)
        val after = handler(clipboard).handle(state, KeyEvent(EditingKey.PASTE))
        assertEquals("X world", after?.text?.text)
    }

    @Test
    fun anEmptyClipboardIsANoOp() {
        val clipboard = FakeClipboard(null)
        val state = CoreEditorState.of("abc", 3)
        val after = handler(clipboard).handle(state, KeyEvent(EditingKey.PASTE))
        assertEquals("abc", after?.text?.text)
    }

    @Test
    fun aRichFlavourIsReportedSoTheHostCanDecide() {
        val clipboard = FakeClipboard("flattened text")
        clipboard.richFlavourOffered = true

        val payload = clipboard.paste()!!
        assertTrue(payload.wasRichText, "the host is told a richer source existed")
        assertEquals("flattened text", payload.markdown)
        // And the library does nothing about it: converting HTML is the host's job,
        // because Fude has no HTML parser and should not grow one.
    }

    @Test
    fun aHostCanConvertRichTextItself() {
        // What the host is expected to do, demonstrated: take the richer flavour,
        // convert it, and hand back Markdown.
        val clipboard = object : ClipboardAccess {
            override fun copy(text: String) = Unit
            override fun cut(text: String) = Unit
            override fun paste(): ClipboardPayload = ClipboardPayload(
                markdown = "# Heading\n\nA paragraph.",
                wasRichText = true,
            )
        }
        val after = handler(clipboard).handle(CoreEditorState.of("", 0), KeyEvent(EditingKey.PASTE))
        assertEquals("# Heading\n\nA paragraph.", after?.text?.text)
    }

    @Test
    fun pasteAtTheCaretInsertsAtTheCaret() {
        val clipboard = FakeClipboard("INSERTED")
        val state = CoreEditorState.of("ab", 1)
        val after = handler(clipboard).handle(state, KeyEvent(EditingKey.PASTE))
        assertEquals("aINSERTEDb", after?.text?.text)
        assertEquals(9, after?.caret)
    }
}
