package dev.fude.syntax

import dev.fude.core.InlineRange

/**
 * A deliberately fake dialect, used to exercise the extension point.
 *
 * The library's own tests use this rather than a real one. That is the point of
 * an extension point: Fude can be tested against syntax it has never heard of,
 * with no vault, no host store, and no `[[wikilinks]]` anywhere in the tree. An
 * extension point with one real consumer and no tests is an unverified API.
 */
public class MentionSyntax(
    override val id: String = "mention",
    override val priority: Int = 0,
    private val open: String = "{{",
    private val close: String = "}}",
) : SyntaxExtension {

    /** Resolutions the host would perform, keyed by the matched text. */
    public val resolved: MutableList<String> = mutableListOf()

    /** How many times recognition ran. A recogniser called more than once per frame is a bug. */
    public var recognitionCount: Int = 0
        private set

    override fun recogniseInline(context: BlockContext): List<InlineRange> {
        recognitionCount++
        val text = context.content.toString()
        val matches = mutableListOf<InlineRange>()
        var openIndex = text.indexOf(open)
        while (openIndex >= 0) {
            val closeIndex = text.indexOf(close, openIndex + open.length)
            if (closeIndex < 0) break
            val body = text.substring(openIndex + open.length, closeIndex)
            // A body with whitespace is prose, not a mention.
            if (body.isNotEmpty() && !body.any { it.isWhitespace() }) {
                matches += InlineRange(
                    context.range.start + openIndex,
                    context.range.start + closeIndex + close.length,
                )
            }
            openIndex = text.indexOf(open, closeIndex + close.length)
        }
        return matches
    }

    /**
     * Resolves a matched mention.
     *
     * A callback the host owns, deliberately returning a `String` rather than
     * any domain type. The library has no idea what a mention resolves to, and
     * this is where that ignorance is preserved: if a host wants to turn
     * `@alice` into a `DocId`, or fetch it over HTTP, that is the host's code
     * running in the host's process.
     */
    public fun resolve(range: InlineRange, text: CharSequence): String {
        val raw = text.subSequence(range.start, range.end).toString()
        val handle = raw.removePrefix(open).removeSuffix(close)
        resolved += handle
        return handle
    }
}

/**
 * A fake block-level dialect: `:::name ... :::`.
 *
 * Exercises the case the ticket cares most about — a host adding a construct the
 * library has no built-in knowledge of, and the library treating it as a block
 * rather than as body text.
 */
public class FenceBlockSyntax(
    override val id: String = "fence-block",
    override val priority: Int = 10,
) : SyntaxExtension {

    override fun recogniseBlocks(context: BlockContext): List<BlockMatch> {
        val text = context.content.toString()
        if (!text.startsWith(":::")) return emptyList()
        val closing = text.lastIndexOf(":::")
        if (closing <= 0) return emptyList()
        return listOf(
            BlockMatch(
                range = InlineRange(context.range.start, context.range.start + closing + 3),
                ownsTerminator = true,
            ),
        )
    }
}

/** Two extensions matching the same range, for testing precedence. */
public class FixedRangeSyntax(
    override val id: String,
    override val priority: Int,
    private val ranges: List<InlineRange>,
) : SyntaxExtension {
    override fun recogniseInline(context: BlockContext): List<InlineRange> = ranges
}
