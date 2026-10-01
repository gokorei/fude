# Extending Fude

Fude ships Markdown. Everything else — `[[wikilinks]]`, `@mentions`, callouts,
frontmatter, whatever a given host needs — arrives through
[`SyntaxExtension`](src/commonMain/kotlin/dev/fude/syntax/SyntaxExtension.kt).

This is the seam that keeps Fude a reusable library rather than a Musubime
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

1. **Be pure.** `recogniseInline` runs on every frame, over the blocks that
   changed. A recogniser that reads mutable state makes rendering depend on more
   than the document, which breaks the guarantee the whole design rests on.
2. **Return source offsets.** Ranges are absolute document offsets, not positions
   within the block. This is what lets a decorated range still select the text it
   came from.
3. **Resolve nothing.** Recognition finds syntax; resolution is a callback the
   host owns. Fude never performs a lookup, never touches the network, and never
   converts an identifier into a domain type.

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
        openProfile(mention.resolve(decoration.range, state.text.text))
    },
)
```

`Fude` tests itself against `MentionSyntax` and `FenceBlockSyntax` in
`fude-core/src/commonTest/kotlin/dev/fude/syntax/`. Those are the library's own
tests, using syntax it has never heard of, with no vault and no Opal involved.
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
produces an identical result.

## What the library will not do

- Know that wikilinks exist.
- Know about `DocId`, `Frontmatter`, collections, or vaults.
- Perform network calls or domain lookups.

Enforced, not just documented: `checkArchitecture` in the root
`build.gradle.kts` fails the build if `commonMain` imports anything from Opal, if
`commonMain` imports `java.*` or `javax.*`, or if `:fude-core` imports Compose.
`./gradlew build` runs it.

Run `./gradlew checkArchitecture` to verify the boundary yourself.
