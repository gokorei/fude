# Fude demo — a desktop host

Runs the editor on desktop JVM. Exists because automated tests cannot answer
whether the caret lands where the eye expects; a person driving it can.

```
./gradlew :fude-demo:run
```

## What to look at

The window opens on a document containing every block kind the parser handles,
plus two things the library knows nothing about:

- `{{mentions}}` — an inline dialect registered by `MentionSyntax` in `Main.kt`.
  Clicking one resolves it through the host's callback. Fude has no idea what a
  mention is, and never finds out.
- `> [!note]` — a block construct from `CalloutSyntax`. The library parses no
  callout syntax; the host claims the block and the library honours that.

The fenced block at the bottom of the sample contains `# not a heading` and
`[[not a wikilink]]`. Neither becomes markup. That is the classic live-preview
bug and it is what `MarkdownParserTest.aFenceKeepsItsContentsOpaque` pins down.

## What is deliberately absent

No Opal, no document model, no persistence, no network. The demo holds a string
and hands it back. Everything else — what the string means, where it comes from —
is the host's problem, which is the entire point of splitting this out.
