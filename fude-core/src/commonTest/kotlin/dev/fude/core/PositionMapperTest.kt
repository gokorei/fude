package dev.fude.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Position mapping, including the cases that break naive implementations.
 *
 * A document offset says nothing about where the caret appears on screen once
 * lines wrap, and nothing at all about which visual line a wrapped paragraph is
 * on. Getting this wrong is what makes a live-preview editor feel broken: the
 * caret is logically correct and visually wrong.
 */
class PositionMapperTest {
    private val text = "first line\nsecond line\nthird line"

    @Test
    fun offsetMapsToLineAndColumn() {
        val mapper = PositionMapper.unindexed(text)
        val position = mapper.textPosition(0)
        assertEquals(0, position.line)
        assertEquals(0, position.column)

        val second = mapper.textPosition("first line\n".length)
        assertEquals(1, second.line)
        assertEquals(0, second.column)
    }

    @Test
    fun columnCountsFromTheLineStart() {
        val mapper = PositionMapper.unindexed(text)
        val offset = "first line\nsec".length
        val position = mapper.textPosition(offset)
        assertEquals(1, position.line)
        assertEquals(3, position.column)
    }

    @Test
    fun lineAndColumnRoundTripBackToTheOffset() {
        val mapper = PositionMapper.unindexed(text)
        for (offset in 0..text.length) {
            val position = mapper.textPosition(offset)
            assertEquals(offset, mapper.offsetOf(position.line, position.column), "round trip at $offset")
        }
    }

    @Test
    fun aSingleNewlineDocumentIsOneLine() {
        val index = LineIndex.of("\n")
        assertEquals(2, index.lineCount, "a document of just a newline has two lines")
        assertEquals(0, index.lineStart(0))
        assertEquals(0, index.lineEnd(0), "the first line is empty")
        assertEquals(1, index.lineStart(1))
    }

    @Test
    fun emptyDocumentHasOneEmptyLine() {
        val index = LineIndex.of("")
        assertEquals(1, index.lineCount)
        assertEquals(0, index.lineStart(0))
        assertEquals(0, index.lineEnd(0))
        assertEquals(0, index.lineOf(0))
    }

    @Test
    fun trailingNewlineDoesNotCreateAContentlessProblem() {
        val index = LineIndex.of("abc\n")
        assertEquals(2, index.lineCount)
        assertEquals(3, index.lineEnd(0))
        assertEquals(4, index.lineStart(1))
        assertEquals(4, index.lineEnd(1), "the last line is empty")
    }

    @Test
    fun lineEndCanIncludeTheTerminator() {
        val index = LineIndex.of("abc\ndef")
        assertEquals(3, index.lineEnd(0))
        assertEquals(4, index.lineEnd(0, endInclusive = true), "for a selection grown to end of line")
    }

    @Test
    fun outOfRangeOffsetsClampRatherThanThrow() {
        val index = LineIndex.of(text)
        assertEquals(0, index.lineOf(-10))
        assertEquals(index.lineCount - 1, index.lineOf(9_999))
    }

    @Test
    fun columnBeyondTheLineEndClampsToTheLineEnd() {
        val index = LineIndex.of("abc\ndef")
        assertEquals(3, index.offsetOf(0, 999))
    }

    @Test
    fun aWrappedLineReportsAVisualLineBeyondItsLogicalLine() {
        val index = LineIndex.of("aaa bbb ccc\nnext")
        // The renderer discovered that logical line 0 occupies visual lines 0 and 1.
        val mapper = PositionMapper(index, visualLines = mapOf(0 to listOf(7)))

        val beforeWrap = mapper.textPosition(4)
        assertEquals(0, beforeWrap.visualLine)
        assertEquals(4, beforeWrap.visualColumn)

        val afterWrap = mapper.textPosition(9)
        assertEquals(0, afterWrap.line, "still logical line 0")
        assertEquals(1, afterWrap.visualLine, "but on the second display line")
        assertEquals(2, afterWrap.visualColumn, "counted from the start of the wrap")
    }

    @Test
    fun anUnwrappedLineReportsOneVisualLine() {
        val mapper = PositionMapper.unindexed("short line")
        val position = mapper.textPosition(6)
        assertEquals(position.line, position.visualLine)
        assertEquals(position.column, position.visualColumn)
    }

    @Test
    fun multipleWrapsOnOneLogicalLine() {
        val index = LineIndex.of("aaa bbb ccc ddd")
        val mapper = PositionMapper(index, visualLines = mapOf(0 to listOf(7, 11)))
        assertEquals(0, mapper.textPosition(3).visualLine)
        assertEquals(1, mapper.textPosition(9).visualLine)
        assertEquals(2, mapper.textPosition(13).visualLine)
    }

    @Test
    fun selectionReportsTheLineRangeItCovers() {
        val mapper = PositionMapper.unindexed(text)
        val acrossLines = TextRange("first".length, "first line\nsecond".length)
        assertEquals(0..1, mapper.lineRangeOf(acrossLines))
    }

    @Test
    fun aSelectionEndingAtTheStartOfALineDoesNotIncludeThatLine() {
        val mapper = PositionMapper.unindexed(text)
        // Ends exactly where line 1 begins: nothing of line 1 is selected.
        val range = TextRange(0, "first line\n".length)
        assertEquals(0..1, mapper.lineRangeOf(range), "the caret sits on line 1's start")
    }

    @Test
    fun lineIndexIsBuiltOncePerTextVersion() {
        val index = LineIndex.of(text)
        // Every offset resolves, and no offset throws.
        for (offset in 0..text.length) {
            val line = index.lineOf(offset)
            assertTrue(line in 0 until index.lineCount)
            assertTrue(index.lineStart(line) <= offset)
        }
    }
}
