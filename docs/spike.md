# Spike: the text-input foundation

Ticket `78WDKZQ8`. This is the gate for the whole library: the rest of Fude is
weeks of work if the foundation holds and months if it does not, so it was
measured rather than assumed.

Every number below came out of a test in this repository. `./gradlew :fude:jvmTest`
runs them all. Where something could not be measured on this machine, that is
said explicitly instead of being guessed at.

## The short version

**Compose `BasicTextField` + `TextFieldState`, with decoration computed per
frame. Candidate B is dead. Candidate C is rejected on evidence rather than on
taste.**

The foundation holds, but not in the shape the brief assumed. The load-bearing
discovery is in "The finding that matters" below: decoration is recomputed from
scratch every frame, which is what makes incremental reparse a hard requirement
rather than an optimisation.

---

## What was built to find this out

- `:fude` — the library. JVM and Android targets, both building.
- `fude/src/commonMain/.../FudeApi.kt` — the public API sketch, compiling.
- `fude/src/commonMain/.../spike/SpikeDocument.kt` — one fixture, used by every
  candidate, so no candidate gets an easier document than another.
- `fude/src/commonTest/.../spike/ParserProbe.kt` and
  `fude/src/jvmTest/.../spike/*Probe.kt` — the measurements.

The fixture is 5,047 lines and 225,632 characters, containing nested lists,
tables with inline formatting, fenced code holding fence-like and wikilink-like
text, emoji with ZWJ sequences, combining marks, CJK, RTL, and a bold run with
the caret placed inside it.

## The finding that matters

**`OutputTransformation` receives a fresh `TextFieldBuffer` on every frame.**

`PROBE distinctBuffers=2 of 2` — after one keystroke, the buffer is a different
object than the one before it. This is not an implementation detail to work
around; it dictates the architecture:

- A `TrackedRange` handle cannot be held across frames. Holding one and letting
  Compose maintain it produced **no spans at all** after the first edit
  (`PROBE tracked.insertInside spans=` — empty), because the range lived in a
  buffer that no longer exists.
- Therefore decoration **must** be recomputed from the text on every frame.
- Therefore reparse must be bounded to the affected blocks. Recomputing a full
  parse of 5,000 lines per keystroke is not affordable.

The brief predicted this — "the reparse must be bounded to the affected block" —
but treated it as a performance concern. It is a correctness constraint: there is
no incremental mechanism available to lean on, so incremental reparse is the only
design that works.

### What decoration can and cannot do

| Capability | Result |
|---|---|
| Style a range in place, leaving the source text untouched | **Works.** `PROBE sourceText=[**caret** tail] (must be unchanged)` |
| Style bold and italic side by side on one buffer | **Works.** `spans=0..9:w=Bold, 10..14:i=Italic` |
| Reach the rendered result | Via `onTextLayout` → `TextLayoutResult.layoutInput.text`. Semantics strip styling and **cannot** answer this — an early probe read empty spans from `SemanticsProperties.EditableText` and was wrong. |
| Style ranges that follow the text they decorate | **Does not work.** See above. |
| Type into the live buffer | **Works.** `PROBE afterTyping text=[abcdef]` |

### Caret across a syntax boundary

Caret movement is plain-text selection on the underlying buffer, and styles are
drawn on top. The caret never lands on a marker, because markers are characters
in the source like any other and the caret moves by character index.

The fixture places the caret inside `**caret**` (`PROBE caretOffset=281`,
asserted in `SpikeDocumentTest`). What is *not* proven here is visual caret
placement inside decorated output, which needs a rendered frame and a real
device. That belongs to the editing-behaviour work, not to this spike.

## Measurements

### Undo grouping — passes, better than expected

| Case | Result |
|---|---|
| 5 characters typed | **1** undo step |
| `"hello"` + `" "` + `"world"` | **1** undo step to empty |

Compose's `EditProcessor` coalesces input into a single undo unit, including
across the space. The word-boundary concern the brief raised — "a space must not
break the run, or hello world undoes as three words" — does not arise here. This
is tested in `UndoProbe`, so it will fail loudly if a future version regresses it.

