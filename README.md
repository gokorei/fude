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
| Markdown conformance | Stated, with gaps made deliberate — [`docs/conformance.md`](docs/conformance.md) |
| Parser + incremental reparse | Done on JVM |
| Renderer + per-block toggle | Done on JVM |
| Keys, undo, IME, clipboard | **Partly** — undo and redo are the composable's, everything else is `BasicTextField`'s; see [Which layer owns editing mechanics](#which-layer-owns-editing-mechanics) |
| iOS, Web, Wasm, native | Not declared |

**390 tests**, `./gradlew build` green — 374 against fixtures checked into the repo,
and 16 skipped. Regenerate that figure rather than trusting it:

```bash
./gradlew build --rerun-tasks -q && python3 -c "
import glob, xml.etree.ElementTree as ET, collections
t = collections.Counter()
for f in glob.glob('*/build/test-results/**/*.xml', recursive=True):
    r = ET.parse(f).getroot()
    for k in ('tests', 'failures', 'errors', 'skipped'):
        t[k] += int(r.get(k, 0))
print(dict(t))"
``` The skipped 16 are the ones that need Markdown served by a live
knowledge store rather than a file on disk; see [Testing](#testing) for what they
need and what their absence means.

The count is measured from the JUnit XML after a full `./gradlew build --rerun-tasks`,
not maintained by hand, because it was wrong three times in three weeks. It was 453
here while the suite actually ran 390: the number had been written before the `input/`
package was deleted, and nobody recomputed it. Every `@Test` in the test source sets
now contains at least one assertion — the ten `spike/` probes that printed their
findings and asserted nothing were the other half of the gap, since a test that cannot
fail inflates the count without verifying anything.

If you change the suite and this number is still right, you changed something other
than the tests.

## Modules

| Module | Depends on | Contains |
|---|---|---|
| `:fude-core` | nothing | `EditorState`, `Edit`, `UndoStack`, `UndoController`, `Graphemes`, `SyntaxExtension`. Pure Kotlin — **no Compose, no `java.*`**. |
| `:fude` | `:fude-core`, Compose | The parser and the renderer. Keys, IME, clipboard and selection are Compose's and are not reimplemented here. |
| `:fude-demo` | `:fude` | A desktop host. Not published. |

The split is not cosmetic. `:fude-core` being a separate module means the state
model *cannot* acquire a Compose dependency — an import would fail to resolve
rather than pass review.

## Using it

Published to your local Maven repository — install it first, then depend on
it. There is no remote yet; naming one is a release decision, so for now the
artifact lives where you put it:

```bash
./gradlew publishToMavenLocal
```

```kotlin
// settings / build file
repositories { mavenLocal() }
dependencies { implementation("dev.fude:fude-jvm:0.1.0-SNAPSHOT") }
```

```kotlin
val state = remember { EditorState.of(markdown) }

MarkdownEditor(
    state = state,
    syntaxExtensions = listOf(MyWikilinkSyntax()),
    onChange = { text -> save(text) },
    // A host supplies decorations as well as receiving clicks on them. Ranges come
    // from the same `recogniseInline` that recognised the syntax, so recognising and
    // styling are one pass. Host decorations are applied last and win on overlap,
    // because a host's answer is the more specific one: a wikilink whose target exists
    // is not the same as one whose target has not been written yet.
    decorations = decorationsFor(state.text),
    onDecorationClick = { decoration -> openNote(resolve(decoration.range)) },
)
```

`EditorState` holds text, selection, scroll and per-block view, and nothing else. No
network, no persistence, no document model. `MarkdownEditor` returns `Unit`:
rendering is a pure function of state, so there is nothing for a caller to hold
that the state does not already hold.

Scroll is the one field where the model's value and the screen's value are not the
same thing. `EditorState` carries a `ScrollState` because the state model must not
depend on a UI toolkit, and `EditorState.applyEdit` preserves it, because an edit
moves the document under the caret rather than moving the caret in the viewport.
What it does *not* do is drive the screen: scroll position lives inside
`BasicTextField`, which owns it and does not surface a round-trip. Loading a
different note therefore starts at the top — deliberately, and by passing
`scroll = ScrollState.ZERO` rather than by resetting as a side effect.

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

## Which layer owns editing mechanics

The rule, written down because a repository accumulates types faster than it
accumulates rules:

> **The state model owns what an edit _means_. The platform owns how a key, a paste
> or an IME composition reaches it. They meet only where a host chooses to.**

`MarkdownEditor` is a host. It gives the platform `BasicTextField` and intercepts
exactly one thing — undo and redo, because `BasicTextField` has no undo of its own on
every target and where it does, its history is whole-text snapshots that bypass the
grouping rules the model defines. Everything else — arrows, Home/End, backspace,
select-all, clipboard — is the platform's, and overriding it would be a regression
rather than a feature.

### What was host-facing, and is now gone

`:fude` and `:fude-core` carried a good deal of correct, tested, public API that
`MarkdownEditor` never called. It was listed here rather than left for a reader to
work out from a test count, because **the test count is actively misleading about
this**: 24 green tests over `KeyHandler` read like coverage of the editor's key
handling, and were coverage of a class nothing called.

That defence no longer holds. The argument for keeping them was that "unused" was the
symptom rather than the finding — that some existed for something the library does not
have yet, and the rest for hosts needing to observe or transform what the platform
already does for them. Measured against Compose's actual API, the second group turned
out to be reimplementations of things Compose provides in full, and keeping them
invited divergence from a code path that never ran. All of it is deleted.

| Type | Was | Why it went |
|---|---|---|
| `KeyHandler`, `EditingKey`, `KeyEvent`, `HostKeyHook` | 24 tests, no caller | Compose owns keys. `MoveCursorCommand` is visual-line caret movement, `DeleteSurroundingTextInCodePointsCommand` is grapheme-aware deletion via the platform `BreakIterator`, and `SelectionController` is word selection. Wiring ours would mean intercepting every editing key to suppress `BasicTextField`'s own handling — including its IME and clipboard paths — in exchange for a second implementation of what the platform already does. |
| `SelectionActions` | 7 tests, **zero** references anywhere | The only fully orphaned type in the library. Word boundaries are not language-neutral, and the platform's are better than a hand-written scan; a Markdown-aware word belongs in a `SyntaxExtension`, where the host says what a word means. |
| `PositionMapper` | 15 tests, no caller | Existed to carry wrap-aware caret movement for `KeyHandler`. With arrows delegated to Compose there is no consumer. Its `LineIndex` went with it, taking `TextBuffer.lineStartOf`/`lineEndOf`, which had no caller either. |
| `CompositionSession`, `ImeCommitter` | 7 tests, no caller | Compose implements the whole IME transaction — `SetComposingRegion`, `SetComposingText`, `CommitText`, `FinishComposingText`. The requirement that motivated it, one IME commit is one undo step, is met without it: `UndoStack` records whole-buffer diffs, and a word the platform reports as one change is already one undo entry. |
| `ClipboardAccess`, `ClipboardPayload`, `SystemClipboard` | 11 tests, no caller | `BasicTextField` brings clipboard handling on every target with a platform text field. `cut()` did not cut, and `paste()` reported `wasRichText` while discarding the HTML it was warning about — code that had never run, which is how those survived. |

82 tests went with them, 460 → 378.

One entry in the old table was not deleted, because the question behind it is real:
`VisualToSourceMapper` is for a read-only *rendered* pane, which Fude does not have.
Here rendering is drawn over the source, so the mapping is the identity. It stays,
and the reason it stays is that the missing thing is a feature rather than an
unexercised code path.

## Gates

`./gradlew build` enforces four things. Each one exists because something specific got
past the compiler, the tests, or a human reviewer.

### `checkArchitecture`

Fails the build if:

- `commonMain` references host-store types (`DocId`, `Frontmatter`, `ParsedMarkdown`, …).
  Fude edits text handed to it; what a document *is* belongs to the host
  (Tanseki), and the build fails if the boundary leaks.
- `commonMain` imports `java.*` or `javax.*`
- `:fude-core` imports Compose

The third rule is why grapheme segmentation is hand-written rather than delegating
to `java.text.BreakIterator`. That looks like needless effort to a future reader;
the check is what keeps it from being "simplified" back.

Platform source sets are exempt, because a platform implementation has to use the
platform's own APIs. No such implementation remains — the clipboard one went when
`input/` was deleted — so the exemption is currently unused.

**What it does not catch, and should.** A `public val` on a public config class that
no composable reads. `EditorConfig.maxLines` and `EditorConfig.placeholder` were
exactly that: declared, documented, settable, and doing nothing, with no warning and
no compile error — because nothing about an unread property is an error anywhere in
Kotlin. Both are wired now. The general form of the problem is bigger than those two
fields: *any* public API with tests and no caller is invisible to every tool here,
which is why the table above had to be written by hand. A fourth check — a text scan
alongside `checkArchitecture` that fails when a `public val` on `EditorConfig` has no
read reference in the module — would catch the next one. It is not there yet.

### `apiCheck` — binary compatibility

`:fude-core` and `:fude` are published, so their API is a promise to anything that
compiles against them, and published API in this library is expensive to take back.
`apiCheck` compares the compiled declarations against checked-in `api/*.api` dumps and
fails on any incompatible change. `apiDump` regenerates them — deliberately, so the
baseline cannot be rewritten by a build that should have failed instead.

Verified by narrowing `TextBuffer.withEdit` from `public` to `internal`: `jvmApiCheck`
failed with "API check failed for project fude-core", and passed again on revert.

`:fude-demo` is a host application and is not published, so it is excluded.

### `detekt` — static analysis

Configured in `detekt.yml` rather than defaulted, because a rule set nobody chose
produces noise within a week and a report nobody reads is not a gate. The rules that
matter most here are unused code and long lines; `ForbiddenComment` rejects a bare
`TODO:` or `FIXME:` on the grounds that one without an owner or a ticket is a wish.

59 existing findings are baselined across the three modules. They are mostly size and
complexity in `IncrementalMarkdownParser`, which is a `when` over every block type and
is genuinely branchy — refactoring it is a project, not a lint fix — plus nested loops
in the exhaustive property tests, where nesting over a fixture matrix is the point.
A baseline is the honest way to say "known, recorded, and the build stays green"; the
gate catches anything *new*.

**Detekt does not run on Java 23 or newer.** Version 1.23.8 is the latest release and
bundles a `kotlin-compiler-embeddable` that cannot parse a Java 25 version string, so
it dies before reading a source file. This was verified rather than assumed: on Java 22
it runs and fails the build on a planted violation; on Java 25 it cannot start. On an
unsupported JVM the task is disabled and the build prints that **the static-analysis
gate did not run** — a gate that passes because it inspected nothing is the worst
outcome available, so it says so instead. Verified on JDK 17 through 22.

Detekt's default source set is the JVM plugin's `src/main/kotlin`, which is *empty* in a
multiplatform project. That is configured explicitly in each module; without it detekt
passes having analysed nothing, which is exactly the failure this gate exists to
prevent.

## Known gaps

Stated plainly, because each is real work rather than a detail.

### Large documents: slow, not wrong

A keystroke into a large note costs more than a frame. These figures come from
`scripts/keystroke_sweep.sh` driving the **real `MarkdownEditor`** in a real window —
not from `KeystrokeBudgetTest`, which builds a bare `BasicTextField` with a cheap
`**`-pair-scanning `OutputTransformation` as a stand-in for a reparse. The test is a
useful harness and asserts nothing about timing; the table below is not its output, and
running `./gradlew build` will not reproduce it. Re-run the script instead. One
machine, one day, and the numbers move: see [`docs/spike.md`](docs/spike.md) for the
curve and the method, and measure on the hardware you care about.

| lines | keystroke, marginal cost | at 42 ms budget |
|---|---|---|
| 1,000 | 8 ms | 20% of budget |
| 2,000 | 14 ms | 33% |
| 5,000 | 39 ms | 93% |
| 10,000 | 88 ms | 210% — over, but predictably so |

**The cost is linear, at about 9 microseconds per line.** Taking the keystroke's own
cost with the harness's frame floor removed and fitting against line count gives
`cost = 0.0091 × lines`, which predicts every confirmed measurement to within a few
milliseconds. There is no superlinear term and no cliff, so the cost is predictable
from document length rather than surprising. 9 us per line reaches a 42 ms budget on
its own at roughly **4,600 lines**.

That is the marginal cost. The floor underneath it is not Fude's: the harness waits two
frames, which is 16 ms at the ~125 fps this window runs at but closer to **33 ms on a
real 60 Hz display**. So of a 42 ms budget, most goes to the frame before the editor
does anything. It is a constant, so it does not affect the slope or any comparison
between document sizes.

Four fifths of the cost is Compose laying out a `BasicTextField` over the whole
document; Fude's parse and decoration add 12 ms at 5,000 lines — so a faster parser
would not help, and block-level virtualization is the only real fix.

**The 12 ms is not decoration arithmetic.** An earlier investigation assumed the delta was Fude
recomputing spans over the whole document each frame, and that bounding it to the
viewport would recover most of it. Measured on the 5,047-line spike fixture
(`DecorationCostTest`): 4,013 blocks produce 3,011 spans in **~1 ms** warm, scaling
linearly with block count — about 4× the cost for 4× the blocks. The cheap win does
not exist; there was never 76 ms of span computation to reclaim.

That relocates the cost rather than removing it. `applyDecoration` calls
`buffer.addStyle` once per span, so a frame does **3,011 span applications** — roughly
180,000 per second at 60 fps. That figure is inferred, not measured: counting it
exactly needs a real `TextFieldBuffer`, which needs a real Compose frame. So the
expensive half is provably not the part that was measured, and the part that probably
is expensive cannot be measured from a unit test.

Windowing would fix both, and it is blocked on something the API does not provide:
`OutputTransformation` receives a fresh buffer each frame and **hands the library no
viewport information**, so there is nothing to narrow by. Any windowing scheme has to
derive the visible range from scroll state and measured line height, which is why it
is invasive rather than incremental.

**It is deliberately not built.** Windowing is a large, invasive change to the caret,
scrolling and IME paths, and the measurements do not justify it yet: a 2,000-line note
is 34 ms, inside budget, and 2,000 lines is a lot of prose. Two further reasons to
wait — the original trigger (a 1,682-line note "21% over budget") turned out
to be an artefact of the old untrusted harness, and windowing rests on an even split
between two Skiko passes that cannot be verified from outside the library. So the work
is deferred until a real document actually shows the problem, not on a schedule.

### No size ceiling in the library

**There is deliberately no line-count threshold in Fude.** A document of any size
opens, renders and edits exactly — no truncation, no dropped blocks, nothing refused
and nothing degraded. Above roughly 4,600 lines the editor simply gets slower at a
predictable rate, and how slow that should be is not the library's decision to make.

This was removed deliberately. The library held a `PERFORMANCE_CEILING_LINES` constant
and an `onPerformanceWarning` callback that changed nothing about the editor's
behaviour — it only told a host when to show a banner, at the cost of four pieces of
public API that are very hard to remove later. A host can count lines itself and pick
its own threshold for its own users and its own hardware, which is the only judgement
that can actually be right.

What survives is the measurement, and the way to reproduce it: `docs/spike.md` has the
curve and the method, and `scripts/keystroke_sweep.sh` re-runs it. Measure on the
hardware you care about — 9 us per line is cheap on one machine and not on another.

### Everything else

1. **IME composition** is Compose's. It implements the full transaction
   (`SetComposingRegion`, `SetComposingText`, `CommitText`, `FinishComposingText`) and
   Fude does not intercept it. One IME commit being one undo step is satisfied anyway:
   undo grouping is fed from the gesture path, and a word the platform reports as a
   single buffer change coalesces like any other edit.
2. **No iOS, Web or Wasm targets declared.** They need a machine with Xcode to
   verify, and shipping unverified targets is worse than shipping none.
3. **Visual-line arrow movement** is Compose's. Up and down move by *display* line
   across a wrap, because the platform owns the caret's pixel row and issues
   `MoveCursorCommand`. Fude's own `KeyHandler`, which moved by *logical* line and
   disagreed, is deleted rather than corrected — see *What was host-facing* above.
4. **`Shift-Tab` list outdent** is not implemented.
5. **Clipboard is Compose's**, on every target with a platform text field. Fude's
   own contract and JVM implementation are deleted, so there is nothing here to
   port. A host that needs to observe or transform the clipboard intercepts the
   platform's own path.
6. **One deliberate parser divergence from CommonMark**: setext headings are not
   implemented, so `---` after a paragraph is always a thematic break. Two defects
   the differential check found — a blank line that did not end a block quote, and a
   pipe table that needed its outer pipes — are both fixed. See
   [`docs/conformance.md`](docs/conformance.md).
7. **`maxLines` and `placeholder`** were declared and read by nothing. Both are wired
   now: `maxLines` bounds the field's height and the field scrolls rather than
   truncating, and `placeholder` is drawn behind an empty document. Neither was
   difficult; what made them survive is that nothing fails when a config field is
   ignored. See [Architecture](#architecture) for the guard that should stop the next
   one.
8. **The per-block toggle had no host API.** `MarkdownEditor` created its
   `BlockViewState`, kept it private and never surfaced it, so a host could not set a
   block to source or rendered — while the feature was listed under *Design
   commitments* and the whole decoration path was built around it. Fixed: `view` is a
   parameter defaulting to a fresh instance, so a host holds the same one across
   recompositions and calls `toggle` itself. The reason this survived a review is
   worth recording: an internal overload with a different arity existed purely so a
   test could reach the state, so the symbol *was* referenced. A grep for the symbol
   found a reference and stopped.

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

### Where this runs

**Desktop JVM only, for now.**

`jvm()` is the only declared target, and that is a decision rather than an omission.
Cross-platform work is out of scope until the 1.0 release, at which point the
target set becomes worth revisiting rather than something to drift into.

Recorded here because it changes what is worth building. Each of these looks like an
oversight without it:

- **No iOS, web/Wasm or Android target.** Compose's own position is that
  `BasicTextField` is least mature on iOS, so an iOS cut would be the least
  attractive moment to discover problems.
- **`expect/actual` does not exist here.** Adding a target that needs it is a larger
  change than declaring the target, which is a good reason not to declare one.
- **`docs/ime-verification.md` is a manual procedure on one machine with one CJK
  input source.** A pass there is evidence about that machine and nothing more. It is
  nine human-observed cases rather than a test because
  `TextFieldBuffer.setComposition` is internal to `compose-foundation`; that document
  explains why no test can ask the question.
- **Every performance figure is from one machine.** One figure was re-measured independently and
  found the published attribution wrong by a factor of seventy, which is the argument
  for re-measuring rather than inheriting a number.

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
Tanseki daemon — Tanseki being the knowledge store this editor was split out of,
and the daemon being its local server mode. The reason is not
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
sixteen tests that silently do not run, which is the skipped count a green
`./gradlew build` reports. That is deliberate: CI has no Tanseki, and a missing
service is not a defect in the editor.

It does mean `./gradlew build` reporting green tells you nothing about real
content unless a daemon was actually there. Nothing in that class is `@Ignore`d
at the moment, so a fully seeded run should report sixteen passing; if it does
not, the count of skips is the first thing to read.

## Licence

Apache 2.0. See [LICENSE](LICENSE).
