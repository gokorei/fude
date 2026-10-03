# Fude (筆)

A standalone Kotlin/Compose library for **live-preview Markdown editing**: the
user types Markdown and the formatted result appears in place, while the raw
syntax stays editable.

The editor is the thing Obsidian does, rebuilt as a Compose Multiplatform library
rather than a web app.

## What it is not

**It knows nothing about any host.** No `DocId`, no `Frontmatter`, no collections,
no vaults, no HTTP seam, no `/v1`. That boundary is the point, and it is enforced
by a build check rather than a convention — see [Architecture](#architecture).

Wikilinks, mentions and frontmatter all arrive through the
[syntax extension point](docs/extending.md). The library ships Markdown; a host
supplies its own dialect.

## Status

| Area | State |
|---|---|
| Text-input foundation (spike) | Done, measured — [`docs/spike.md`](docs/spike.md) |
| Editor state model | Done — `:fude-core` |
| Syntax extension point | Done — [`docs/extending.md`](docs/extending.md) |
| Parser + incremental reparse | Done on JVM |
| Renderer + per-block toggle | Done on JVM |
| Keys, undo, IME, clipboard | **Partly** — see [Known gaps](#known-gaps) |
| iOS, Web, Wasm, native | Not declared |

246 tests, `./gradlew build` green — 243 of them against fixtures checked into
the repo, and 14 against Markdown served by a live knowledge store. See
[Testing](#testing) for what the second group needs, and what its absence means.

## Modules

| Module | Depends on | Contains |
|---|---|---|
| `:fude-core` | nothing | `EditorState`, `Edit`, `UndoStack`, `Graphemes`, `PositionMapper`, `SyntaxExtension`. Pure Kotlin — **no Compose, no `java.*`**. |
| `:fude` | `:fude-core`, Compose | The parser, the renderer, key handling, clipboard. |
| `:fude-demo` | `:fude` | A desktop host. Not published. |

The split is not cosmetic. `:fude-core` being a separate module means the state
model *cannot* acquire a Compose dependency — an import would fail to resolve
rather than pass review.

## Using it

```kotlin
val state = remember { EditorState.of(markdown) }

MarkdownEditor(
    state = state,
    syntaxExtensions = listOf(MyWikilinkSyntax()),
    onChange = { text -> save(text) },
    onDecorationClick = { decoration -> openNote(resolve(decoration.range)) },
)
```

`EditorState` holds text, selection and per-block view, and nothing else. No
network, no persistence, no document model. `MarkdownEditor` returns `Unit`:
rendering is a pure function of state, so there is nothing for a caller to hold
that the state does not already hold.

## Design commitments

- **Source is the truth.** What the user typed is canonical. Rendering never
  rewrites their Markdown to "fix" it, and decoration is applied on top of the
  buffer rather than replacing it.
- **Plain-text semantics underneath.** The document is a string with a cursor and
  a selection. What is drawn on top of it is a projection.
- **Per-block source/rendered toggle.** A block is either showing its syntax or
  its result, and the user decides. Obsidian's model, copied deliberately: it is
  the thing that removes the ambiguity of "am I typing Markdown or prose?"
  Toggle state lives *outside* the document, so toggling never marks a note
  modified.
- **Bounded reparse.** An edit reparses one block, not the document. Asserted on a
  counter rather than a timing, because a counter is exact and a timing fails on a
  slow machine while the code is equally wrong. This is a correctness requirement,
  not an optimisation: a full reparse of a 5,000-line note per keystroke is not
  affordable.

## Architecture

`./gradlew checkArchitecture` fails the build if:

- `commonMain` references Opal types (`DocId`, `Frontmatter`, `ParsedMarkdown`, …)
- `commonMain` imports `java.*` or `javax.*`
- `:fude-core` imports Compose

The third rule is why grapheme segmentation is hand-written rather than delegating
to `java.text.BreakIterator`. That looks like needless effort to a future reader;
the check is what keeps it from being "simplified" back.

Platform source sets are exempt, because a clipboard implementation has to use the
platform's own APIs.

## Known gaps

Stated plainly, because each is real work rather than a detail.

1. **A keystroke into a large note costs far more than a frame.** Measured in a real
   window on 2026-10-03: **34 ms at 2,000 lines, 66 ms at 4,000**, against a 42 ms
   budget for 24 fps — so the crossover is **~2,500 lines**. The figures formerly
   quoted here (110 ms at 5,000 lines, 186 ms decorated) came from a headless,
   software-rendered harness at one size and should not be cited; `docs/spike.md` has
   the correction and why the old numbers flattered the problem.

   The useful part is *where* the cost is. At 5,000 lines a bare `BasicTextField`
   over the same text costs 53 ms; Fude's parse and decoration add 12. **Four fifths
   of a keystroke is Compose laying out a field that knows nothing about Markdown**,
   which is why the fix is block-level virtualization rather than a faster parser.

   Two caveats. Worst case at 5,000 lines was 159 ms against a 65 ms median, and a
   user perceives the worst case. And the assumed even split between Skia's
   intrinsic-shaping and constrained-layout passes is still **unverified** — it is
   internal to Skiko and cannot be measured without forking it — so windowing still
   rests on an assumption. Reproduce with `scripts/keystroke_sweep.sh`.
2. **Undo grouping is implemented and tested but not yet fed from the platform
   gesture path.** Compose reports whole-change diffs, so undo granularity is
   currently whatever Compose reports.
3. **IME composition** is modelled — intermediate updates produce no edits, a
   commit produces exactly one — but the composable does not yet call it, and
   verifying it needs a real CJK input source.
4. **No iOS, Web or Wasm targets declared.** They need a machine with Xcode to
   verify, and shipping unverified targets is worse than shipping none.
5. **Visual-line arrow movement** is not implemented; `PositionMapper` carries the
   data but nothing consumes it.
6. **`Shift-Tab` list outdent** is not implemented.

## Toolchain

Kotlin 2.3.21 · Compose Multiplatform 1.12.1 · AGP 9.4.1 · Gradle 9.8.0 · JDK 17+

Notes worth keeping, all of which cost time to find:

- AGP 9 requires `com.android.kotlin.multiplatform.library`, **not**
  `com.android.library`.
- Compose 1.12.1's Android artifacts need `compileSdk 37` **and** AGP 9.1+.
- `org.jetbrains.compose.material3:material3:1.12.1` **does not exist** — the
  Material3 artifact is versioned separately and lags.
- Desktop Compose tests need Skiko's per-OS native runtime on the classpath
  (all five are declared, since the loader picks at runtime) or
  `org.jetbrains.skia.Surface` fails with an `ExceptionInInitializerError` that
  says nothing useful. Declaring only the macOS one is the trap: it works on the
  machine that wrote it and fails everywhere else.

## Building

```bash
./gradlew build              # compile, test, architecture check
./gradlew :fude-demo:run     # the desktop host
```

The demo opens on a document containing every block kind, plus two things the
library knows nothing about: `{{mentions}}` and `> [!note]` callouts, both
registered by the host. See [`fude-demo/README.md`](fude-demo/README.md).

Point it at real Markdown instead of the baked-in sample with either:

```bash
FUDE_DEMO_FILE=/path/to/note.md ./gradlew :fude-demo:run
./gradlew :fude-demo:run -Dfude.demo.file=/path/to/note.md
```

## Testing

`./gradlew build` runs everything and is green with no external services. That
is most of the suite, and it is worth being precise about which part it is not.

### The Tanseki functional suite

`TansekiFunctionalTest` is the exception. Every other test parses a fixture
someone wrote by hand; this one parses Markdown fetched over HTTP from a running
[Tanseki](https://github.com/DavyMaddelein/tanseki) daemon. The reason is not
elegance. Hand-written fixtures contain the syntax the author already thought of,
which is exactly the syntax that works. The failures worth catching are the ones
nobody types on purpose — a code fence whose contents look like Markdown, a link
label shorter than its destination, an emoji that is four code points but one
character.

It found three real defects on its first run. One of them,
`SyntaxExtension.recogniseBlocks` never being called, was invisible to the
existing suite in a way worse than a gap: `SyntaxExtensionTest` calls the method
directly, passes, and makes a dead public API look covered.

To run it, seed a daemon and point the suite at it:

```bash
# 1. a Tanseki daemon on :8088 (or anywhere — see below)
# 2. seed the six documents
python3 scripts/seed_tanseki.py
python3 scripts/seed_tanseki.py --base-url http://elsewhere:8088
TANSEKI_URL=http://elsewhere:8088 python3 scripts/seed_tanseki.py

# 3. run the suite
./gradlew :fude:jvmTest --tests "dev.fude.functional.TansekiFunctionalTest"
```

The seed script upserts, so re-running it is safe, and it reads every document
back through the same endpoint the test suite uses and compares byte for byte
before reporting success. A seed that did not round-trip exactly would surface
as a parser failure three files away; instead it fails at the seed, naming the
character it diverged at.

### A green run is not coverage against real content

With no daemon running, `TansekiFunctionalTest` **skips** rather than fails —
fourteen tests that silently do not run. That is deliberate: CI has no Tanseki,
and a missing service is not a defect in the editor.

It does mean `./gradlew build` reporting green tells you nothing about real
content unless a daemon was actually there. Three of those fourteen tests are
currently `@Ignore`d, each carrying the diagnosis of a known defect, so a fully
seeded run reports 11 passing and 3 skipped.

## Licence

Apache 2.0. See [LICENSE](LICENSE).
