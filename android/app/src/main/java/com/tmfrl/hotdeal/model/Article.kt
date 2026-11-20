package com.tmfrl.hotdeal.model

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64

/** 댓글 하나. 작성자 닉네임은 담지 않는다(AI 분석용 익명 내보내기). depth 0 = 댓글, 1 이상 = 답글. */
data class Comment(
    val depth: Int,
    val time: String,
    val text: String,
    val likes: Int? = null,
    val dislikes: Int? = null,
    /** 글쓴이가 단 댓글(클리앙만 알 수 있다) */
    val byAuthor: Boolean = false,
)

/** 원문 페이지에서 읽은 글. body는 이미 마크다운이다. */
data class Article(
    val title: String,
    val postedAt: String,
    val body: String,
    val comments: List<Comment>,
    /** 사이트가 보여 주는 댓글 수 */
    val commentTotal: Int? = null,
    val recommends: Int? = null,
    /** 글 머리의 상품 링크(뽐뿌) */
    val links: List<String> = emptyList(),
    /** 댓글 일부를 받지 못했을 때 그 이유 */
    val note: String? = null,
)

sealed class ArticleResult {
    data class Read(val article: Article) : ArticleResult()
    data class Unsupported(val reason: String) : ArticleResult()
    data class Failed(val reason: String) : ArticleResult()
}

enum class Site { PPOMPPU, CLIEN, FMKOREA, YOUTUBE, OTHER }

fun siteOf(url: String): Site = when {
    "ppomppu.co.kr" in url -> Site.PPOMPPU
    "clien.net" in url -> Site.CLIEN
    "fmkorea.com" in url -> Site.FMKOREA
    "youtube.com" in url || "youtu.be" in url -> Site.YOUTUBE
    else -> Site.OTHER
}

fun siteName(site: Site): String = when (site) {
    Site.PPOMPPU -> "뽐뿌"
    Site.CLIEN -> "클리앙"
    Site.FMKOREA -> "FM코리아"
    Site.YOUTUBE -> "유튜브"
    Site.OTHER -> ""
}

/** 한 글에서 댓글 페이지를 이보다 많이 받지 않는다(사이트 부담과 시간 제한). */
const val MAX_COMMENT_PAGES = 200

// ───────────────────────── HTML → 마크다운 ─────────────────────────

private val BLOCKS = setOf(
    "p", "div", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "table", "tr", "section",
    "article", "pre", "figure", "figcaption", "header", "footer", "center", "dl", "dt", "dd", "body",
)
private val SKIP = setOf("script", "style", "noscript", "input", "button", "textarea", "select", "form", "head", "svg")
private val SPACES = Regex("[ \\t\\u00A0\\u200B]+")
private val BLANK_LINES = Regex("\n{3,}")
private val TARGET = Regex("""[?&]target=([^&#]+)""")

/** "//cdn..."은 https로, 뽐뿌 바로가기(s.ppomppu.co.kr?target=base64)는 실제 주소로 바꾼다. */
fun realUrl(url: String): String {
    val u = if (url.startsWith("//")) "https:$url" else url
    if ("s.ppomppu.co.kr" in u) {
        val target = TARGET.find(u)?.groupValues?.get(1) ?: return u
        return try {
            val b64 = URLDecoder.decode(target, "UTF-8")
            val decoded = String(Base64.getMimeDecoder().decode(b64), Charsets.UTF_8)
            if (decoded.startsWith("http")) decoded else u
        } catch (e: IllegalArgumentException) {
            u
        }
    }
    return u
}

private fun absUrl(e: Element, attr: String): String {
    val abs = e.absUrl(attr)
    return realUrl(abs.ifEmpty { e.attr(attr) }.trim())
}

private fun imageUrl(e: Element): String? {
    for (attr in listOf("data-original", "data-src", "src")) {
        if (e.hasAttr(attr)) {
            val u = absUrl(e, attr)
            if (u.isNotEmpty() && !u.startsWith("data:")) return u
        }
    }
    return null
}

private fun looksLikeUrl(text: String) = text.startsWith("http://") || text.startsWith("https://") || text.startsWith("www.")

private fun walk(node: Node, sb: StringBuilder) {
    for (child in node.childNodes()) {
        when (child) {
            is TextNode -> sb.append(child.wholeText.replace('\n', ' ').replace('\r', ' '))
            is Element -> element(child, sb)
        }
    }
}

