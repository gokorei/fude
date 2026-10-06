package dev.fude.markdown

/**
 * Counts full parses.
 *
 * Extracted from IncrementalMarkdownParser so the parser file holds the
 * parse/reparse orchestration and this unit holds the instrument. Behaviour
 * unchanged; see its KDoc in git history for the counting contract.
 */
class ReparseCounter {
    var fullParses: Int = 0
        private set

    var blockParses: Int = 0
        private set

    fun recordFullParse() {
        fullParses++
    }

    fun recordBlockParse() {
        blockParses++
    }

    fun reset() {
        fullParses = 0
        blockParses = 0
    }
}
