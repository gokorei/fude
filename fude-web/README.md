# fude-web — the web host

A host application, like `:fude-demo` is for desktop. Not published.

It serves two purposes that are one thing: the `@JsExport` boundary in
`dev.fude.web` (`WebEditor.kt`) and the demo page that proves it
(`dev.fude.web.demo` + `index.html`). The boundary is what a Phoenix LiveView
hook — or any page that is not Kotlin — calls. The demo page calls it from
both sides (Kotlin mounts one editor, inline JavaScript mounts another), so a
green `./gradlew build` plus one headless run below proves the whole loop
without fingers.

## The contract

The bundle loads asynchronously: `window["fude-web"]` is a promise that
resolves to the boundary once the runtime is up. Awaiting it is what orders
the host after the export bindings, which are assigned last in
initialisation — calling on load races them and loses.

```js
window["fude-web"].then((Fude) => {
  // Mounted; `save` runs on every edit with the whole document.
  const editor = Fude.fudeMount("editor-root", "# Hello\n", false, save);

  Fude.fudeSetText(editor, "# Hello\nmore\n");     // edit: toggles follow
  Fude.fudeLoadDocument(editor, "# Other note\n"); // swap: toggles cleared
  Fude.fudeSetPlainMode(editor, true);             // raw Markdown, no styling
  Fude.fudeGetText(editor);                        // read the document
});
```

Mount once per page load: `ComposeViewport` returns nothing to dispose, so a
mounted editor lives until the page goes away and there is deliberately no
unmount. A host that re-renders DOM around the editor must leave the container
alone — `phx-update="ignore"` on the container is the LiveView form. Every
value crossing is a string, a boolean, or an int; the numeric handle survives
`postMessage` and JSON untouched.

## Running it

```bash
./gradlew :fude-web:wasmJsBrowserDistribution
python3 -m http.server 8931 --directory fude-web/build/dist/wasmJs/productionExecutable
# open http://localhost:8931/index.html
```

`?autotype` drives one edit over the boundary from JavaScript and reports the
result in the title (`FUDE_AUTOTYPE_OK len=…`). It is the hook a headless
smoke gate would assert on — see below.

## What is verified, and what is not

- The bundle compiles, mounts from Kotlin and from JavaScript, renders
  decorated Markdown (headings, emphasis, code, links, quotes, lists), and
  round-trips an edit plus a `getText` over the boundary — all observed in
  headless Chrome with a clean console and a screenshot, not assumed.
- The `commonTest` suite (parser, reparse, goldens) runs green in a browser
  via `:fude:wasmJsTest`.
- Typing by hand — caret feel, IME composition, clipboard, virtual keyboards —
  has never been done in front of this page by a person. That is the next
  verification, and it needs fingers rather than a harness.
- Headless runs need software WebGL (`--use-angle=swiftshader
  --enable-unsafe-swiftshader`): with GPU disabled Skiko gets no GL context
  and the frame loop dies, which looks like an app bug and is a flag bug.
- The wasm compile in this module flakes intermittently (instant OOM in the
  `@JsExport` checker; clean rebuilds pass). See the comment in
  `fude-web/build.gradle.kts`. Disabling incrementality did not help, so the
  record of the symptom is the fix on offer until the toolchain explains
  itself.
