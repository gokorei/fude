package dev.fude.core

/**
 * Where undo time comes from.
 *
 * Injected rather than called directly so the idle-timeout rule is testable
 * without sleeping, and so no platform clock leaks into `commonMain`.
 */
public fun interface TimeSource {
    public fun nowMillis(): Long

    public companion object {
        /** A clock that does not move. Tests that do not care about time use this. */
        public val FIXED: TimeSource = TimeSource { 0L }

        /** A clock the test drives by hand. */
        public class Manual(private var now: Long = 0L) : TimeSource {
            override fun nowMillis(): Long = now

            public fun advance(millis: Long) {
                now += millis
            }
        }
    }
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
public class UndoGrouping(
    /** How long after the last edit a new one still counts as the same run. */
    public val idleTimeoutMillis: Long = 800L,
    private val boundaryCharacters: Set<Char> = DEFAULT_BOUNDARY_CHARACTERS,
    /** When false, every edit is its own undo step. */
    public val coalesceTyping: Boolean = true,
    private val clock: TimeSource = TimeSource.FIXED,
) {
    private var lastEditAt: Long = Long.MIN_VALUE

    /** Milliseconds since the last recorded edit, or [Long.MAX_VALUE] if there was none. */
    public fun millisSinceLastEdit(): Long {
        val last = lastEditAt
        if (last == Long.MIN_VALUE) return Long.MAX_VALUE
        return clock.nowMillis() - last
    }

    public fun recordEditTime() {
        lastEditAt = clock.nowMillis()
    }

    /**
     * Whether [incoming] should join the run that ended with [previous].
     *
     * Breaks on: a non-typing edit, a moved caret, non-contiguous typing, a
     * boundary character, a newline, and an idle gap.
     */
    public fun shouldMerge(
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

        if (millisSincePreviousEdit != Long.MAX_VALUE && millisSincePreviousEdit > idleTimeoutMillis) {
            return false
        }

        return true
    }

    private fun Char.isBoundary(): Boolean = this in boundaryCharacters

    public companion object {
        /**
         * Characters that end a coalescing run.
         *
         * Enter, carriage return and tab are structural. Space is not.
         */
        public val DEFAULT_BOUNDARY_CHARACTERS: Set<Char> = setOf('\n', '\r', '\t')
    }
}
