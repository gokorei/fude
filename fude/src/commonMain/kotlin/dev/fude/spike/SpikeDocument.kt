package dev.fude.spike

/**
 * The single test document every candidate in the spike is measured against.
 *
 * The point of generating one document rather than hand-writing several is that
 * a candidate that passes only on a friendly document has not been tested. Each
 * section below targets a specific documented failure mode from the spike brief.
 */
public object SpikeDocument {

    /** Lines of synthetic body appended to reach the large-document target. */
    public const val LARGE_DOCUMENT_LINES: Int = 5_000

    /**
     * A line with an active inline element, with the caret offset inside it.
     *
     * `**caret**` is the case live preview breaks on: the user is editing
     * rendered output, and arrow keys must behave as though the text were plain.
     */
    public const val CARET_ANCHOR: String = "**caret**"

    /**
     * Character index within [CARET_ANCHOR] where the caret is placed.
     *
     * `**caret**` is 10 characters: indices 0-1 are the opening markers, so an
     * offset below 2 would put the caret on the syntax rather than the content.
     */
    public const val CARET_ANCHOR_OFFSET: Int = 4

    public val header: String = """
        # Spike document

        A line of plain prose with **bold**, *italic*, ***bold italic***,
        ~~strikethrough~~ and `inline code`, plus a [[Wikilink]] and
        a [[Target|labelled link]] and an ![image](image.png).

        ## Caret anchor

        The caret for the selection-fidelity test is placed inside **caret** on
        this line, which is the case live preview breaks on.

        ## Lists

        - Level one
            - Level two
                - Level three
                    - Level four
        1. Ordered
        2. Ordered
            1. Nested ordered
        - [ ] Task open
        - [x] Task done

        ## Table with inline formatting

        | Column | Value | Note |
        |--------|-------|------|
        | **a**  | *b*   | `c`  |
        | d      | [[e]] | f    |

        ## Fenced code

        ```markdown
        Not a heading: # this is inside a fence
        Not a link: [[NotAWikilink]]
        Fence-like: ```{nested}
        ```

        ## Unicode

        Emoji ZWJ family: 👨‍👩‍👧‍👦 — flag: 🇯🇵 — skin tone: 👋🏽
        Combining marks: é à ö ñ
        CJK: 日本語のテキスト、中文字符、한국어
        RTL: مرحبا بالعالم — עברית
        Mixed: 日本語 with English and العربية together
    """.trimIndent()

    public val syntheticBody: String = buildString {
        repeat(LARGE_DOCUMENT_LINES) { index ->
            when (index % 5) {
                0 -> appendLine("Paragraph $index with **bold** and a [[Link$index]] for density.")
                1 -> appendLine("- List item $index with *emphasis* inside")
                2 -> appendLine("> Quote $index with `code` and 日本語 mixed in")
                3 -> appendLine("### Heading level three at $index")
                else -> appendLine("Plain line $index — no markup, no unicode, no links.")
            }
        }
    }

    /** The full document every candidate is measured against. */
    public val full: String = "$header\n\n$syntheticBody"

    /**
     * Character offset of [CARET_ANCHOR] within [full].
     *
     * Computed rather than hard-coded so the fixture stays correct when the
     * header changes.
     */
    public val caretOffset: Int = full.indexOf(CARET_ANCHOR) + CARET_ANCHOR_OFFSET

    public val lineCount: Int get() = full.count { it == '\n' } + 1

    public val charCount: Int get() = full.length
}
