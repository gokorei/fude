# Markdown conformance stance

What Fude's parser does with CommonMark, and — more usefully — what it does not.

Every item here was verified against the parser, not inferred. Each is **supported**,
**deliberately unsupported** with a reason, or **undecided**. The point is that a
future contributor should not have to rediscover any of them, and should not "fix" the
surprising ones.

The corpus in `ConformanceCorpusTest` pins every row here, so this document and the
parser cannot drift apart.

`DifferentialConformanceTest` runs the same corpus against `org.jetbrains:markdown` and
records every place the two disagree. It lives in `jvmTest` rather than `commonTest`
because the reference parser publishes no common klib, which is a fine constraint for
a development-time check.

The corpus alone could not tell us whether any of these decisions were *right*. Every
expectation in it was written by the same person who wrote the parser, so a shared
misreading of CommonMark would be pinned just as firmly as a correct reading. The
differential check corroborates from outside.

**It found four defects that had been shipped unnoticed.** All are fixed, and all are
written up below because the cause matters more than the fix. It also confirmed that
every construct in the corpus is read the same way by an independent implementation.

This is the argument for having it. A corpus can only prove the parser has not changed
its mind.

## The surprising ones, recorded so nobody "fixes" them

These are correct CommonMark. They look wrong and will be reported as bugs.

| Input | Fude | Why it is right |
|---|---|---|
| `#Title` | `PARAGRAPH` | ATX headings need a space after the `#`. `#Title` is not a heading. |
| `####### s` | `PARAGRAPH` | Seven hashes exceeds the maximum level of six. |
| `#` | `HEADING`, empty | A bare `#` is a level-one heading with no content. |
| `- a\n\n- b` | one `LIST` | A blank line between items makes the list *loose*, not two lists. |
| `1) one` | `LIST` | Both `.` and `)` are ordered-list markers. |
| `> a\nb` | `BLOCK_QUOTE`, `PARAGRAPH` | **A known gap, not correct.** See below. |

## Deliberately unsupported

### Setext headings — unsupported

`Title\n=====` is a plain paragraph, not an H1. `Title\n-----` is a paragraph followed
by a thematic break, not an H2.

**Consequence, stated because it was ambiguous and blocked other work:** a `---`
following a paragraph is a **thematic break**, never a setext H2. Anything that needs
to know this — block-extent logic, windowing — can rely on it.

The reason it is not implemented: a live-preview editor renders the source it is
editing, and a setext heading's underline is indistinguishable from a thematic break
while you are typing it. Choosing the other reading would make `---` flicker between
"underline of the line above" and "horizontal rule" on every keystroke. That is a
judgement about the editor's job, not about Markdown.

### Autolinks — unsupported

`<https://example.com>` produces a paragraph with no `LinkNode`. The URL is not
clickable.

Deliberate for now: autolink detection is a regex over angle brackets, and a false
positive turns ordinary prose containing `<` into a link. Worth adding when a host
wants it — it belongs in the [syntax extension point](extending.md), not the library,
because "what counts as a URL in my dialect" is a host decision.

### Indented code blocks — unsupported

Four leading spaces produce a `PARAGRAPH`, not a code block.

Undecided rather than decided. The ambiguity is real: inside a list, four spaces
usually mean *continuation*, not code. Getting this wrong in the other direction
silently reformats a user's prose into a code block, which is worse than not
supporting it.

### Hard line breaks — unsupported

A trailing double space or a trailing backslash does not produce a `<br>`. The
document keeps its newline and Fude renders the paragraph as one run.

Undecided. The same live-preview tension as setext headings applies: the marker is
invisible, so a break that appears and disappears as the user types is worse than no
break.

## Known gaps that are neither supported nor deliberately so

These are unimplemented and unexamined. They are bugs, not positions.

| Construct | Fude produces | Should produce |
|---|---|---|
| `> a\nb` (lazy blockquote) | `BLOCK_QUOTE`, `PARAGRAPH` | one `BLOCK_QUOTE` |

**Lazy blockquote continuation is an inconsistency, not a gap.** Lazy continuation
*is* implemented for paragraphs — `a **bold** word\r\nnext` is one paragraph — so the
blockquote case is an omission against Fude's own rule rather than a missing feature.
It is listed separately for that reason.

### A gap that was filed here by mistake, and is now fixed

