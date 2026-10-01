package dev.fude.syntax

import dev.fude.core.InlineRange

/**
 * A block-level construct Fude does not have built-in knowledge of.
 *
 * This is how a host handles something like frontmatter without the library
 * knowing that frontmatter exists. If the library understood it, the boundary
 * between "Markdown editor" and "one host's dialect" would have already leaked.
 */
public data class BlockMatch(
    val range: InlineRange,
    /**
     * How much of the matched range the construct owns.
     *
     * Fenced constructs own their terminator; a frontmatter block owns the
     * delimiters. This is the same decision Obsidian makes, and getting it wrong
     * either swallows the closing delimiter or leaves it in the body.
     */
    val ownsTerminator: Boolean = true,
)

/**
 * The block of source an extension is being asked about.
 *
 * Deliberately a value, and deliberately just text plus offsets: an extension
 * runs per frame and must be pure, or decoration would stop being a function of
 * state.
 */
public data class BlockContext(
    val text: CharSequence,
    /** The block's source range in the whole document. */
    val range: InlineRange,
    /**
     * The block's own text, which may differ from `text[range]` once the library
     * has decided how markers are treated.
     */
    val content: CharSequence = text.subSequence(range.start, range.end),
)

/**
 * A host-supplied dialect extension.
 *
 * Fude ships Markdown. A host that needs `[[wikilinks]]` registers recognition
 * here rather than forking the parser, which is the difference between a
 * reusable library and a Musubime component with a misleading name.
 *
 * Implementations must be pure. The library calls [recogniseInline] and
 * [recogniseBlocks] on every frame over the affected blocks; a recogniser that
 * reads mutable state makes rendering depend on more than the document, which
 * is the guarantee this whole design exists to provide.
 *
 * Resolution is a callback the host owns. The library never looks anything up,
 * never touches the network, and never resolves an identifier to anything — a
 * wikilink resolver belongs to the host, and putting one here would put Opal's
 * `DocId` in a library that is supposed not to know it exists.
 */
public interface SyntaxExtension {

    /** A stable identifier, used in precedence resolution, diagnostics and tests. */
    public val id: String

    /**
     * Priority when two extensions match overlapping ranges. Higher wins.
     *
     * Without a defined rule, adding an unrelated extension silently changes
     * rendering, which is the worst possible time to discover it.
     */
    public val priority: Int get() = 0

    /**
     * Recognises this extension's inline syntax within [context].
     *
     * @return matched source ranges, or an empty list when this extension does
     *   not apply here.
     */
    public fun recogniseInline(context: BlockContext): List<InlineRange> = emptyList()

    /**
     * Recognises a block-level construct within [context].
     *
     * The default is empty, so an extension that only adds inline syntax does not
     * have to think about blocks at all.
     */
    public fun recogniseBlocks(context: BlockContext): List<BlockMatch> = emptyList()
}

/**
 * A match plus the extension that produced it, kept for diagnostics.
 */
public data class ResolvedMatch(
    val extensionId: String,
    val range: InlineRange,
)

/**
 * Resolves overlapping matches from several extensions into a non-overlapping set.
 *
 * Longest match wins first, because a longer match is more specific and the user
 * is usually getting what they typed. Ties break on [SyntaxExtension.priority],
 * then on id, so the outcome is deterministic rather than dependent on list
 * ordering. Without a defined rule here, adding an unrelated extension silently
 * changes rendering — which is the worst possible moment to discover it.
 */
public fun resolveMatches(
    candidates: List<Pair<SyntaxExtension, InlineRange>>,
): List<ResolvedMatch> {
    val sorted = candidates.sortedWith(
        compareByDescending<Pair<SyntaxExtension, InlineRange>> { (_, range) -> range.end - range.start }
            .thenByDescending { (extension, _) -> extension.priority }
            .thenBy { (extension, _) -> extension.id },
    )
    val accepted = mutableListOf<ResolvedMatch>()
    for ((extension, range) in sorted) {
        val clashes = accepted.any { it.range.start < range.end && range.start < it.range.end }
        if (!clashes) accepted += ResolvedMatch(extension.id, range)
    }
    return accepted.sortedBy { it.range.start }
}