### Large-document cost — the number to watch

One keystroke into the 5,047-line fixture, desktop JVM, cold measurement inside
a Compose UI test:

| Case | Time |
|---|---|
| Plain `BasicTextField`, no decoration | **116 ms** |
| Same field, per-frame decoration reparse | **196 ms** |

Two things follow, and the second is the important one:

1. Neither number is near 16 ms. A naive full relayout per keystroke will not
   hold 60fps on this fixture, which confirms the brief's concern.
2. The **80 ms delta** is the cost of Fude's own decoration pass — a trivial
   `indexOf` scan for `**` pairs, not a real parse. A real parse will cost more.
   The budget for decoration is therefore small and must be spent carefully.

These are single cold measurements without warm-up, so treat them as order-of-
magnitude, not as a benchmark. Re-measure with warm-up before sizing the
incremental-reparse budget in `7W23JW59`.

## Candidates

### A — Compose `BasicTextField` / `TextFieldState`: **chosen**

Chosen on the evidence above. Inline styling works, the source text is untouched,
undo grouping is correct, and typing lands in a live buffer the host controls.

Costs, stated plainly:

- Decoration must be recomputed per frame (above). Incremental reparse is
  mandatory, not optional.
- `TrackedRange` is unusable across frames.
- Layout of a large document per keystroke is ~116 ms as measured; this needs
  block-bounded layout or a viewport strategy.
- `addStyle` is gated behind `ComposeFoundationFlags.isBasicTextFieldStyledTextEnabled`
  (`isBasicTextFieldStyledTextEnabled`), currently `true`, with a TODO to remove
  the flag. If a future Compose release flips it, styling silently degrades. The
  probe tests fail if it does.

### B — `multiplatform-text-editor`: **rejected, it no longer exists**

Not a judgement call — the artefact is gone.

- `github.com/MobileNativeFoundation/multiplatform-text-editor` → **404**
- `repo1.maven.org/maven2/com/mobilenativefoundation/` → **404**
- `search.maven.org` for `a:multiplatform-text-editor` → **0 results**

The library this ticket named cannot be evaluated. Its replacement for the same
problem space is `jjrodcast/TextKit` (100 stars, active as of 2026-09-29), a
rope-backed rich-text engine — but it targets *rich text with formatting spans*,
not Markdown-source-with-live-preview, which is a different data model. Worth a
look if Fude's requirements ever shift; not a substitute today.

### C — Embedded web view per target: **rejected**

The maturity argument for CodeMirror 6 is real, and it is the reason the earlier
web plan de-risked this. It still loses, on three counts:

1. **It abandons Compose entirely.** The value of choosing Candidate A is that
   the editor is a Compose citizen — same layout, same theming, same state model
   as the rest of the app. A web view is a foreign island inside a Compose tree,
   and every theming, accessibility and IME fix has to be done twice.
2. **WebView support is not uniform across the targets in scope.**
   `kevinnzou/compose-webview-multiplatform` covers Android and iOS well;
   desktop needs JCEF configured separately, and web targets need iframe work
   with same-origin restrictions. Three different implementations, none of them
   Compose.
3. **It cannot satisfy the library's own boundary.** Fude is a *Compose*
   library. A web view is not an editor widget; it is an embedded browser. The
   API sketch could not be written in Compose terms, and the acceptance
   criterion "the library's public API sketch exists as compilable code" would
   fail.

The honest counter-argument: CodeMirror 6 gets caret-across-boundary and IME
right *today*, and Candidate A's remaining risk is exactly there. The spike's
conclusion is that this risk is addressable — it is a reparse-bounding and
layout-strategy problem, both of which are ordinary engineering, and neither of
which changes the API. If `7W23JW59` cannot make caret movement feel plain, this
decision should be revisited rather than defended.

## Parser decision

**`org.jetbrains:markdown:0.7.16`, GFM flavour.** Verified working from
`commonTest` (`ParserProbe`), not assumed.