`- - -` was a row in this table for a long time, with the cause recorded as
"`isThematicBreak` requires every character to be a dash". **No such check existed.**
The real cause was set equality over a set that included spaces: `isThematicBreak`
compared `line.trim().toSet()` against `setOf('-')`, and the set for `"- - -"` is
`{'-', ' '}`, so the test failed — while the very next clause of the same predicate,
`all { it == '-' || … || it == ' ' }`, shows spaces were meant to be allowed. The set
test was silently forbidding what the character test permitted.

Two things follow from that, and both are why it is worth recording rather than just
deleting the row:

- A one-line predicate with a known cause and a known fix is an ordinary defect. Filing
  it here — beside three deliberate decisions — is what made this section stop being
  trustworthy, and the wrong recorded cause is why it was filed at all: anyone reading
  that sentence and then reading `isThematicBreak` would be misled about what to change.
- The **consequence** was worse than "unstyled", which is what the row said. `- - -` was
  parsed as a **list item** — a bullet with empty items rather than a rule — so it was
  the wrong shape entirely, not a missing style.

It is now under **Supported** below. The cause was never "every character must be a
dash", and if that phrase appears anywhere it is wrong.

## HTML: escaped by omission, and that should stay

`<div>\nhi\n</div>` and `a <b>bold</b> word` both produce a `PARAGRAPH` containing the
raw tags, rendered literally.

This is currently an accident of HTML blocks being unimplemented rather than a stated
position, and it should be made explicit — **shipped Markdown escapes user HTML and
does not render it.**

The reason that is the right answer for this library: rendering host-supplied HTML
would mean Fude evaluating arbitrary markup and its styles, in a component whose
purpose is to render *Markdown*. A host that wants live HTML has a browser and a
sanitiser; a Markdown editor that silently executes what it is given is a different
and much larger product. Escaping is also the only option that cannot become an
injection sink by accident.

## Supported

Headings (ATX, levels 1–6, with closing hashes), paragraphs with lazy continuation,
emphasis and strong emphasis in both `*` and `_`, code spans, fenced code blocks with
info strings including nested fences, block quotes, thematic breaks (`***`, `___`,
`---`), ordered and unordered lists including nesting and task items, pipe tables with
header rows and alignment — with or without outer pipes, and indented by up to three
spaces, as GFM specifies, `~~strikethrough~~`, and links and images with separate
label ranges.

Within that list, four rows now say more than they used to:

| Construct | Fude produces | Note |
|---|---|---|
| `- - -`, `* * *`, `_ _ _` | `THEMATIC_BREAK` | Spaces **or tabs** may separate the markers, and any number of them. Where a thematic break and a list item are both possible readings CommonMark says the break wins, and Fude's branch order already put the break first — so `- - -` is a rule and not a list of empty bullets. |
| `>quote` | `BLOCK_QUOTE`, content `quote` | The space after `>` is **optional**. It was assumed present and hardcoded as two characters, so every unspaced quote silently lost its first character — not thrown, just absent from the tree, so the caret could not reach it. |
| `> > nested` | nested `BLOCK_QUOTE` | Only the **outer** marker is stripped. Measured rather than looped, so nesting survives. |
| ` ``a ` b`` ` | one `CODE_SPAN`, content ``a ` b`` | A code span is closed by a run of the **same length** as the one that opened it. Searching for "the next backtick of any length" made this three spans and two stray highlighted empty regions. |

A tab-indented list item is a list item, and its `indent` is **4** — CommonMark measures
indentation in columns with tab stops, and that column count is what decides nesting and
whether a line continues an item. Its inline **offsets**, however, are counted in
characters, because every range this library emits indexes the host's original text
one-for-one. Those are two different quantities and they are now computed separately;
conflating them put `"\t- item"`'s content one past the end of the item holding `"m"`.

Line endings: LF, CRLF and lone CR are all recognised as line breaks, and a block's
range never ends on a terminator. A leading byte-order mark is offset past rather
than stripped, so offsets always index the host's own text. See the parser's KDoc —
that invariant is what the rest of the library is built on.

CRLF is also one grapheme cluster, under UAX#29 GB3, so backspace and delete remove a
line ending whole and the caret has no boundary to stop at inside one. That agrees
with `isLineTerminatorChar` and `pastLineTerminator`, which already treated `\r\n` as
a single terminator of two characters; the grapheme layer was the one place that
disagreed, and it is the layer the caret moves through.

### Fixed: an edit report that splits a CRLF used to undo wrongly

