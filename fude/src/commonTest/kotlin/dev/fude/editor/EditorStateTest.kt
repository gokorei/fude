package dev.fude.editor

import androidx.compose.ui.text.TextRange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EditorStateTest {
    @Test
    fun editMovesTheCaretToTheEndByDefault() {
        val state = EditorState()
        state.edit("hello")
        assertEquals("hello", state.text)
        assertEquals(5, state.selection.start)
        assertEquals(5, state.selection.end)
    }

    @Test
    fun aSelectionOutsideTheNewTextIsRejected() {
        val state = EditorState()
        assertFailsWith<IllegalArgumentException> {
            state.edit("hi", TextRange(0, 99))
        }
        assertEquals("", state.text, "a rejected edit must not have been applied")
    }
}