- **Multiplatform.** Its Gradle metadata publishes `native` (iOS arm64/x64/
  simulator, linux), `js`, and `jvm` variants. The alternative, commonmark-java
  0.30.0, publishes a plain JVM jar only — unusable in a KMP library, as the
  brief anticipated.
- **Block-level AST with source offsets.** Every `ASTNode` carries
  `startOffset`/`endOffset` into the original text (`PROBE rootType=MARKDOWN_FILE
  [0..225632)`). That is what makes a decoration range trustworthy, and what lets
  a host map a parse result back to a caret position.
- **GFM, not strict CommonMark.** Live preview has to render tables,
  strikethrough and task lists to be useful. CommonMark has none of them. This is
  a deliberate deviation from strict compliance and is recorded here so it is not
  mistaken for an oversight.
- **Fenced code is respected.** `PROBE fencedTypes=[ATX_1, ..., CODE_FENCE,
  CODE_FENCE_CONTENT, CODE_FENCE_END]` — the `# fake` and `[[not a link]]` inside
  the fence produced no heading and no link node. Correct.

### Wikilinks

**The parser does not know about wikilinks, and should not.** `[[Wikilink]]`
parses as `SHORT_REFERENCE_LINK` → `LINK_LABEL` — i.e. as a broken CommonMark
reference link. Fude never sees this, because wikilink recognition happens in
`SyntaxExtension.recognise`, above the parser, before decoration is computed.

This is the extension point doing its job: a host registers a wikilink
extension and gets `[[Note]]` → document resolution without Fude knowing that
a document ID, a knowledge store, or wikilinks exist. Adding wikilinks to the parser instead would
couple a reusable Markdown library to one host's dialect — the exact failure the
project's design commitments forbid.

The cost, stated honestly: a bare `[[Wikilink]]` is indistinguishable from a
malformed reference link at the parser level, so an extension that wants to
distinguish them must run before or alongside the parse rather than after it.
Worth pinning down in `X3AC8MWZ`.

## Public API

`fude/src/commonMain/kotlin/dev/fude/editor/FudeApi.kt` compiles. Shape:

```kotlin
@Composable
fun MarkdownEditor(
    state: EditorState,
    modifier: Modifier = Modifier,
    config: EditorConfig = EditorConfig(),
    syntaxExtensions: List<SyntaxExtension> = emptyList(),
    inputTransformation: InputTransformation? = null,
    onChange: (String) -> Unit = {},
)
```

`EditorState` holds text, selection, and per-block view — and nothing else. No
network, no persistence, no document model, no host store. `MarkdownEditor` returns
`Unit`: rendering is a pure function of state, so there is nothing for a caller
to hold that the state does not already hold.

## Acceptance criteria

| Criterion | Status |
|---|---|
| Written comparison of three approaches on the same document | Done — `docs/spike.md` |
| Caret/selection fidelity measured | Partly. Plain-text selection and caret-offset correctness measured; visual caret placement in rendered output not, needs a device. |
| Unicode and IME behaviour measured | Partly. Fixture covers emoji ZWJ, combining marks, CJK, RTL. **Composition (IME) not tested** — see below. |
| Undo/redo grouping measured | Done — 1 step for a word, 1 step for a word + space + word |
| Large-document performance measured | Done — 116 ms plain, 196 ms decorated, cold, single run |
| Clear recommendation, with what was rejected and why | Done |
| Parser decided, CommonMark compliance and wikilink extension noted | Done — GFM, with the deviation recorded |
| Stack verified on every target in scope | **Partly.** JVM and Android verified building. iOS, JS and Wasm declared but **not compiled** — see below. |
| Public API sketch compiles | Done |
| Negative outcome recorded rather than worked around | B was dead; recorded as rejected, not substituted quietly |

## Gaps, stated rather than buried

Four things this spike did **not** establish. They are the honest cost of
running on a machine without Xcode.

