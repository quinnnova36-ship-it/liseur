package com.chmouel.liseur.readora.discussion

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * What a discussion turn is sent with (Readora S3).
 *
 * The product promise: the selected passage, the natural paragraph
 * before it and the one after it, the book's name and the chapter's,
 * and the turns already in the thread. Everything of the book goes into
 * the user message as quoted material — never into the system role,
 * where a book's text could pose as instructions.
 */
data class DiscussionContext(
    val bookTitle: String,
    val chapterTitle: String?,
    val passage: String,
    val paragraphBefore: String?,
    val paragraphAfter: String?,
    /** Why any part of the window is missing, shown with the turn. */
    val limitation: String?,
    /** The turns before this one, oldest first, user and assistant. */
    val history: List<Pair<String, String>>,
) {
    /** The message body actually sent, quoted and bounded. */
    fun compose(question: String): String = buildString {
        appendLine("《书名》$bookTitle")
        chapterTitle?.let { appendLine("《章节》$it") }
        appendLine()
        paragraphBefore?.let { appendLine("《前文》"); appendLine(it); appendLine("《/前文》") }
        appendLine("《选中原句》"); appendLine(passage); appendLine("《/选中原句》")
        paragraphAfter?.let { appendLine("《后文》"); appendLine(it); appendLine("《/后文》") }
        limitation?.let { appendLine(); appendLine("（$it）") }
        appendLine()
        append(question)
    }

    fun snapshotJson(): String = JSONObject()
        .put("bookTitle", bookTitle)
        .put("chapterTitle", chapterTitle ?: JSONObject.NULL)
        .put("passage", passage)
        .put("paragraphBefore", paragraphBefore ?: JSONObject.NULL)
        .put("paragraphAfter", paragraphAfter ?: JSONObject.NULL)
        .put("limitation", limitation ?: JSONObject.NULL)
        .put(
            "history",
            org.json.JSONArray().apply { history.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content.take(200))) } },
        )
        .toString()
}

/**
 * Builds a [DiscussionContext] from the book's own reading of itself.
 *
 * The paragraphs come from walking `publication.content()` — the same
 * stream the reading companion walks — which yields text elements in
 * reading order with their place. The selection's own file is walked,
 * and the element whose text contains the passage is the anchor; the
 * elements beside it in that file are the window. Anything the walk
 * cannot establish degrades rather than fails: a missing flank is a
 * noted limitation, an unreadable book is passage-only.
 */
object ContextBuilder {

    private const val MAX_PARAGRAPH = 2000
    private const val MAX_TOTAL = 6000
    private const val MAX_HISTORY = 12

    /**
     * @param paragraphs the selection's file as (text, place) pairs in
     * reading order; callers supply `publication.content()` walked once.
     */
    fun build(
        bookTitle: String,
        chapterTitle: String?,
        selection: Locator,
        paragraphs: List<Pair<String, Locator?>>,
        history: List<Pair<String, String>>,
    ): DiscussionContext {
        val passage = selection.text.highlight?.takeIf { it.isNotBlank() }
        val limitation = mutableListOf<String>()
        if (passage == null) {
            return DiscussionContext(
                bookTitle = bookTitle,
                chapterTitle = chapterTitle,
                passage = selection.text.highlight.orEmpty().ifBlank { "（无文字选区）" },
                paragraphBefore = null,
                paragraphAfter = null,
                limitation = "选区没有可读文字，未附上下文",
                history = bound(history),
            )
        }

        val href = selection.href.toString()
        val index = paragraphs.indexOfFirst { (text, _) -> text.contains(passage) }
        if (index < 0) {
            // The walk did not see the passage (a file listed twice, a
            // page the walker cannot read): the promise degrades to the
            // passage itself, and the reader is told why.
            return DiscussionContext(
                bookTitle, chapterTitle, passage, null, null,
                "未能定位该段落在书中的位置，仅发送选中原句",
                bound(history),
            )
        }

        var before = paragraphs.getOrNull(index - 1)?.first
        var after = paragraphs.getOrNull(index + 1)?.first
        // A chapter boundary shows as the file's edge: there is no
        // neighbour to take there, which is a fact about the passage's
        // place, not a failure worth a notice.
        if (index == 0) before = null
        if (index == paragraphs.lastIndex) after = null

        var total = passage.length
        if (before != null && before.length > MAX_PARAGRAPH) {
            before = before.takeLast(MAX_PARAGRAPH)
            limitation += "前文过长已截断"
        }
        if (after != null && after.length > MAX_PARAGRAPH) {
            after = after.take(MAX_PARAGRAPH)
            limitation += "后文过长已截断"
        }
        total += (before?.length ?: 0) + (after?.length ?: 0)
        if (total > MAX_TOTAL) {
            // Keep the passage, then the nearer flank.
            val room = MAX_TOTAL - passage.length
            val share = room / 2
            before = before?.takeLast(share.coerceAtLeast(0))
            after = after?.take(share.coerceAtLeast(0))
            limitation += "上下文总量超限已裁剪"
        }

        return DiscussionContext(
            bookTitle = bookTitle,
            chapterTitle = chapterTitle,
            passage = passage,
            paragraphBefore = before?.takeIf { it.isNotBlank() },
            paragraphAfter = after?.takeIf { it.isNotBlank() },
            limitation = limitation.takeIf { it.isNotEmpty() }?.joinToString("；"),
            history = bound(history),
        )
    }

    /** The file a place is in, as the walker spells it. */
    fun fileOf(place: Locator?): String? = place?.href?.toString()

    private fun bound(history: List<Pair<String, String>>): List<Pair<String, String>> =
        if (history.size <= MAX_HISTORY) history else history.takeLast(MAX_HISTORY)
}
