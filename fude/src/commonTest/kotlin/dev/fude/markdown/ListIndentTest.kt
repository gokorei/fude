package dev.fude.markdown

import dev.fude.core.EditorState
import dev.fude.core.Edit
import dev.fude.core.Replace
import dev.fude.core.TextBuffer
import dev.fude.core.TextRange
import dev.fude.core.UndoStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Shift-Tab outdent.
 *
 * The property under test throughout is that outdent *refuses* at least as often as
 * it acts. A line with no level to lose must return null rather than an edit, and
 * the composable reports false for null so the platform can try its own path — the
 * alternative is Shift-Tab inserting a tab into prose, or deleting a user's
 * bullets, with neither being something they asked for.
 */
class ListIndentTest {

    private fun parse(text: String) = IncrementalMarkdownParser().parse(text)

    /** The whole document with the caret's line outdented, or null if it refused. */
    private fun applied(text: String, caret: Int = 0): String? {
        val edit = ListIndent.outdentAt(parse(text), caret) ?: return null
        return text.substring(0, edit.affectedRange.start) +
            (edit as Replace).inserted +
            text.substring(edit.affectedRange.end)
    }

    /** The whole document with [start]..[end] outdented, or null if it refused. */
    private fun rangeApplied(text: String, start: Int, end: Int): String? {
        val edit = ListIndent.outdentRange(parse(text), start, end) ?: return null
        return text.substring(0, edit.affectedRange.start) +
            (edit as Replace).inserted +
            text.substring(edit.affectedRange.end)
    }

    // --- the core transformation -------------------------------------------

    @Test
    fun anIndentedItemLosesOneLevelAndKeepsItsMarker() {
        assertEquals("- child", applied("    - child", 6))
    }

    @Test
    fun oneLevelOfFourSpacesComesOffNotAllOfThem() {
        assertEquals("  - deep", applied("      - deep", 9))
    }

    @Test
    fun eightColumnsComesOffInOneStepNotTwo() {
        // Outdent is one level at a time, so 8 columns becomes 4 and not 0.
        assertEquals("    - deep", applied("        - deep", 11))
    }

    @Test
    fun aTabIndentedItemOutdentsCleanly() {
        // One tab is column 4, so it leaves entirely. Trimming characters would
        // have left the tab behind and the line would still be indented.
        assertEquals("- child", applied("\t- child", 4))
    }

    // --- refusals -----------------------------------------------------------

    @Test
    fun aTopLevelItemHasNoShallowerLevelAndIsLeftAlone() {
        // Not a paragraph conversion. Obsidian does nothing here, and so do we.
        assertNull(ListIndent.outdentAt(parse("- item"), 3))
        assertNull(ListIndent.outdentAt(parse("1. item"), 3))
        assertNull(ListIndent.outdentAt(parse("- [ ] task"), 5))
    }

    @Test
    fun aPlainParagraphIsNotOutdented() {
        assertNull(ListIndent.outdentAt(parse("just prose"), 3))
    }

    @Test
    fun aHeadingIsNotOutdented() {
        assertNull(ListIndent.outdentAt(parse("# Title"), 3))
    }

    @Test
    fun indentedProseThatIsNotInAListIsNotOutdented() {
        // Leading whitespace is not indentation on its own. Only a list item has a
        // level to lose, so a stray indented paragraph is left alone.
        assertNull(ListIndent.outdentAt(parse("    continued"), 6))
    }

    @Test
    fun anEmptyDocumentIsNotOutdented() {
        assertNull(ListIndent.outdentAt(parse(""), 0))
    }

    // --- markers are never rewritten ---------------------------------------

    @Test
    fun everyBulletMarkerSurvives() {
        for (m in listOf("-", "*", "+")) {
            assertEquals("$m item", applied("    $m item", 7), "marker $m")
        }
    }

    @Test
    fun bothOrderedMarkersSurvive() {
        assertEquals("1. item", applied("    1. item", 7))
        assertEquals("1) item", applied("    1) item", 7))
    }

    @Test
    fun anOrderedMarkerKeepsItsNumber() {
        // Deliberate. Renumbering would be an edit the user did not ask for, and
        // the rendered number comes from the block tree regardless.
        assertEquals("3. child", applied("    3. child", 9))
    }

    @Test
    fun taskItemsKeepTheirBox() {
        assertEquals("- [ ] done", applied("    - [ ] done", 11))
        assertEquals("- [x] done", applied("    - [x] done", 11))
        assertEquals("- [X] done", applied("    - [X] done", 11))
    }

    // --- fenced code --------------------------------------------------------

    @Test
    fun aListMarkerInsideAFenceIsNotTreatedAsAList() {
        val text = "```\n    - not a list\n```"
        assertNull(ListIndent.outdentAt(parse(text), 8))
    }

    @Test
    fun aListOutsideAFenceIsStillOutdentedWhenAFenceIsElsewhere() {
        val text = "- item\n\n```\ncode\n```\n\n    - real"
        val edit = assertNotNull(ListIndent.outdentAt(parse(text), 26))
        // The fence sits between the two lists; it must be neither dedented nor
        // disturbed by an edit aimed at the list below it.
        assertEquals("- real", (edit as Replace).inserted)
    }

