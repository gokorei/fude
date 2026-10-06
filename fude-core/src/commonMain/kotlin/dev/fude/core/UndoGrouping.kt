package dev.fude.core

/**
 * Where undo time comes from.
 *
 * Injected rather than called directly so the idle-timeout rule is testable
 * without sleeping, and so no platform clock leaks into `commonMain`.
 */
fun interface TimeSource {
    fun nowMillis(): Long

    companion object {
        /** A clock that does not move. Tests that do not care about time use this. */
        val FIXED: TimeSource = TimeSource { 0L }

        /**
         * A clock that moves, for production use.
         *
         * Monotonic rather than wall-clock: only gaps between keystrokes matter,
         * so a user changing the system clock must not split or join an undo run.
         * Implemented on `kotlin.time` so no `expect`/`actual` leaks a platform
         * clock into `commonMain`.
         */
        val SYSTEM: TimeSource = SystemClock

        /** A clock the test drives by hand. */
        class Manual(private var now: Long = 0L) : TimeSource {
            override fun nowMillis(): Long = now

            fun advance(millis: Long) {
                now += millis
            }
        }
    }
}

private object SystemClock : dev.fude.core.TimeSource {
    private val origin = kotlin.time.TimeSource.Monotonic.markNow()
    override fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}

/**
 * Decides when consecutive edits become one undo step.
 *
 * The rule: coalesce while the user is still typing the same kind of thing, and
 * break the run on anything that signals a new intent.
 *
 * Space is deliberately **not** a break. "hello world" is one undo step, because
 * that is what a person means by typing a word — and a rule that undoes it as
 * three steps is the complaint that makes an editor feel wrong.
 */
class UndoGrouping(
    /** How long after the last edit a new one still counts as the same run. */
    val idleTimeoutMillis: Long = 800L,
    private val boundaryCharacters: Set<Char> = DEFAULT_BOUNDARY_CHARACTERS,
    /** When false, every edit is its own undo step. */
    val coalesceTyping: Boolean = true,
    private val clock: TimeSource = TimeSource.SYSTEM,
) {
    private var lastEditAt: Long = Long.MIN_VALUE

    /** Milliseconds since the last recorded edit, or [Long.MAX_VALUE] if there was none. */
    fun millisSinceLastEdit(): Long {
        val last = lastEditAt
        if (last == Long.MIN_VALUE) return Long.MAX_VALUE
        return clock.nowMillis() - last
    }

    fun recordEditTime() {
        lastEditAt = clock.nowMillis()
    }

    /**
     * Whether [incoming] should join the run that ended with [previous].
     *
     * Breaks on: a non-typing edit, a moved caret, non-contiguous typing, a
     * boundary character, a newline, and an idle gap.
     */
    fun shouldMerge(
        previous: Edit,
        incoming: Edit,
        selectionAfterPrevious: TextRange,
        selectionBeforeIncoming: TextRange,
        millisSincePreviousEdit: Long = millisSinceLastEdit(),
    ): Boolean {
        if (!coalesceTyping) return false

        // Only pure insertions of typing characters coalesce. A delete, a paste,
        // or an IME commit is a discrete intent, not continued typing.
        if (previous !is Insert || incoming !is Insert) return false

        // The caret must be continuing where it was. Moving and typing elsewhere
        // is a new intent.
        if (selectionAfterPrevious != selectionBeforeIncoming) return false

        // Typing must be contiguous: the new insert starts where the last ended.
        val lastEnd = previous.affectedRange.start + previous.text.length
        if (incoming.offset != lastEnd) return false

        // A structural character ends the run. A space does not — see above: the
        // whole point is that "hello world" is one step, not three.
        if (incoming.text.any { it.isBoundary() }) return false

        // A run does not resume immediately after a structural character either.
        //
        // Without this, Enter joins the *following* word instead of standing alone:
        // the newline fails the check above, so the next character is compared
        // against it, and "t" happily merges with "\n". The user then needs one undo
        // to remove a word and the paragraph break together, which is not what
        // pressing Enter means.
        if (previous.text.any { it.isBoundary() }) return false

        if (millisSincePreviousEdit != Long.MAX_VALUE && millisSincePreviousEdit > idleTimeoutMillis) {
            return false
        }

        return true
    }

    private fun Char.isBoundary(): Boolean = this in boundaryCharacters

    companion object {
        /**
         * Characters that end a coalescing run.
         *
         * Enter, carriage return and tab are structural. Space is not.
         */
        val DEFAULT_BOUNDARY_CHARACTERS: Set<Char> = setOf('\n', '\r', '\t')
    }
}
