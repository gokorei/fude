package dev.fude.markdown

/**
 * The block fixtures every exhaustive test drives, in one place.
 *
 * ## Why this exists
 *
 * There were three block matrices in this repository with three different contents, and
 * they drifted. Two of them are exhaustive sweeps — every offset of every fixture, every
 * edit kind — and between them they are the project's strongest correctness asset: they
 * are what turned `reparse` from "probably right" into a measured agreement with a full
 * parse. A sweep's value is that it is exhaustive *over its fixture set*, so a fixture
 * set that quietly omits the construct most recently fixed is exactly how the next
 * regression ships unnoticed.
 *
 * That is not hypothetical. Three defects were fixed in one wave — a tab-indented list's
 * content offsets, `>quote` losing its first character, and `- - -` not recognised as a
 * thematic break — and **none of the three** appeared in the reparse sweep's matrix. The
 * properties were guarded, but by the smaller test, not by the one whose purpose is
 * exhaustive coverage.
 *
 * ## Why one copy rather than two that differ
 *
 * The duplication was deliberate and documented at the time: the sweep's matrix was
 * copied from the range property test's rather than shared, so the two could diverge on
 * purpose. That was defensible when there was one copy and one consumer. There are now
 * three, and none of the divergence is doing any work.
 *
 * ## What is and is not covered
 *
 * Covered, one fixture each: ATX heading, paragraph, bullet list, **tab-indented list**,
 * fenced code (closed and unclosed), block quote **with and without a space after the
 * marker**, table, thematic break **plain and spaced**, and a host block.
 *
 * Not covered here, and guarded elsewhere — stated so a future fix knows which is which:
 *
 * - setext headings, which are deliberately unsupported (`docs/conformance.md`)
 * - indented code blocks, likewise
 * - autolinks and hard line breaks, likewise
 * - Indic conjunct clusters (UAX#29 GB9c), a known `Graphemes` gap
 *
 * Nothing in this file asserts anything. It is data, shared so that adding a fixture
 * once puts it in front of every exhaustive test rather than in front of whichever one
 * remembered.
 */
internal val BLOCK_FIXTURES: LinkedHashMap<String, String> = linkedMapOf(
    "heading" to "# Heading\n",
    "paragraph" to "A paragraph.\n",
    "list" to "- one\n- two\n",
    // Tab-indented, which is a *different* list from a space-indented one. `indentOf`
    // counts a tab as four columns, which is right for the nesting question and wrong
    // for a character offset, and the two were once added together to compute one --
    // 8WBTEBS1.
    "tab-indented list" to "\t- tabbed\n\t- second\n",
    "fenced code" to "```kotlin\nval x = 1\n```\n",
    // No closing marker, so the fence runs to the end of its region. G7HPWM1Y: the
    // range used to claim the terminator after it.
    "unclosed fence" to "```\ncode\n",
    "block quote" to "> quoted\n",
    // No space after the marker. HHNMTGGJ: the content start was a hardcoded two
    // characters, so this form lost its first character.
    "unspaced block quote" to ">quote\n",
    "table" to "| a | b |\n|---|---|\n| 1 | 2 |\n",
    "thematic break" to "---\n",
    // Spaced. 0PPP1ARJ: the marker test required the run to be solid.
    "spaced thematic break" to "- - -\n",
    "host block" to ":::note\nA callout.\n:::\n",
)