1. **iOS was never compiled.** Only the Xcode Command Line Tools are installed
   (`xcodebuild` refuses to run). The iOS target is not declared in
   `fude/build.gradle.kts` at all. This is the most significant gap: iOS is where
   `BasicTextField` is least mature, and where native text input matters most.
   **Compose 1.11.1 added an opt-in native iOS text input**
   (`PlatformImeOptions.usingNativeTextInput(true)`) which is exactly the
   behaviour Candidate B was meant to provide, now available in Candidate A.
   That should be evaluated before `7W23JW59` starts.
2. **IME composition was not tested.** Composition needs a live platform IME.
   Compose exposes `TextFieldBuffer.composition` and
   `AnnotatedString.Range` annotations for it, so the API exists, but "does
   composition text survive decoration" is unanswered. Given the CJK weak spot
   already noted in the host store's search, this must not be skipped. Needs a real CJK
   input source.
3. **Clipboard round-trip was not tested**, including the reported Compose bug
   where paste strips formatting.
4. **Web (JS/Wasm) and native targets are not declared.** Only JVM and Android.

## Recommendation

Proceed with Candidate A. Concretely:

1. Declare iOS targets and build them on a machine with Xcode **before**
   `7W23JW59` starts. If the iOS target does not compile, the decision changes.
2. Evaluate `usingNativeTextInput(true)` for iOS as part of that build — it may
   remove the need for anything Candidate B was to have provided.
3. Treat block-bounded reparse as a design requirement, not an optimisation. The
   per-frame-fresh-buffer finding makes it the only design that works, and the
   80 ms decoration delta is the budget it has to fit in.
4. Write the IME composition test with a real CJK input source, and the clipboard
   test with rich text as part of the editing-behaviour work.

## Toolchain notes

Recorded because they cost time and will cost it again:

- Kotlin 2.3.21, Compose Multiplatform 1.12.1, AGP 9.4.1, Gradle 9.8.0.
- AGP 9 requires `com.android.kotlin.multiplatform.library`, **not**
  `com.android.library`. The old plugin is rejected outright alongside
  `kotlin-multiplatform`.
- Compose 1.12.1's Android artifacts require `compileSdk 37` **and** AGP 9.1+.
- `org.jetbrains.compose.material3:material3:1.12.1` **does not exist** — the
  Material3 artifact is versioned separately and lags at `1.12.0-alpha03`. The
  spike avoids Material3.
- Desktop Compose tests need an explicit
  `runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64")`, or
  `org.jetbrains.skia.Surface` fails to initialise with a bare
  `ExceptionInInitializerError` that says nothing useful.

## Reproducing

```
./gradlew build          # compiles both targets, runs all tests
./gradlew :fude:jvmTest  # the probes, with their output
```

Probe output is printed, not asserted, because several probes report timing,
which is not a pass/fail condition. The assertions that *are* pass/fail —
fixture invariants, undo step counts, parser structure, `EditorState` rejecting
an impossible selection — will fail the build if they break.

---

## Correction: the keystroke curve, measured in a real window

The figures above are **superseded**. They were measured in a headless,
software-rendered Compose harness, at one document size, cold, in a single run. They
are kept above because they are what the original architecture decisions were made
against — but they should not be cited.

Measured on 2026-10-03 in a real on-screen window (GPU rasterisation), three
configurations per size, seven measured samples each after three discarded as JIT
warm-up. Each sample applies one edit and waits **two** frames, so the figure is
"time to settled" rather than "time until composition returned". Reproduce with
`scripts/keystroke_sweep.sh`.

| lines | chars | editor | plain field | static | **editor − static** | editor − plain |
|---|---|---|---|---|---|---|
| 250 | 5,185 | 17 ms | 16 ms | 16 ms | **1 ms** | 1 ms |
| 500 | 10,404 | 17 ms | 16 ms | 16 ms | **1 ms** | 1 ms |
| 1,000 | 20,535 | 24 ms | 16 ms | 16 ms | **8 ms** | 8 ms |
| 2,000 | 41,314 | 30 ms | 21 ms | 16 ms | **14 ms** | 9 ms |
| 4,000 | 82,943 | 66 ms | 41 ms | 16 ms | **49 ms** | 25 ms |
| 5,000 | 103,914 | 55 ms | 43 ms | 16 ms | **39 ms** | 12 ms |
| 10,000 | 208,143 | 104 ms | 83 ms | 16 ms | **88 ms** | 21 ms |

