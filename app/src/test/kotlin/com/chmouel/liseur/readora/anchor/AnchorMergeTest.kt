package com.chmouel.liseur.readora.anchor

import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * The merge rule a second selection of the same passage goes through.
 * The four cases the product promised: re-selecting what is already
 * marked (one mark, not two), the same sentence in a different place
 * (two marks, the flanks tell them apart), a passage at the very top of
 * a file (no before-context to compare, still mergeable), and a
 * selection spanning paragraphs (merge on the whole passage).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AnchorMergeTest {

    private fun locator(
        href: String = "https://example.com/chapter1.xhtml",
        highlight: String?,
        before: String? = null,
        after: String? = null,
    ): Locator = requireNotNull(
        Locator.fromJSON(
            JSONObject()
                .put("href", href)
                .put("type", "application/xhtml+xml")
                .put(
                    "text",
                    JSONObject().apply {
                        highlight?.let { put("highlight", it) }
                        before?.let { put("before", it) }
                        after?.let { put("after", it) }
                    },
                ),
        ),
    )

    private fun mark(
        id: String,
        locator: Locator,
        kind: AnnotationKind = AnnotationKind.HIGHLIGHT,
    ): BookAnnotation {
        val json = JSONObject()
            .put("href", locator.href.toString())
            .put("type", "application/xhtml+xml")
            .put("text", JSONObject().apply {
                locator.text.highlight?.let { put("highlight", it) }
                locator.text.before?.let { put("before", it) }
                locator.text.after?.let { put("after", it) }
            })
        return BookAnnotation(
            id = id,
            bookId = "book",
            kind = kind.name,
            locatorJson = json.toString(),
            text = locator.text.highlight,
            createdAt = 1L,
        )
    }

    @Test
    fun `re-selecting the same passage merges onto the existing mark`() {
        val existing = mark(
            "mark-1",
            locator(
                highlight = "阅读是一种主动思考的过程",
                before = "位置标记 C1-P01。",
                after = "我们先理解作者提出的问题。",
            ),
        )

        val again = AnchorMerge.existingFor(
            locator(
                highlight = "阅读是一种主动思考的过程",
                before = "位置标记 C1-P01。",
                after = "我们先理解作者提出的问题。",
            ),
            listOf(existing),
        )

        assertEquals("mark-1", again?.id)
    }

    @Test
    fun `the same sentence in a different paragraph stays its own mark`() {
        val existing = mark(
            "mark-1",
            locator(
                highlight = "阅读是一种主动思考的过程",
                before = "位置标记 C1-P01。",
                after = "我们先理解作者提出的问题。",
            ),
        )

        val elsewhere = AnchorMerge.existingFor(
            locator(
                highlight = "阅读是一种主动思考的过程",
                before = "位置标记 C2-P07。上一句的结尾。",
                after = "本章的另一处论述继续展开。",
            ),
            listOf(existing),
        )

        assertNull(elsewhere)
    }

    @Test
    fun `a passage at the very top of a file still merges`() {
        val existing = mark(
            "mark-top",
            locator(
                highlight = "开篇第一句",
                before = "",
                after = "第二句紧随其后展开了全书的论述。",
            ),
        )

        val again = AnchorMerge.existingFor(
            locator(
                highlight = "开篇第一句",
                before = "",
                after = "第二句紧随其后展开了全书的论述。",
            ),
            listOf(existing),
        )

        assertEquals("mark-top", again?.id)
    }

    @Test
    fun `a selection spanning paragraphs merges on the whole passage`() {
        val crossing = "第一段的结尾。第二段的开头。第三段的起始。"
        val existing = mark(
            "mark-cross",
            locator(highlight = crossing, before = "第一段的开始之前。", after = "第三段之后的内容。"),
        )

        val again = AnchorMerge.existingFor(
            locator(highlight = crossing, before = "第一段的开始之前。", after = "第三段之后的内容。"),
            listOf(existing),
        )

        assertEquals("mark-cross", again?.id)
    }

    @Test
    fun `a different file never merges however alike the passage`() {
        val existing = mark(
            "mark-1",
            locator(
                highlight = "阅读是一种主动思考的过程",
                before = "位置标记 C1-P01。",
                after = "我们先理解作者提出的问题。",
            ),
        )

        val otherFile = AnchorMerge.existingFor(
            locator(
                href = "https://example.com/chapter2.xhtml",
                highlight = "阅读是一种主动思考的过程",
                before = "位置标记 C1-P01。",
                after = "我们先理解作者提出的问题。",
            ),
            listOf(existing),
        )

        assertNull(otherFile)
    }

    @Test
    fun `a hand-dragged wordless selection never merges`() {
        val existing = mark("mark-1", locator(highlight = "有字的段落", before = "前文", after = "后文"))

        assertNull(AnchorMerge.existingFor(locator(highlight = null), listOf(existing)))
        assertNull(AnchorMerge.existingFor(locator(highlight = ""), listOf(existing)))
    }

    @Test
    fun `bookmarks and book notes are not anchors to merge with`() {
        val bookmark = mark(
            "bm",
            locator(highlight = "阅读是一种主动思考的过程", before = "前文", after = "后文"),
            kind = AnnotationKind.BOOKMARK,
        )

        assertNull(
            AnchorMerge.existingFor(
                locator(highlight = "阅读是一种主动思考的过程", before = "前文", after = "后文"),
                listOf(bookmark),
            ),
        )
    }
}
