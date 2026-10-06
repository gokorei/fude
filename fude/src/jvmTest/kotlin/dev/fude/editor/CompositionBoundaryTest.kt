package dev.fude.editor

import dev.fude.markdown.BlockViewState
import dev.fude.markdown.IncrementalMarkdownParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the library does and does not do about IME composition.
 *
 * `AP1Z9PXG` asks whether composition text survives live decoration. It cannot be
 * answered from here, and the reason is sharper than "needs a device".
 *
 * Two Compose APIs are internal to `compose-foundation`, and both are exactly the two
 * a test would need:
 *
 * - `TextFieldBuffer.setComposition` is `setComposition$foundation` on the JVM. No
 *   code outside that module can put an active composition on a buffer.
 * - `TextFieldBuffer` has no public constructor. Its only constructor takes
 *   `TextFieldCharSequence`, `ChangeTracker` and `OffsetMappingCalculator`, all
 *   foundation internals.
 *
 * So a test cannot construct the state it would need to observe, and cannot construct
 * the buffer that `applyDecoration` writes into. The only source of a real composition
 * is a real platform IME session.
 *
 * What *is* assertable is the boundary, and that is worth having. Fude holds no
 * composition state at all — it never reads `composition`, never commits one, and
 * never special-cases a selection because composition is active. That follows from
 * `E279AQ3R`, which deleted `input/` and made Compose the owner of typing. These
 * tests exist so that stays a decision rather than drifting.
 *
 * They also pin the one place Fude *is* exposed. `applyDecoration` adds a style per
 * span, on every frame, over whatever the platform has put in the buffer. If a
 * composing region can be disturbed by styling over it, that is where it happens.
 * Nothing here can prove it is safe on a real IME. It can prove the exposure is
 * confined to one function, and that every range reaching it is a valid offset into
 * the document — which is the precondition a composition region has to satisfy too,
 * since it is also expressed as absolute offsets into the same text.
 */
class CompositionBoundaryTest {

    @Test
    fun noPublicTypeInEitherModuleCarriesCompositionState() {
        // A guard, not a description. `E279AQ3R` deleted `CompositionSession` and
        // `ImeCommitter` and made Compose the owner of typing. If a future change
        // reintroduces composition state, this fails and the reviewer finds out that
        // it contradicts that decision — rather than a second, competing notion of
        // what is being composed quietly existing alongside the real one.
        val offenders = stateTypes()
            .filter { type -> type.declaredFields.any { it.name.contains("composition", ignoreCase = true) } }
            .map { it.simpleName }
        assertEquals(emptyList(), offenders, "composition state has re-entered the library")
    }

    @Test
    fun noPublicTypeExposesCompositionInItsApi() {
        val offenders = stateTypes()
            .filter { type ->
                type.methods.any { it.name.contains("composition", ignoreCase = true) }
            }
            .map { it.simpleName }
        assertEquals(emptyList(), offenders, "composition has re-entered the public API")
    }

    @Test
    fun everyDecorationRangeSitsInsideTheDocument() {
        // The one real exposure. `addStyle` throws on a range past the end, so a
        // decoration computed from a stale parse is a crash rather than a glitch —
        // and a crash while an IME is mid-composition is the worst possible time.
        val text = "**a** *b* `c` [d](e)\n\n```\nfence\n```\n\n# Title\n"
        val spans = decorationSpans(IncrementalMarkdownParser().parse(text), BlockViewState())

        assertTrue(spans.isNotEmpty(), "the fixture must actually decorate")
        for (s in spans) {
            assertTrue(
                s.range.start >= 0 && s.range.end <= text.length,
                "decoration ${s.range} escapes a ${text.length}-char document",
            )
        }
    }

    @Test
    fun decorationRangesAreAbsoluteOffsetsNotLineRelative() {
        // A composition region is an absolute offset range into the same text, so a
        // line-relative decoration range would be meaningless to one. This is a
        // precondition for the question `AP1Z9PXG` asks, and unlike the question it
        // is checkable here.
        val text = "first paragraph\n\n**second paragraph**\n"
        val spans = decorationSpans(IncrementalMarkdownParser().parse(text), BlockViewState())

        assertTrue(spans.isNotEmpty())
        for (s in spans) {
            // The strongest available statement: the span covers the literal source
            // text it was computed from. A line-relative range would cover the same
            // *column* of a different line and slice out unrelated text.
            val covered = text.substring(s.range.start, s.range.end)
            assertTrue(
                "second paragraph" in covered,
                "span ${s.range} covered '$covered', which is not the text it was derived from",
            )
        }
    }

    @Test
    fun decorationIsAnOverlayAndNeverChangesText() {
        // Decoration adds styles and no characters. If this failed, every
        // composition question would be moot because the text itself would already
        // be corrupted.
        val text = "# Title\n\n**bold** and [a](b) and `c`"
        val before = IncrementalMarkdownParser().parse(text)

        decorationSpans(before, BlockViewState())

        assertEquals(text, before.text, "collecting spans must not touch the document")
        assertEquals(text, IncrementalMarkdownParser().parse(text).text)
    }

    private fun stateTypes(): List<Class<*>> = listOf(
        dev.fude.core.EditorState::class.java,
        dev.fude.core.TextBuffer::class.java,
        dev.fude.core.TextRange::class.java,
        dev.fude.core.UndoController::class.java,
        dev.fude.core.Insert::class.java,
        dev.fude.core.Delete::class.java,
        dev.fude.core.Replace::class.java,
        dev.fude.markdown.ParsedDocument::class.java,
        dev.fude.markdown.BlockViewState::class.java,
        dev.fude.markdown.ListIndent::class.java,
    )
}