private fun element(e: Element, sb: StringBuilder) {
    val name = e.normalName()
    when {
        name in SKIP -> return
        name == "br" -> sb.append('\n')
        name == "hr" -> sb.append("\n\n---\n\n")
        name == "img" -> imageUrl(e)?.let { sb.append("![](").append(it).append(')') }
        name == "iframe" || name == "video" || name == "embed" -> {
            val src = absUrl(e, "src")
            if (src.isNotEmpty()) sb.append("\n\n[삽입된 동영상·콘텐츠](").append(src).append(")\n\n")
        }
        name == "a" -> link(e, sb)
        name == "li" -> {
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            sb.append("- ")
            walk(e, sb)
            sb.append('\n')
        }
        name in BLOCKS -> {
            sb.append("\n\n")
            walk(e, sb)
            sb.append("\n\n")
        }
        else -> walk(e, sb)
    }
}

private fun link(e: Element, sb: StringBuilder) {
    val href = absUrl(e, "href")
    val inner = StringBuilder()
    walk(e, inner)
    val text = tidy(inner.toString()).replace('\n', ' ')
    if (href.isEmpty() || href.startsWith("javascript") || href.startsWith("#") || href.startsWith("mailto")) {
        sb.append(text)
        return
    }
    when {
        text.isEmpty() -> sb.append("[링크](").append(href).append(')')
        looksLikeUrl(text) -> sb.append(href) // "https://service.toss.im/sho..." 처럼 잘린 주소 글자는 실제 주소로
        else -> sb.append('[').append(text).append("](").append(href).append(')')
    }
}

/** 줄마다 공백을 정리하고 빈 줄은 하나만 남긴다. */
private fun tidy(text: String): String =
    text.lines().joinToString("\n") { it.replace(SPACES, " ").trim() }
        .replace(BLANK_LINES, "\n\n")
        .trim()

/** 엘리먼트 안의 HTML을 마크다운으로 바꾼다. 문단은 빈 줄, 줄바꿈은 줄바꿈, 링크는 [글](주소), 이미지는 ![](주소). */
fun htmlToMarkdown(root: Element): String {
    val sb = StringBuilder()
    walk(root, sb)
    return tidy(sb.toString())
}

/** 닉네임 언급(@닉네임)을 지운다. 뽐뿌는 b.cheditor_tonick, 클리앙은 data-role=highlite. */
private fun anonymizeMentions(root: Element) {
    for (el in root.select(".cheditor_tonick, [data-role=highlite]")) {
        if (el.parent() == null) continue
        el.replaceWith(TextNode(if (el.normalName() == "img") "언급" else "@언급"))
    }
}

/** 댓글 HTML 조각을 익명 마크다운으로. */
fun commentHtmlToMarkdown(html: String, baseUrl: String): String {
    val body = Jsoup.parseBodyFragment(html, baseUrl).body()
    anonymizeMentions(body)
    return htmlToMarkdown(body)
}

