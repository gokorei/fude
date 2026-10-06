# Extending Fude

Fude ships Markdown. Everything else — `[[wikilinks]]`, `@mentions`, callouts,
frontmatter, whatever a given host needs — arrives through
[`SyntaxExtension`](../fude-core/src/commonMain/kotlin/dev/fude/syntax/SyntaxExtension.kt).

This is the seam that keeps Fude a reusable library rather than one host's
component with a misleading name. Fude has no idea what a wikilink is, what a
`DocId` is, or what a vault contains.

## The contract

```kotlin
public interface SyntaxExtension {
    public val id: String
    public val priority: Int get() = 0

    public fun recogniseInline(context: BlockContext): List<InlineRange> = emptyList()
    public fun recogniseBlocks(context: BlockContext): List<BlockMatch> = emptyList()
}
```

Three things an extension must respect:

1. **Be pure.** A recogniser that reads mutable state makes rendering depend on more
   than the document, which breaks the guarantee the whole design rests on. See
   *How often you are called* below for how often purity actually has to hold.
2. **Return source offsets.** Ranges are absolute document offsets, not positions
   within the block. This is what lets a decorated range still select the text it
   came from.
3. **Resolve nothing.** Recognition finds syntax; resolution is a callback the
   host owns. Fude never performs a lookup, never touches the network, and never
   converts an identifier into a domain type.

## How often you are called

**`recogniseInline` is called once per block, during parsing — not per frame, and
not over the whole document.** This is the single most useful thing to know as an
extension author, because it decides what you are allowed to assume about cost.

For each block it re-parses, the parser calls `recogniseInline` with a `BlockContext`
whose `range` is that block's inline run, and `content` is that run's text. Your
ranges come back as `HostInlineNode`s, are precedence-resolved against every other
extension's, and are styled by the renderer in the ordinary way — a host inline match
is decorated exactly like a `**bold**`, by the same pass, in the same order.

Concretely:

- **You are not called for a block showing source.** A block the user has toggled to
  source is left undecorated entirely, host syntax included. There is nothing to
  recognise.
- **You are not called on a frame where nothing was reparsed.** Recognition happens
  in the parser, and the parser runs bounded to the block that changed. Scrolling,
  resizing and re-laying-out do not call you.
- **You are not called for a code fence's contents**, even though the parser does
  walk them. A fence is opaque: a `{{mention}}` inside a code sample is not
  highlighted, because highlighting it would tell the user their fence was being
  interpreted.
- **Your cost is bounded by the block being edited**, not by the length of the note.
  A recogniser that scans `context.content` is doing exactly as much work as the
  block is long. Scanning `context.text` — the whole document — is possible but is
  your recogniser doing a block's job at a document's price.

This was not always so. The renderer used to call `recogniseInline` over the entire
document on every frame, and styled whatever came back regardless of the block's mode.
That pass was removed as redundant — the parse had started producing the same matches
through a precedence-resolved path — and it had been disagreeing with it: it styled
blocks set to source, it highlighted inside fences, and it resolved overlaps in
registration order rather than by the rules below. If you are upgrading and relied on
that, the differences are all in the direction of *less* highlighting.

A note on invalidation, since it comes up: **do not cache inside a recogniser.**
Purity is what makes caching sound in principle, but the cache key would be the
document text and knowing when to throw it away is a separate problem the library
solves once, centrally, rather than once per extension.

## A complete example

A mention syntax: `{{alice}}` becomes a clickable, styled span.

```kotlin
class MentionSyntax : SyntaxExtension {
    override val id = "mention"
    override val priority = 10

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

    /**
     * Resolution lives here, in the host. Note the return type: a String, not a
     * domain type. Fude cannot resolve anything because it does not know what a
     * mention *means*.
     */
    fun resolve(range: InlineRange, text: CharSequence): String =
        text.subSequence(range.start, range.end).toString().removeSurrounding("{{", "}}")
}
```

Registering it:

```kotlin
val mention = MentionSyntax()

MarkdownEditor(
    state = state,
    syntaxExtensions = listOf(mention),
    onDecorationClick = { decoration ->
        // The host decides what a click means, including whether to navigate.
        openProfile(mention.resolve(decoration.range, state.text))
    },
)
```

`Fude` tests itself against `MentionSyntax` and `FenceBlockSyntax` in
`fude-core/src/commonTest/kotlin/dev/fude/syntax/`. Those are the library's own
tests, using syntax it has never heard of, with no vault and no host store involved.
An extension point with one real consumer and no tests is an unverified API.

## Adding a block construct

Inline syntax styles text in place. A block construct takes over a whole region —
frontmatter, a callout, a diagram block.

```kotlin
class FenceBlockSyntax : SyntaxExtension {
    override val id = "fence-block"
    override val priority = 10

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
```

`ownsTerminator` is the decision worth getting right. It says whether the closing
delimiter belongs to the block. Get it wrong in one direction and the closing
`:::` is swallowed; get it wrong in the other and it is left dangling in the
body text, which the user then has to delete by hand.

## Precedence

When two extensions match overlapping ranges:

1. **Longest match wins.** More specific beats broader, because the user is
   usually getting what they typed.
2. **Then higher `priority`.**
3. **Then `id`, alphabetically.**

The tie-break on `id` rather than registration order is deliberate: it makes the
outcome independent of the order a host happens to build its list in. Without a
defined rule, adding an unrelated extension silently changes rendering, which is
the worst possible moment to find out.

Tested in `SyntaxExtensionTest` — including that reversing the input list
produces an identical result — and, at the level that actually reaches the screen,
in `MarkdownEditorRenderTest`: two extensions matching the same range produce one
highlighted span, and the registration order does not decide which.

`recogniseBlocks` claims its candidate outright, before the library decides what a
block is, so the same precedence rules apply at block level. The cost of that is
worth stating plainly: an extension that returns a match for every candidate has
decided where blocks are, and the parser will not overrule it. Return a match only
when the construct is really there.

## What the library will not do

- Know that wikilinks exist.
- Know about `DocId`, `Frontmatter`, collections, or vaults.
- Perform network calls or domain lookups.

Enforced, not just documented: `checkArchitecture` in the root
`build.gradle.kts` fails the build if `commonMain` imports anything from the host
store (Tanseki), if
`commonMain` imports `java.*` or `javax.*`, or if `:fude-core` imports Compose.
`./gradlew build` runs it.

Run `./gradlew checkArchitecture` to verify the boundary yourself.
