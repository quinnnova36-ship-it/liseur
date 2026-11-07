package com.chmouel.liseur.readora.anchor

import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import org.readium.r2.shared.publication.Locator
import com.chmouel.liseur.reader.annotations.locator

/**
 * Decides whether a fresh selection is the same mark the reader already
 * made, so a passage picked twice is one mark rather than two.
 *
 * Readora S2. The upstream bar answers this only for a mark the reader
 * tapped (its id rides along with the selection); a fresh selection of
 * the same words always made a new row. That is the gap this closes.
 *
 * The rule is deliberately narrow: same file ([Locator.href]), same
 * passage, and the same words on both flanks. Locator.text is a bounded
 * snapshot Readium takes at selection time, so the flanks carry at most
 * a screenful each — comparing the overlap that exists, not requiring a
 * length the book may not provide, is what keeps a mark at the very
 * start of a file or a chapter from being unmergeable.
 *
 * Marks with a blank passage (a decoration the reader dragged by hand)
 * never merge: two wordless selections carry no evidence of place.
 */
object AnchorMerge {

    /** How many flank characters must agree when both sides have them. */
    private const val FLANK = 16

    /**
     * The mark [selection] already stands for, or null when it is a new
     * one. Only highlights and passage notes can merge; bookmarks and
     * book notes have no passage to compare.
     */
    fun existingFor(
        selection: Locator,
        marks: List<BookAnnotation>,
    ): BookAnnotation? {
        val passage = selection.text.highlight?.takeIf { it.isNotBlank() } ?: return null
        val before = selection.text.before.orEmpty()
        val after = selection.text.after.orEmpty()
        return marks.firstOrNull { mark ->
            isSameAnchor(mark, selection.href.toString(), passage, before, after)
        }
    }

    private fun isSameAnchor(
        mark: BookAnnotation,
        href: String,
        passage: String,
        before: String,
        after: String,
    ): Boolean {
        if (mark.kind == AnnotationKind.BOOKMARK.name || mark.kind == AnnotationKind.BOOK_NOTE.name) {
            return false
        }
        val locator = mark.locator() ?: return false
        if (locator.href.toString() != href) return false
        val markPassage = locator.text.highlight?.takeIf { it.isNotBlank() } ?: return false
        if (markPassage != passage) return false
        return flanksAgree(before, locator.text.before.orEmpty()) &&
            flanksAgree(after, locator.text.after.orEmpty())
    }

    /**
     * True when the shorter flank is long enough to be evidence and the
     * two agree on all of it. A flank shorter than [FLANK] still counts
     * when it is all the room the book gives: at the very top of a file
     * there is nothing before the first passage, and that is a place,
     * not a coincidence.
     */
    private fun flanksAgree(a: String, b: String): Boolean {
        val shorter = minOf(a.length, b.length)
        if (a.length < FLANK || b.length < FLANK) {
            // One side simply ends (start/end of file): the overlap that
            // exists is all the evidence there is, and both sides must
            // end in the same place for it to count.
            return a.take(shorter) == b.take(shorter) && a.length == b.length
        }
        return a.takeLast(FLANK) == b.takeLast(FLANK) || a.take(FLANK) == b.take(FLANK)
    }
}