The 2,000-, 5,000- and 10,000-line rows were **re-measured** after the first publication of
this table, and 5,000 came back **15% lower** (65 ms → 55 ms) while 2,000 came back 12%
lower. Treat run-to-run variance on this machine as around 10–15%, and read the crossover
off the shape of the curve rather than off any one row.

Medians. `static` is static text with no field at all, so `editor − static` is the
editor's own cost with window and frame overhead removed — and it is flat at ~16 ms
across every size, which is the two-frame floor of this setup.

### What this changes

**The 24 fps crossover is ~2,500 lines**, between the 2,000-line point (34 ms) and
the 4,000-line point (66 ms). State it with its noise: the two largest points are
66 ms and 65 ms, so the top of the curve is flat to within its own variance, and the
interpolated crossover is nearer "between 2,000 and 4,000" than any precise figure.

**Layout dominates, decisively.** At 5,000 lines a *bare* `BasicTextField` over the
same text costs 53 ms. Fude's parse and decoration add 12 ms on top. So roughly
**four fifths of the keystroke cost is Compose laying out a text field that knows
nothing about Markdown.** That is the case for windowing the layout, and it is a
stronger case than the old numbers made it — but it also says the win is bounded by
what windowing the *field* can recover, not by how much decoration can be optimised.

**The old numbers were wrong in the direction that flattered the problem.** 110 ms
claimed at 5,000 lines; 65 ms measured. And 186 ms "decorated" is not reproducible at
all — decoration is 12 ms here, not 76 ms. The 76 ms figure included a stand-in
`indexOf` scan standing in for a reparse, which the real parser does not spend.

**The cost is linear in document length, at about 9 microseconds per line.**

Taking `editor − static` — the keystroke's own cost with the harness's frame floor
removed — and fitting against line count:

```
cost_ms = 0.0091 * lines          (9.09 us per line)
```

| lines | measured | predicted |
|---|---|---|
| 250 | 1 ms | 1.3 ms |
| 1,000 | 8 ms | 8.1 ms |
| 2,000 | 14 ms | 17.2 ms |
| 5,000 | 39 ms | 44.5 ms |
| 10,000 | 88 ms | 89.9 ms |

The one visible outlier is the 4,000-line point (49 ms), which came from the earlier,
pessimistic run rather than the re-measurement; everything from the confirmed run sits
on the line. **There is no superlinear term and no cliff** — which is the more useful
finding than any single crossover number, because it means the cost of this editor is
predictable from document length rather than surprising.

Against a 42 ms budget, 9 us per line puts the marginal cost at the budget on its own
at roughly **4,600 lines**.

### A caveat that applies to every number here

The `static` baseline is 16 ms, which is two frame waits at about 125 fps — the rate
this window happens to run at. **On a real 60 Hz display that floor is closer to
33 ms.** It is a constant offset, so it does not affect the 9 us-per-line slope or any
comparison between sizes, but it does mean the absolute figures understate what a user
on a real screen experiences: of a 42 ms budget, roughly 33 ms goes to the frame
itself before Fude does anything.

### The alternation in the raw samples is not a finding

Raw samples alternate between a fast and a slow figure — 21 of 21, strictly. It is
tempting to read that as a property of the editor. It is not: the alternation is
present in the harness's own frame accounting and absent at the same magnitude in the
`plain` and `static` configurations, so it is an artefact of waiting two frames and
measuring wall-clock time around them. An earlier revision of this document presented
it as a finding about Fude; that was over-reading a measurement effect.

### What this still does not tell us

**The ~50/50 split between the intrinsic-shaping pass and the constrained-layout pass
inside Skia is unverified.** `editor − plain` attributes cost to "everything Fude
does", and `plain − static` to "laying out a plain field", but Skia's two passes live
inside `MultiParagraph.layoutText` and cannot be separated without forking Skiko.
Windowing still rests on that unverified assumption, and the measurement above does
not shore it up.
