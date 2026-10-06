# Verifying IME composition against live decoration

`AP1Z9PXG`. The question: **does in-progress composition text survive Fude's
per-frame decoration pass?**

The short answer is that nobody knows, and it cannot be settled by writing a
better test. This document is how a person settles it.

## Why a test cannot answer it

Not a tooling limitation — an API wall. Three Compose APIs a test would need are
internal to `compose-foundation`:

| you want | you get on the JVM |
|---|---|
| `TextFieldBuffer.setComposition` | `setComposition$foundation` |
| `TextFieldState.composition` | `getComposition-MzsxiRA$foundation` |
| `new TextFieldBuffer()` | only a constructor taking `TextFieldCharSequence`, `ChangeTracker`, `OffsetMappingCalculator` |

No code outside that module can put an active composition on a buffer, nor
construct the buffer `applyDecoration` writes into. There is no injection point.
A synthetic composition would also be a different thing: the failure mode is
produced by a platform IME session holding state across frames, and a test that
fabricates the state is testing the fabrication.

Earlier reasoning in `AP1Z9PXG` claimed `TextFieldBuffer.composition` "exists in
the API, so a headless test could in principle inject a composition". That was
wrong — the name is visible, the setter is not. It should have been checked before
it was written down.

## What is actually at risk

Narrower than it was before `E279AQ3R` deleted `input/`. Fude has **no**
composition handling: it never reads `composition`, never commits one, and never
special-cases a selection because composition is active. `CompositionBoundaryTest`
enforces that as a tripwire.

So the entire exposure is one function. `applyDecoration` adds a style per span,
on every frame, over whatever the platform put in the buffer — and
`OutputTransformation` receives a **fresh** buffer each frame, so it cannot know
whether a region is being composed. If styling over a composing region disturbs
it, that is where it happens.

The specific risk: decoration rewrites the buffer while the IME holds an active
composition, and the composition is cancelled, garbled, or committed early. The
fenced-code case is the useful control — nothing inside a fence is decorated, so
it behaves like plain text by construction.

## Running it

```sh
scripts/ime_verification.sh
```

That opens `docs/fixtures/ime-composition.md` in the real desktop editor. Needs a
CJK input source enabled (macOS: System Settings → Keyboard → Input Sources).

Each of the fixture's nine cases is a place decoration applies a style to a range
you will be composing into. Type Japanese or Chinese into each marked spot — do not
type the markers.

## The nine cases

| # | where | what a failure looks like |
|---|---|---|
| 1 | plain paragraph | control; if this fails it is Compose or the platform, not Fude |
| 2 | inside `**bold**` | preedit text vanishes mid-word; span flickers |
| 3 | inside a link label | preedit cancelled; span jumps to the destination |
| 4 | start of a list item | preedit cancelled |
| 5 | inside a fenced code block | **should behave exactly like case 1** — it is the control for case 2 |
| 6 | a 5+ character word | partial commits; composition splits |
| 7 | commit then undo once | needs **more than one** undo to clear the word |
| 8 | caret after commit | caret lands somewhere other than after the committed text |
| 9 | two overlapping spans | worse than case 2, or different from it in a way that is hard to explain |

Case 5 is the one that makes the others interpretable. Nothing inside a fence is
decorated, so if fenced composition is clean and bold composition is not, the fence
is doing something and the difference is worth chasing before anyone files a bug.

Case 7 is the one with a number attached. `3451115` settled that `UndoGrouping`
coalesces a multi-character report, and found a better reason than expected —
adjacency plus idle timeout, not whether the edit was typed or composed. That
reasoning has never met an actual IME. Five characters must be one undo step; five
means each composition update reached history separately, and that reasoning is
wrong in a way that would affect typed input too.

## What to record

Per case: pass or fail, and if fail, what you saw. Screen recording is worth more
than a description — a cancelled composition is gone by the time you can type what
happened, and the flicker that precedes it is the actual evidence.

Worth stating explicitly even when everything passes: **which platform and input
source you used.** A pass on one macOS IME is not a pass on all of them, and
`AP1Z9PXG`'s last criterion asks for per-platform honesty rather than a single
green tick.

## Where the result goes

Report per case, and the platform. That closes `AP1Z9PXG`, or narrows it to a
specific failing construction — which is the more likely outcome and a more useful
one.

If case 5 fails, the problem is not Fude's decoration and this document is the
wrong place to look. That is still worth reporting.
