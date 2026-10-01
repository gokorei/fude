package dev.fude.platform

import dev.fude.input.ClipboardAccess
import dev.fude.input.ClipboardPayload
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * Desktop clipboard.
 *
 * Reads text as-is. The one non-obvious decision: **plain text wins over rich
 * text**. Pasting from a word processor normally yields `text/html`, and taking
 * it produces a wall of inline styles; taking the plain flavour gives clean
 * Markdown that the user can then edit. Rich text is a better source for
 * structure when a host wants it, and that is the host's call to make, not this
 * implementation's.
 *
 * `java.awt` is allowed here and only here: this is a platform source set, and
 * `commonMain` is forbidden from importing `java.*` by the architecture check.
 */
public class SystemClipboard : ClipboardAccess {

    private val clipboard: java.awt.datatransfer.Clipboard
        get() = Toolkit.getDefaultToolkit().systemClipboard

    override fun copy(text: String) {
        clipboard.setContents(StringSelection(text), null)
    }

    override fun cut(text: String) {
        copy(text)
    }

    override fun paste(): ClipboardPayload? {
        val board = try {
            clipboard
        } catch (t: IllegalStateException) {
            // Locked by another process. Not worth crashing a paste over; the caller
            // treats null as "nothing there".
            return null
        }

        val text = try {
            board.getData(DataFlavor.stringFlavor) as? String
        } catch (t: Exception) {
            // The owner is gone, or the flavour is unreadable.
            null
        } ?: return null

        // Report whether a richer flavour was on offer, so a host can decide to
        // convert structure rather than take the flattened text.
        val hadRichText = try {
            board.isDataFlavorAvailable(DataFlavor("text/html"))
        } catch (t: Exception) {
            false
        }
        return ClipboardPayload(markdown = text, wasRichText = hadRichText)
    }
}