    // --- selection ----------------------------------------------------------

    @Test
    fun aSelectionOutdentsEveryLineItTouches() {
        val text = "    - a\n    - b\n    - c"
        val edit = assertNotNull(ListIndent.outdentRange(parse(text), 0, text.length))
        assertEquals("- a\n- b\n- c", (edit as Replace).inserted)
    }

    @Test
    fun aSelectionIsOneEditSpanningEveryLine() {
        val text = "    - a\n    - b\n    - c"
        val edit = assertNotNull(ListIndent.outdentRange(parse(text), 0, text.length))
        // One undo step for one gesture. Three separate edits here would mean three
        // undos, and undo coalescing is decided by edit adjacency, not by intent.
        assertEquals(TextRange(0, text.length), edit.affectedRange)
    }

    @Test
    fun aSelectionOfPlainLinesProducesNoEdit() {
        val text = "alpha\nbeta\ngamma"
        assertNull(ListIndent.outdentRange(parse(text), 0, text.length))
    }

    @Test
    fun aSelectionTouchingOneListLineOutdentsOnlyThatLine() {
        val text = "- top\n\n    - deep"
        assertEquals("- top\n\n- deep", rangeApplied(text, 8, text.length))
    }

    @Test
    fun aSelectionOfTopLevelItemsOnlyProducesNoEdit() {
        val text = "- a\n- b"
        assertNull(ListIndent.outdentRange(parse(text), 0, text.length))
    }

    @Test
    fun aReversedSelectionIsHandled() {
        val text = "    - a\n    - b"
        val forward = ListIndent.outdentRange(parse(text), 0, text.length)
        val reversed = ListIndent.outdentRange(parse(text), text.length, 0)
        assertEquals((forward as Replace).inserted, (reversed as Replace).inserted)
    }

    @Test
    fun aContinuationLineOfANestedItemMovesWithIt() {
        val text = "- parent\n    - child\n    continued"
        assertEquals("- parent\n- child\ncontinued", rangeApplied(text, 10, text.length))
    }

    // --- the rest of the document is never touched --------------------------

    @Test
    fun aDocumentWithNoTrailingNewlineIsUnaffectedBeyondTheLine() {
        assertEquals("- a\n- b", applied("    - a\n- b", 2))
    }

    @Test
    fun surroundingTextIsUntouched() {
        val text = "intro\n\n    - a\n\nouttro"
        assertEquals("intro\n\n- a\n\nouttro", applied(text, 12))
    }

    @Test
    fun aCaretOutdentsItsOwnLineAndNoOther() {
        // The bug this guards: running the line loop to end-of-document outdented the
        // block below the caret and swallowed it.
        assertEquals("- a\n\nouttro", applied("    - a\n\nouttro", 4))
    }

    @Test
    fun outdentIsIdempotentOnceDepthIsExhausted() {
        val text = "        - deep"
        var current = text
        var rounds = 0
        while (true) {
            val next = applied(current, current.length / 2) ?: break
            current = next
            rounds++
            if (rounds > 4) error("outdent did not converge")
        }
        assertEquals("- deep", current)
        assertEquals(2, rounds, "8 columns is two levels")
    }
}

/**
 * Outdent and undo together.
 *
 * Kept apart from [ListIndentTest] because this is a property of the pair — the
 * pure outdent and the history that records it — and the failure mode it guards
 * is specifically "one Shift-Tab, many undos", which neither test can see alone.
 */
class ListIndentUndoTest {

    /** Outdents the *whole* document as one selection — one gesture, one edit. */
    private fun outdented(text: String): Pair<EditorState, Edit> {
        val edit = assertNotNull(
            ListIndent.outdentRange(IncrementalMarkdownParser().parse(text), 0, text.length),
        )
        val before = EditorState(text = TextBuffer.of(text))
        val after = before.apply(edit)
        return after to edit
    }

    @Test
    fun oneOutdentOfManyLinesIsOneUndoStep() {
        val original = "    - a\n    - b\n    - c"
        val undo = UndoStack()
        val (after, edit) = outdented(original)
        undo.record(EditorState(text = TextBuffer.of(original)), after, edit)

        assertEquals(1, undo.undoDepth, "one gesture is one step")
        assertEquals("- a\n- b\n- c", after.text.text)

        val back = assertNotNull(undo.undo(after))
        assertEquals(original, back.text.text, "byte-for-byte, not approximately")
    }

    @Test
    fun aTaskItemOutdentUndoesExactly() {
        val original = "    - [x] done"
        val (after, edit) = outdented(original)
        val undo = UndoStack()
        undo.record(EditorState(text = TextBuffer.of(original)), after, edit)
        val back = assertNotNull(undo.undo(after))
        assertEquals(original, back.text.text)
    }

    @Test
    fun undoThenRedoReturnsToTheOutdentedForm() {
        val original = "        - deep"
        val (after, edit) = outdented(original)
        val undo = UndoStack()
        val before = EditorState(text = TextBuffer.of(original))
        undo.record(before, after, edit)

        val back = assertNotNull(undo.undo(after))
        val forward = assertNotNull(undo.redo(back))
        assertEquals("    - deep", forward.text.text, "one gesture removes one level")
    }
}
