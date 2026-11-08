package com.chmouel.liseur.readora.discussion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
/**
 * What the context builder sends beside a question.
 *
 * The four promises: the passage with its neighbouring paragraphs, a
 * passage at a file's edge without its missing neighbour, a passage the
 * walk cannot find degraded to itself with a note, and a long window
 * bounded with the passage kept whole.
 */
class ContextBuilderTest {

    private fun locator(highlight: String, href: String = "https://example.com/c1.xhtml"): Locator =
        requireNotNull(
            Locator.fromJSON(
                org.json.JSONObject()
                    .put("href", href)
                    .put("type", "application/xhtml+xml")
                    .put("text", org.json.JSONObject().put("highlight", highlight)),
            ),
        )

    private fun paragraphs(vararg texts: String): List<Pair<String, Locator?>> =
        texts.map { it to null }

    @Test
    fun `the passage travels with both neighbours`() {
        val context = ContextBuilder.build(
            bookTitle = "书名",
            chapterTitle = "第一章",
            selection = locator("中间这一句被选中"),
            paragraphs = paragraphs("前一段的内容。", "中间这一句被选中", "后一段的内容。"),
            history = emptyList(),
        )

        assertEquals("前一段的内容。", context.paragraphBefore)
        assertEquals("后一段的内容。", context.paragraphAfter)
        assertEquals("中间这一句被选中", context.passage)
        assertNull(context.limitation)
    }

    @Test
    fun `a passage at the top of a file has no before`() {
        val context = ContextBuilder.build(
            "书名", null,
            locator("开篇第一句"),
            paragraphs("开篇第一句", "第二句", "第三句"),
            emptyList(),
        )

        assertNull(context.paragraphBefore)
        assertEquals("第二句", context.paragraphAfter)
        assertNull(context.limitation)
    }

    @Test
    fun `a passage at the end of a file has no after`() {
        val context = ContextBuilder.build(
            "书名", null,
            locator("收尾的最后一句"),
            paragraphs("倒数第二句", "收尾的最后一句"),
            emptyList(),
        )

        assertEquals("倒数第二句", context.paragraphBefore)
        assertNull(context.paragraphAfter)
    }

    @Test
    fun `a passage the walk cannot find degrades to itself with a note`() {
        val context = ContextBuilder.build(
            "书名", null,
            locator("书里确实有但我没读到的一句"),
            paragraphs("别的段落。", "另一个段落。"),
            emptyList(),
        )

        assertNull(context.paragraphBefore)
        assertNull(context.paragraphAfter)
        assertEquals("书里确实有但我没读到的一句", context.passage)
        assertTrue(context.limitation!!.contains("仅发送选中原句"))
    }

    @Test
    fun `a wordless selection says so rather than sending nothing`() {
        val context = ContextBuilder.build(
            "书名", null,
            locator(""),
            paragraphs("有字的段落。"),
            emptyList(),
        )

        assertTrue(context.passage.contains("无文字选区"))
        assertNull(context.paragraphBefore)
        assertTrue(context.limitation!!.contains("未附上下文"))
    }

    @Test
    fun `an oversized window is bounded with the passage kept whole`() {
        val longBefore = "前".repeat(3000)
        val longAfter = "后".repeat(3000)
        val context = ContextBuilder.build(
            "书名", null,
            locator("这一句必须完整保留"),
            paragraphs(longBefore, "这一句必须完整保留", longAfter),
            emptyList(),
        )

        assertEquals("这一句必须完整保留", context.passage)
        assertTrue(context.limitation!!.contains("截断"))
        val total = (context.paragraphBefore?.length ?: 0) + (context.paragraphAfter?.length ?: 0)
        assertTrue(total <= ContextBuilderMaxTotalForTest)
    }

    @Test
    fun `history is bounded to the last twelve turns`() {
        val history = (1..20).map { "user" to "第 $it 轮" }
        val context = ContextBuilder.build(
            "书名", null,
            locator("选中"),
            paragraphs("前", "选中", "后"),
            history,
        )

        assertEquals(12, context.history.size)
        assertEquals("第 9 轮", context.history.first().second)
    }

    @Test
    fun `the composed message quotes the book and asks plainly`() {
        val context = ContextBuilder.build(
            "书名", "第一章",
            locator("选中"),
            paragraphs("前文", "选中", "后文"),
            emptyList(),
        )
        val body = context.compose("这句话什么意思？")

        assertTrue(body.contains("《选中原句》\n选中"))
        assertTrue(body.contains("《前文》\n前文"))
        assertTrue(body.contains("《后文》\n后文"))
        assertTrue(body.trim().endsWith("这句话什么意思？"))
    }

    @Test
    fun `the snapshot keeps every part for later inspection`() {
        val context = ContextBuilder.build(
            "书名", "第一章",
            locator("选中"),
            paragraphs("前文", "选中", "后文"),
            listOf("user" to "第一问", "assistant" to "第一答"),
        )
        val json = org.json.JSONObject(context.snapshotJson())

        assertEquals("书名", json.getString("bookTitle"))
        assertEquals("第一章", json.getString("chapterTitle"))
        assertEquals("前文", json.getString("paragraphBefore"))
        assertEquals(2, json.getJSONArray("history").length())
    }
}

/** Test-visible bound, so the test states the rule it checks. */
internal const val ContextBuilderMaxTotalForTest = 6000
