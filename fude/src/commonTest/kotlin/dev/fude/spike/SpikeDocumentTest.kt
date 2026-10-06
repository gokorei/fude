package dev.fude.spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpikeDocumentTest {
    @Test
    fun documentReachesTheLargeDocumentTarget() {
        assertTrue(SpikeDocument.lineCount >= SpikeDocument.LARGE_DOCUMENT_LINES, "got ${SpikeDocument.lineCount} lines")
    }

    @Test
    fun caretOffsetLandsInsideTheEmphasisMarkers() {
        val offset = SpikeDocument.caretOffset
        val text = SpikeDocument.full
        assertEquals('r', text[offset], "caret should sit inside the word, past the opening markers")
        assertEquals("**ca", text.substring(offset - 4, offset))
    }
}