private val DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(:\d{2})?""")
private fun digits(text: String?): Int? = text?.filter { it.isDigit() }?.toIntOrNull()

// ───────────────────────── 뽐뿌 ─────────────────────────

/** 뽐뿌 모바일 글 페이지 하나에서 읽은 것. 댓글은 페이지마다 나뉜다(c_page). */
data class PpomppuPage(
    val title: String?,
    val postedAt: String,
    val recommends: Int?,
    val body: String?,
    val links: List<String>,
    val comments: List<Comment>,
    val commentTotal: Int?,
    val totalPages: Int,
)

// JSON 안의 줄바꿈은 \n으로 이스케이프되어 있어서 "};" 뒤의 실제 줄바꿈이 끝이다
private val INITIAL_COMMENTS = Regex("""var initialCommentData = (\{.*?\});\s*\n""", RegexOption.DOT_MATCHES_ALL)
private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()

private fun ppomppuComments(arr: JsonElement?, baseUrl: String, out: MutableList<Comment>, depth: Int) {
    for (item in (arr as? JsonArray).orEmpty()) {
        val c = item.obj() ?: continue
        val meta = c["meta"].obj()
        out += Comment(
            depth = c["depth"].int() ?: depth,
            time = meta?.get("time_detail").str() ?: meta?.get("time_display").str() ?: "",
            text = commentHtmlToMarkdown(c["memo"].str().orEmpty(), baseUrl),
            likes = c["vote_btn"].obj()?.get("vote_count").int(),
            dislikes = c["anti_vote_btn"].obj()?.get("anti_vote_count").int(),
        )
        ppomppuComments(c["sub_cmt"], baseUrl, out, depth + 1)
    }
}

fun parsePpomppuPage(html: String, url: String): PpomppuPage {
    val doc: Document = Jsoup.parse(html, url)
    val h4 = doc.selectFirst("div.bbs.view > h4") ?: doc.selectFirst("div.bbs h4")
    val content = doc.selectFirst("#KH_Content")?.clone()
    content?.select(".history_edit, script, style")?.remove()
    anonymizeMentions(content ?: Element("div"))
    val data = INITIAL_COMMENTS.find(html)?.groupValues?.get(1)?.let {
        try {
            lenientJson.parseToJsonElement(it).obj()
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    val comments = mutableListOf<Comment>()
    ppomppuComments(data?.get("comments"), url, comments, 0)
    return PpomppuPage(
        title = h4?.ownText()?.trim()?.takeIf { it.isNotEmpty() },
        postedAt = doc.selectFirst("div.bbs span.hi")?.text()?.let { DATE_TIME.find(it)?.value ?: it.trim() } ?: "",
        recommends = digits(doc.selectFirst("#top_recommend_area strong")?.text()),
        body = content?.let { htmlToMarkdown(it) },
        links = doc.select("div.bbs .link-box a[href]").map { absUrl(it, "href") }.filter { it.isNotEmpty() }.distinct(),
        comments = comments,
        commentTotal = data?.get("total_comment").int(),
        totalPages = data?.get("total_page").int() ?: 1,
    )
}

fun ppomppuCommentPageUrl(mobileUrl: String, page: Int) = "$mobileUrl&c_page=$page"

// ───────────────────────── 클리앙 ─────────────────────────

data class ClienPage(
    val title: String?,
    val postedAt: String,
    val recommends: Int?,
    val body: String?,
    val comments: List<Comment>,
    val commentTotal: Int?,
    val pageSize: Int,
)

/** 댓글 줄(div.comment_row)을 읽는다. 글 페이지와 댓글 목록 조각(/comment?po=) 모두 같은 모양이다. */
fun parseClienComments(root: Element): List<Comment> = root.select("div.comment_row").map { row ->
    val view = row.selectFirst(".comment_view")?.clone() ?: Element("div")
    view.select("input").remove()
    anonymizeMentions(view)
    val images = row.select(".comment-img img").mapNotNull { imageUrl(it) }.joinToString("\n") { "![]($it)" }
    val text = listOf(htmlToMarkdown(view), images).filter { it.isNotEmpty() }.joinToString("\n\n")
    val time = row.selectFirst(".comment_time .timestamp")?.text()
        ?: row.selectFirst(".comment_time")?.text().orEmpty()
    Comment(
        depth = if (row.hasClass("re")) 1 else 0,
        time = DATE_TIME.find(time)?.value ?: time.trim(),
        text = text,
        likes = digits(row.selectFirst("strong[id^=setLikeCount_]")?.text()),
        byAuthor = row.hasClass("by-author"),
    )
}

fun parseClienPage(html: String, url: String): ClienPage {
    val doc = Jsoup.parse(html, url)
    val article = doc.selectFirst(".post_article")?.clone()
    article?.select("script, style")?.remove()
    val soldOut = doc.selectFirst(".post_subject > .post_soldout") != null
    val title = (
        doc.selectFirst(".post_subject > span:not(.post_soldout)")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.removeSuffix(" : 클리앙")
        )?.let { if (soldOut) "[품절] $it" else it }
    return ClienPage(
        title = title?.takeIf { it.isNotEmpty() },
        postedAt = doc.selectFirst(".post_date")?.text()?.let { DATE_TIME.find(it)?.value ?: it.trim() } ?: "",
        recommends = digits(doc.selectFirst(".symph_count")?.text()),
        body = article?.let { htmlToMarkdown(it) },
        comments = parseClienComments(doc),
        commentTotal = doc.selectFirst("#commentCount")?.attr("value")?.toIntOrNull(),
        pageSize = doc.selectFirst("#configCommentPageSize")?.attr("value")?.toIntOrNull()?.takeIf { it > 0 } ?: 100,
    )
}

/** 글 페이지에 다 실리지 않은 댓글을 받는 주소. po는 0부터 세는 쪽 번호다. */
fun clienCommentListUrl(pageUrl: String, po: Int, ps: Int) = "$pageUrl/comment?order=date&po=$po&ps=$ps"

// ───────────────────────── 글 읽기 ─────────────────────────

private fun failReason(e: Exception): String = when (e) {
    is IOException -> "원문을 받지 못했습니다 (${e.message ?: e.javaClass.simpleName})"
    else -> "원문을 읽다가 오류가 났습니다 (${e.message ?: e.javaClass.simpleName})"
}

/**
 * 저장한 글의 원문을 받아 읽는다. 댓글이 여러 쪽이면 gapMs씩 쉬며 모두 받는다.
 * onPage(받은 쪽, 전체 쪽)로 진행을 알린다. 취소하면 delay에서 CancellationException이 난다.
 */
suspend fun readArticle(
    url: String,
    fetch: suspend (String) -> String,
    gapMs: Long,
    onPage: (Int, Int) -> Unit = { _, _ -> },
): ArticleResult {
    val site = siteOf(url)
    return when (site) {
        Site.PPOMPPU -> readPpomppu(url, fetch, gapMs, onPage)
        Site.CLIEN -> readClien(url, fetch, gapMs, onPage)
        Site.FMKOREA -> ArticleResult.Unsupported("FM코리아는 보안 시스템이 자동 요청을 막습니다")
        Site.YOUTUBE -> ArticleResult.Unsupported("유튜브 영상은 본문과 댓글을 읽지 않습니다")
        Site.OTHER -> ArticleResult.Unsupported("")
    }
}

private suspend fun readPpomppu(
    url: String, fetch: suspend (String) -> String, gapMs: Long, onPage: (Int, Int) -> Unit,
): ArticleResult {
    val mobile = commentPageUrl(url) ?: return ArticleResult.Failed("뽐뿌 글 주소에서 게시판·글 번호를 찾지 못했습니다")
    val first = try {
        parsePpomppuPage(fetch(mobile), mobile)
    } catch (e: IOException) {
        return ArticleResult.Failed(failReason(e))
    }
    if (first.title == null && first.body == null) {
        return ArticleResult.Failed("본문을 찾지 못했습니다 (삭제된 글이거나 페이지 모양이 바뀌었습니다)")
    }
    val pages = first.totalPages.coerceIn(1, MAX_COMMENT_PAGES)
    onPage(1, pages)
    val comments = first.comments.toMutableList()
    var note: String? = if (first.totalPages > MAX_COMMENT_PAGES) "댓글이 너무 많아 앞 ${MAX_COMMENT_PAGES}쪽만 받았습니다" else null
    for (page in 2..pages) {
        delay(gapMs)
        try {
            comments += parsePpomppuPage(fetch(ppomppuCommentPageUrl(mobile, page)), mobile).comments
        } catch (e: IOException) {
            note = "댓글 ${page}쪽부터 받지 못했습니다 (${e.message})"
            break
        }
        onPage(page, pages)
    }
    return ArticleResult.Read(
        Article(
            title = first.title ?: "",
            postedAt = first.postedAt,
            body = first.body.orEmpty(),
            comments = comments,
            commentTotal = first.commentTotal,
            recommends = first.recommends,
            links = first.links,
            note = note,
        ),
    )
}

private suspend fun readClien(
    url: String, fetch: suspend (String) -> String, gapMs: Long, onPage: (Int, Int) -> Unit,
): ArticleResult {
    val page = commentPageUrl(url) ?: return ArticleResult.Failed("클리앙 글 주소를 알아보지 못했습니다")
    val first = try {
        parseClienPage(fetch(page), page)
    } catch (e: IOException) {
        return ArticleResult.Failed(failReason(e))
    }
    if (first.title == null && first.body == null) {
        return ArticleResult.Failed("본문을 찾지 못했습니다 (삭제된 글이거나 페이지 모양이 바뀌었습니다)")
    }
    val total = first.commentTotal ?: first.comments.size
    val ps = first.pageSize
    val pages = ((total + ps - 1) / ps).coerceIn(1, MAX_COMMENT_PAGES)
    onPage(1, pages)
    val comments = first.comments.toMutableList()
    var note: String? = null
    var po = 1
    while (comments.size < total && po < pages) {
        delay(gapMs)
        val more = try {
            parseClienComments(Jsoup.parse(fetch(clienCommentListUrl(page, po, ps)), page))
        } catch (e: IOException) {
            note = "댓글 ${po + 1}쪽부터 받지 못했습니다 (${e.message})"
            break
        }
        if (more.isEmpty()) break
        comments += more
        po++
        onPage(po, pages)
    }
    return ArticleResult.Read(
        Article(
            title = first.title ?: "",
            postedAt = first.postedAt,
            body = first.body.orEmpty(),
            comments = comments,
            commentTotal = first.commentTotal,
            recommends = first.recommends,
            note = note,
        ),
    )
}