`editBetween` used to snap its boundaries outward to whole grapheme clusters, on the
reasoning that a report landing inside a CRLF should take the whole pair. That could not
coexist with its own round-trip contract. The common prefix and the common suffix are
found independently, so between them they already account for every unchanged character
in the report; widening a boundary past them deletes a character the report said was
unchanged, and the inserted text cannot supply it back because `after`'s suffix is by
definition identical to `before`'s. A host may legitimately report `"a
b"` to
`"a
b"` — selecting one character of the terminator, or normalising line endings on
paste — and the edit claimed to remove two characters:

| | before the fix | now |
|---|---|---|
| edit | `Delete(1..3, "\r\n")` | `Delete(1..2, "\r")` |
| `applyTo(before)` | `"ab"` — not the reported text | `"a\nb"` — the reported text |
| `inverse().applyTo(after)` | `"a\r\n\nb"` — an extra terminator | `"a\r\nb"` — restored |

CRLF was not the only cluster affected. A property over every single-character removal
and replacement found the same failure for a ZWJ sequence with the ZWJ removed on its
own — `EditDiff` no longer segments at all, so it no longer depends on `Graphemes`, and
the reason is recorded in its KDoc rather than here.

What the snap was defending against did not need defending: an `Edit` is applied
atomically, so a range falling between two units of a cluster is never observable, and
the inverse is built from the same pair of strings. The round trip is the invariant that
is observable, and `EditDiffTest.everyPartialClusterRemovalAndReplacementRoundTrips`
now states it over CRLF, combining marks, ZWJ sequences, surrogate pairs and skin-tone
modifiers.

### Grapheme clusters: what is and is not segmented

`Graphemes` is not a UAX#29 implementation and does not claim to be. Its scope is stated
in its KDoc as the rules that break editors — the ones where naive UTF-16 code-unit
deletion leaves visible garbage rather than removing a character. What that currently
covers, with the rule that does the work:

| Rule | Cluster | Implemented |
|---|---|---|
| GB3 | CR x LF | Yes |
| GB4 | Control | Yes, implicitly — neither CR nor LF is a combining mark |
| GB6-GB8 | Hangul L+V+T composition | Yes |
| GB9 | Extend: combining marks, variation selectors, and emoji tag characters | Yes |
| GB9c | Indic conjuncts | **No — see below** |
| GB11 | ZWJ-joined emoji | Yes, via the preceding-`ZWJ` clause |
| GB12-GB13 | Regional indicator pairs | Yes |
| — | skin-tone modifiers, keycaps | Yes |

**GB9c, Indic conjunct clusters, is the one known gap.** `U+094D` DEVANAGARI SIGN VIRAMA
falls inside the existing `0x093A..0x094F` combining range, so GB9 does join a virama to
the consonant it follows — which is why Devanagari looks mostly right. What is missing is
the consonant *after* the virama, which GB9c glues to the conjunct. So `\u0915\u094D\u0937`
(`क्ष`) segments as two clusters, and backspace removes `क्` and leaves `ष` on screen.

Left unimplemented deliberately. No document in the corpus contains Devanagari, unlike
Korean, which `SpikeDocument.syntheticBody` generates on every large-document
measurement — and a half-deleted conjunct is a worse symptom than a split flag, so this
is worth doing properly rather than approximately if it is ever needed. Pinned by
`aDevanagariConjunctStillSplitsAfterTheViramaAndThatIsRecorded` in `GraphemesTest`.

### Word selection: the platform's, and Fude has no opinion

Double- and triple-click selection is Compose's. `BasicTextField` issues
`SelectionController`, which resolves a word using the platform's notion of one — and
that notion is language-aware, which is the whole reason to prefer it.

Fude had a `SelectionActions` type that scanned grapheme clusters to find word
boundaries. It had **zero** references anywhere in the repository, so it had never run;
it is deleted. The reasoning is worth recording because "our scan is consistent with
our cluster rules" sounds like an argument for keeping it and is not: the platform
answers "what is a word" better than we can, and a Markdown-aware word — selecting a
whole `[[wikilink]]` or `#tag` as one unit — is a real requirement that belongs in a
`SyntaxExtension`, where the host states what a word means. It would not belong in a
library-level word scanner, because the answer is dialect-specific.

So: no divergence, and none intended. If a host wants Markdown-aware word selection,
the extension point is `SyntaxExtension`, not a reimplementation of selection.

## Divergences from the reference parser

Found by `DifferentialConformanceTest`, which fails if the list of *block kinds* grows.
**Three divergent cases, all one decision:**

