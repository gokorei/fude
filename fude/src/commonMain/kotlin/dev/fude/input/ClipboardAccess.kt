package dev.fude.input



/**
 * Clipboard, expressed as source text.
 *
 * The library's job ends at "give me Markdown, or take Markdown". Anything about
 * HTML, RTF, MIME types or platform handles is the host's, because that is where
 * the platform APIs live and because a library that owned them could not be
 * tested off-device.
 *
 * Rich text in, Markdown out is handled by the host pasting
 * [ClipboardPayload.markdown]. Fude supplies no HTML parser and takes no
 * responsibility for converting one.
 */
public interface ClipboardAccess {
    /** The current selection as Markdown. */
    public fun copy(text: String): Unit

    /** The current selection, removed. */
    public fun cut(text: String): Unit

    /**
     * Markdown from the clipboard, or null when there is none.
     *
     * Always source text, never pre-rendered output: pasting rendered Markdown
     * back as source is what turns a round trip into a document that grows
     * formatting nobody typed.
     */
    public fun paste(): ClipboardPayload?
}

/** What a clipboard read produced. */
public data class ClipboardPayload(
    /** Markdown source, always. */
    val markdown: String,
    /** Whether the source came from a rich-text source and may need normalising. */
    val wasRichText: Boolean = false,
) {
    public companion object {
        public val EMPTY: ClipboardPayload = ClipboardPayload("")
    }
}
