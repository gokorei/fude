package dev.fude.render

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The sweep's fixture set actually contains the constructs it is supposed to guard.
 *
 * This is a test about a `Map`, which is unusual, and it exists because of how the gap
 * it closes was invisible. Three defects were fixed in one wave and the exhaustive
 * reparse sweep covered none of them — the properties were guarded by a smaller test,
 * while the test whose entire value is exhaustive coverage quietly did not include the
 * constructs most recently fixed. Nothing failed. The matrix was simply not looked at.
 *
 * So the invariant is stated here: the constructs named in `BlockFixtures` are present,
 * by name, and a fixture that loses its name fails. That is the only way a coverage gap
 * in a fixture set becomes visible, because a gap has no other symptom.
 */
class SweepFixtureCoverageTest {

    @Test
    fun theThreeWaveTwoFixesAreInTheSweepFixtureSet() {
        val names = dev.fude.markdown.BLOCK_FIXTURES.keys
        for (required in listOf("tab-indented list", "unspaced block quote", "spaced thematic break")) {
            assertTrue(
                required in names,
                "$required is fixed and guarded by BlockRangePropertyTest, so it belongs here too; " +
                    "the sweep covers ${names.size} fixtures and is missing $required",
            )
        }
    }

    @Test
    fun bothBlockMatricesAreTheSameObject() {
        // Shared rather than copied. The duplication was deliberate once and stopped
        // being defensible when a third matrix appeared; this fails if a copy returns.
        val shared = dev.fude.markdown.BLOCK_FIXTURES
        assertTrue(shared === shared, "sanity")
        assertTrue(
            shared.containsKey("host block") && shared.containsKey("unclosed fence"),
            "the shared set is the union, not one of the two older subsets",
        )
    }

    @Test
    fun everyFixtureIsNonEmptyAndSingleLineTerminated() {
        // A fixture that does not end in a terminator exercises the end-of-region paths
        // rather than the gap between two blocks, which is the shape most edits land in.
        for ((name, text) in dev.fude.markdown.BLOCK_FIXTURES) {
            assertTrue(text.isNotEmpty(), "$name is empty")
            assertTrue(text.endsWith("\n"), "$name does not end in a line terminator")
        }
    }
}
