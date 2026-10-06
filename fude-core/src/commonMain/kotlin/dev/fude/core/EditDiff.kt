package dev.fude.core

/**
 * Recovers an [Edit] from two whole texts.
 *
 * ## Why this exists
 *
 * Compose owns the text buffer during a gesture and reports the *result*, not the
 * keystroke: `onValueChange` hands over the entire new string. There is no insert,
 * delete or replace in what arrives, which is why undo grouping could not be fed
 * from the real editing path and why the README had to say so.
 *
 * This turns that whole-string report back into a single [Edit] so the same
 * [UndoGrouping] rules apply whether an edit arrived from the model API or from the
 * platform.
 *
 * ## The method
 *
 * Common prefix, then common suffix, and whatever is left between them is the
 * change. Cost is O(prefix + suffix): O(n) worst case — an append at the end
 * of a large document scans the whole shared prefix — not O(k) in the typed
 * character. Single-keystroke cost is small only when the edit is near the
 * start or shares a long suffix; the scan itself is never bounded by k.
 *
 * It deliberately finds *one* changed region rather than a general diff. An editor
 * sees one caret and one gesture at a time, so a multi-region diff would be more
 * machinery than the problem has, and a minimum-edit-distance diff would be
 * quadratic in exactly the large-document case this library cares about.
 *
 * Boundaries are **not** snapped outward to whole grapheme clusters.
 * Invariant: `edit.applyTo(before) == after` and `edit.inverse().applyTo(after) == before`
 * for every report, including partial-cluster removals. History: see
 * `docs/conformance.md` ("an edit report that splits a CRLF").
 */
fun editBetween(before: String, after: String): Edit? {
    if (before == after) return null

    val prefix = commonPrefix(before, after)

    // The suffix cannot overlap the prefix. Capping it at the shorter remaining
    // length is what keeps "abc" -> "abcabc" from producing a negative range.
    val suffix = commonSuffix(before, after, prefix)

    // Whatever is left between the prefix and the suffix is the change, and nothing
    // else. Both edges are therefore exactly `prefix` and `before.length - suffix` --
    // see the KDoc on why they are not widened -- and every character outside that
    // window is accounted for by the report as unchanged.
    //
    // `start` doubles as the offset into `after`, which is sound precisely because it
    // equals `prefix`: everything before `prefix` is shared verbatim, so the same
    // index names the same characters in both strings.
    val start = prefix
    val removedEnd = before.length - suffix
    val insertedEnd = after.length - suffix

    val removed = before.substring(start, removedEnd)
    val inserted = after.substring(start, insertedEnd)

    return when {
        removed.isEmpty() && inserted.isEmpty() -> null
        removed.isEmpty() -> Insert(start, inserted)
        inserted.isEmpty() -> Delete(TextRange(start, removedEnd), removed)
        else -> Replace(TextRange(start, removedEnd), removed, inserted)
    }
}

/** How many characters [a] and [b] share from the start. Hand-rolled, deliberately:
 * `commonPrefixWith` disagrees on lone surrogates (returns 0 where 1 matches),
 * which splits emoji ZWJ runs into 6 undo steps instead of 1. */
private fun commonPrefix(a: String, b: String): Int {
    val limit = minOf(a.length, b.length)
    var i = 0
    while (i < limit && a[i] == b[i]) i++
    return i
}

/**
 * How many characters [a] and [b] share from the end, never reaching past [floor].
 *
 * The floor is the shared-prefix length: a suffix counted from further back would
 * re-count characters the prefix already claimed.
 */
private fun commonSuffix(a: String, b: String, floor: Int): Int {
    val limit = minOf(a.length, b.length) - floor
    var i = 0
    while (i < limit && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
    return i
}

