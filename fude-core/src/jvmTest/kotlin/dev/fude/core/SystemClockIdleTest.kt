package dev.fude.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RBXK34GY: production undo grouping never saw idle time because the default
 * clock was FIXED at 0. These tests run against the production default stack —
 * no explicit clock — so they fail while the default is FIXED and pass with
 * the moving SYSTEM clock.
 */
class SystemClockIdleTest {
    @Test
    fun systemClockMoves() {
        val before = TimeSource.SYSTEM.nowMillis()
        Thread.sleep(50)
        val after = TimeSource.SYSTEM.nowMillis()
        assertTrue(after > before, "SYSTEM must move, got $before -> $after")
    }

    @Test
    fun defaultStackSplitsAfterARealIdleGap() {
        val undo = UndoStack()
        var state = EditorState.of("")

        for (character in "ab") {
            val next = state.insert(character.toString())
            undo.record(state, next, Insert(state.selection.start, character.toString()))
            state = next
        }
        assertEquals(1, undo.undoDepth, "fast typing is one step")

        Thread.sleep(900)

        for (character in "cd") {
            val next = state.insert(character.toString())
            undo.record(state, next, Insert(state.selection.start, character.toString()))
            state = next
        }

        assertEquals("abcd", state.text.text)
        assertEquals(2, undo.undoDepth, "a 900ms pause splits the run")

        val undone = undo.undo(state)!!
        assertEquals("ab", undone.text.text, "one undo removes only the run after the pause")
    }
}
