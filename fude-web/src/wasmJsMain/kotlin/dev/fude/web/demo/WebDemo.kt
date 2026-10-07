package dev.fude.web.demo

import dev.fude.web.fudeMount
import kotlinx.browser.document

/**
 * The web demo host.
 *
 * Mounts one editor into `#fude-demo-kotlin` from Kotlin and leaves the rest
 * of the page to JavaScript: `#fude-demo-js` is mounted by the inline script in
 * `index.html` through the same exported functions any host calls, so the page
 * proves both directions — Kotlin driving the editor, and JavaScript driving
 * it with no Kotlin involvement.
 *
 * Importing this bundle on a page *without* those elements mounts nothing and
 * changes nothing. The bundle is shared with hosts that drive the boundary
 * themselves (a Phoenix hook mounts its own element), and a library that
 * mounted an editor as a side effect of loading would fight every one of them.
 */
fun main() {
    if (document.getElementById(KOTLIN_ROOT_ID) == null) return

    fudeMount(KOTLIN_ROOT_ID, SAMPLE_DOCUMENT, false, null)
}

internal const val KOTLIN_ROOT_ID = "fude-demo-kotlin"

internal const val SAMPLE_DOCUMENT = """# Fude for the web

A live-preview Markdown editor running on a canvas. Type **bold**, *italic*,
`code` or a [link](https://example.com) into a paragraph.

- A list item
- Another item

> A quote worth keeping.
"""
