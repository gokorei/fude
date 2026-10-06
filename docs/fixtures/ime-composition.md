# IME composition verification fixture

> Not Fude content. This document exists to be typed into.

Every construct below is a place where live decoration applies a style to a range
that an IME is trying to compose into. If `applyDecoration` disturbs an in-progress
composition, it will do so here and nowhere else in a realistic note.

Type Japanese or Chinese into each marked spot. Do not type the markers.

## 1. Plain paragraph — the control

If composition fails here, the problem is Compose or the platform, not Fude.

Start typing anywhere in this paragraph.

## 2. Inside bold

**start here**

`applyDecoration` adds a span over the whole `**bold**` run every frame. A
composition cancelled mid-word shows as the preedit text vanishing.

## 3. Inside a link label

[start here](https://example.com/a/very/long/target/path)

The span covers the *label*, not the destination, and the label is where you are
composing. This is the case naive caret mapping gets wrong.

## 4. At the start of a list item

- start here

## 5. Inside a fenced code block

```
start here
```

Nothing inside a fence is decorated. Composition here should be *identical* to
case 1, which makes it the control for case 2: if fenced composition works and
bold composition does not, the fence is doing something.

## 6. Long run of composition

The short cases above can pass by luck. Compose a whole word here — five or more
characters — and commit it with a single undo.

aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

## 7. Commit, then undo, immediately

Type a five-character Japanese word at the end of this line and press undo once.

aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

A five-character commit must be **one** undo step. Five means each composition
update reached history separately.

## 8. Caret position after commit

Commit a composition in the middle of this sentence and check where the caret
lands.

The caret should sit immediately after the committed text, not at the end of the
paragraph.

## 9. Two decorations in one composition

**bold with [a link](https://example.com) inside it**

Two overlapping spans over one region. This is the worst case for a styling pass
that is not reentrant.
