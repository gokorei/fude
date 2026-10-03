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
 *
 * For [SyntaxExtension.recogniseInline] the range is the inline run being scanned.
 * For [SyntaxExtension.recogniseBlocks] it is the *candidate* block — the run of
 * non-blank lines starting where a block begins. An extension returning a match
 * narrows that candidate to the construct it actually claims.
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
     * Asked **before** the library decides what a block is, so a match wins over
     * every built-in rule. That precedence is the point: a callout is a block quote
     * to Markdown, and a host can only turn `> [!note]` into a callout if it is
     * allowed to claim a block the library would otherwise have read as a quote. The
     * cost of that choice is that a badly-written extension can shadow Markdown
     * wholesale, which is the trade a host opts into by registering one.
     *
     * [context] spans the candidate block — the run of non-blank lines starting where
     * a block begins — not the whole document, so an extension can see a
     * multi-line construct without scanning the file. Return a match only if the
     * construct is really there: returning one for every candidate would mean this
     * function, not the parser, decides where blocks are.
     *
     * The returned range is authoritative twice over: it is the block's extent, and
     * it is where the scanner resumes. Narrow it to claim part of the candidate and
     * the rest is parsed as Markdown.
     *
     * [BlockMatch.ownsTerminator] says whether the closing delimiter is inside the
     * returned range. When it is `false` the library resumes past the end of that
     * line, so a construct whose closing delimiter you deliberately excluded is
     * still consumed rather than being rescanned and matched again.
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
    public val extensionId: String,
    public val range: InlineRange,
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
): List<ResolvedMatch> =
    resolveOverlaps(candidates) { it }
        .map { (extension, range) -> ResolvedMatch(extension.id, range) }

/** A block match plus the extension that claimed it. */
public data class ResolvedBlockMatch(
    public val extensionId: String,
    public val match: BlockMatch,
)

/**
 * Resolves overlapping *block* matches, by the same rule as [resolveMatches]: longest
 * match first, then priority, then id.
 *
 * Block matches need their own entry point only because they carry a second field;
 * the ordering itself is shared, so an extension cannot behave differently at block
 * level than at inline level.
 */
public fun resolveBlockMatches(
    candidates: List<Pair<SyntaxExtension, BlockMatch>>,
): List<ResolvedBlockMatch> =
    resolveOverlaps(candidates) { it.range }
        .map { (extension, match) -> ResolvedBlockMatch(extension.id, match) }

/**
 * Drops overlapping candidates, most specific first, and returns the survivors with
 * their extensions in document order.
 *
 * Both resolution rules live here so the two cannot drift: same length order, same
 * priority tiebreak, same final sort.
 */
private fun <M> resolveOverlaps(
    candidates: List<Pair<SyntaxExtension, M>>,
    rangeOf: (M) -> InlineRange,
): List<Pair<SyntaxExtension, M>> {
    val sorted = candidates.sortedWith(
        compareByDescending<Pair<SyntaxExtension, M>> { (_, candidate) ->
            rangeOf(candidate).end - rangeOf(candidate).start
        }
            .thenByDescending { (extension, _) -> extension.priority }
            .thenBy { (extension, _) -> extension.id },
    )
    val accepted = mutableListOf<Pair<SyntaxExtension, M>>()
    for (candidate in sorted) {
        val range = rangeOf(candidate.second)
        val clashes = accepted.any { rangeOf(it.second).let { r -> r.start < range.end && range.start < r.end } }
        if (!clashes) accepted += candidate
    }
    return accepted.sortedBy { rangeOf(it.second).start }
}