| Input | Reference | Fude | Status |
|---|---|---|---|
| `Title\n=====` | `HEADING` | `PARAGRAPH` | **Deliberate.** Setext unsupported. |
| `Title\n-----` | `HEADING` | `PARAGRAPH` + `THEMATIC_BREAK` | **Deliberate.** Same decision. |
| `para\n-----` | `HEADING` | `PARAGRAPH` + `THEMATIC_BREAK` | **Deliberate.** Same decision; the case that actually comes up. |

Nothing else, at block granularity. Every other construct in the corpus is corroborated
by an independent CommonMark implementation — including the four rows added under
**Supported** above, which were each divergences or defects until recently.

**One inline divergence is pinned separately.** A link destination containing balanced
parentheses: `[a](b(c))` gives `b(c` where CommonMark gives `b(c)`, because
`parseLinkLike` finds the first `)` rather than balancing. Recorded rather than fixed,
because the fix in that area was bounding the search to the end of the current block —
a per-frame cost issue, not a parsing one — and balanced-paren scanning is a separate
question. It is now *pinned* (`aLinkDestinationWithABalancedParenthesisIsADivergence`)
rather than unknown, which is the only thing this section ever needed from it.

## What the differential check found and fixed

It has now caught four defects that had been shipped unnoticed, all recorded here
because the *cause* is more interesting than the fix.

### A spaced thematic break was parsed as a list item

`- - -`, `* * *` and `_ _ _` were not thematic breaks. `isThematicBreak` compared
`line.trim().toSet()` against `setOf('-')`, and that set contains spaces, so `"- - -"`
produced `{'-', ' '}` and failed — while the predicate's own next clause shows spaces
were meant to be allowed. The wrong shape, not a missing style: a bullet list with empty
items instead of a rule.

This one is also the row that was **filed as an unexamined gap with the cause recorded
wrongly**, which is the more interesting half. See the section above.

Rewritten as a single scan — three or more markers, all one kind, whitespace allowed
between — which expresses the rule directly and also picked up tab-separated breaks for
free, since tabs were rejected twice over before: once by `trimmed.length < 3` counting a
tab as one character, and once by the character test not allowing it at all.

### A block quote with no space after `>` lost its first character

`>quote` parsed as content `"uote"`. `parseBlockQuote` hardcoded `contentStart = cursor + 2`,
which is right for `> quote` and wrong for the unspaced form — which `isBlockQuote` has
always accepted and which a hand-typed quote often takes. The text was not thrown away
loudly; it was simply not in the tree, so nothing could reach it.

Fixed by measuring the marker. The leading indentation counts too, because `contentStart`
is a document offset and an indented quote's content does not start at column two. And
only the **outer** marker is stripped, so `> > nested` still nests.

### A blank line did not end a block quote

`> a` followed by a blank line and `> b` parsed as **one** block quote, so a
paragraph break visibly did nothing. `parseBlockQuote` stepped over the line
terminator with a skip that consumed *every* consecutive newline, swallowing the
blank line.

Fixed by stepping over exactly one terminator — the invariant every other block
already obeyed. `aQuoteDoesNotClaimTheBlankLineAfterIt` pins the range consequence,
because a fix for "the quote is too long" that leaves it too long in the other
direction would trade one bug for another.

### A pipe table needed its outer pipes

`A | B` above `--- | ---` was a paragraph; `| A | B |` above `|---|---|` was a table.

The cause was not where it looked. The header was fine — its **delimiter row** was
being rejected, because `isTableDelimiter` split on `|` and then required every cell
character to be a dash or colon. The padding spaces in `--- | ---` failed that check,
so a delimiter row written the way a human writes one was thrown away. Tightly-written
`--- | ---` variants happened to pass. The asymmetry is what made the bracketed form
work and the natural one fail.

Fixing that single check was most of it. The rest: accept a header with no opening
pipe, compare **column counts** rather than counting pipes, allow indentation, and trim
cell padding from the cell range so a decorated or selected cell covers what the author
wrote rather than the whitespace lining up the columns.

Two guard-rails came out of it, both about not over-matching. A pipe in ordinary prose
is still prose, because the decision needs a delimiter row — a fixed pipe threshold
would have turned `just prose with a | pipe` into a table. And column counts must
agree, so `a | b` above `--- | --- | ---` is not a table either. Escaped pipes are
content, so `escaped \| pipe` does not create a column.

## What this document is not

It is not a CommonMark compliance claim, and it is not a list of bugs to fix. It exists
so that the gaps are decisions rather than surprises. Two of them are deliberate and
argue against themselves in the entries above; the rest are recorded so they can be
chosen rather than discovered.
