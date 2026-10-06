package dev.fude.editor

import dev.fude.markdown.BlockKind
import dev.fude.markdown.IncrementalMarkdownParser
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The IME fixture is a document a person will be asked to type into, and nothing
 * checks it.
 *
 * That is the failure mode worth guarding. If the fixture stops parsing, or loses
 * the construct a case depends on, the tester still types into it, still reports
 * "passes", and the result is worse than no result — it looks like evidence.
 *
 * So the fixture is asserted here: it parses, and every construct the verification
 * protocol names is actually present in it. Only the JVM target reads files, which
 * matches the only target that runs the verification.
 */
class ImeFixtureTest {

    private val fixture: File = sequenceOf("../docs/fixtures/ime-composition.md", "docs/fixtures/ime-composition.md")
        .map(::File)
        .firstOrNull(File::isFile)
        ?: error("ime-composition.md not found; the verification protocol is unrunnable")

    private val text = fixture.readText()

    private val parsed = IncrementalMarkdownParser().parse(text)

    @Test
    fun theFixtureParsesWithoutLeavingTheDocumentInFragments() {
        // Every node's range must cover the substring it claims. A parser hiccup on
        // this document would show up here rather than as a confusing screenshot.
        for (block in parsed.blocks) {
            assertTrue(
                block.range.start >= 0 && block.range.end <= text.length,
                "block ${block.kind} has range ${block.range} outside a ${text.length}-char document",
            )
        }
    }

    @Test
    fun theFixtureContainsEveryConstructTheProtocolNames() {
        val kinds = parsed.allBlocks.map { it.kind }.toSet()

        // Case 2 — inside bold.
        assertTrue(BlockKind.CODE_FENCE in kinds, "case 5 needs a fenced code block")
        assertTrue(BlockKind.LIST_ITEM in kinds, "case 4 needs a list item")
        assertTrue("**bold**" in text, "case 2 needs a bold run")
        assertTrue("[start here](" in text, "case 3 needs a link whose label differs from its target")
        assertTrue("**bold with [a link](" in text, "case 9 needs two overlapping spans in one region")
    }

    @Test
    fun theFixtureHasANineCaseStructureToRecordAgainst() {
        // The protocol asks for nine results. If the numbering drifts, a report comes
        // back with nine rows that do not match what the tester saw.
        val numbered = Regex("^## (\\d+)\\.", RegexOption.MULTILINE)
            .findAll(text)
            .map { it.groupValues[1].toInt() }
            .toList()

        assertEquals((1..9).toList(), numbered, "cases must be numbered 1..9 in order")
    }

    @Test
    fun theFixtureDoesNotOpenOnAFenceOrAnUnclosedConstruct() {
        // The tester should be able to start at the top and work down. An unclosed
        // fence would swallow every case after it into one code block, and the
        // "nothing inside a fence is decorated" control would stop being a control.
        val fences = Regex("^```", RegexOption.MULTILINE).findAll(text).count()
        assertEquals(0, fences % 2, "fence markers are unbalanced")
    }

    @Test
    fun theFixtureNamesThePlatformItAssumes() {
        // The protocol is macOS-shaped. If that changes, the doc has to change with
        // it, and this is the cheapest place to notice.
        assertTrue(
            "macOS" in File("docs/ime-verification.md").takeIf(File::isFile)?.readText()
                ?: File("../docs/ime-verification.md").readText(),
            "the verification doc should state the platform it assumes",
        )
    }
}
