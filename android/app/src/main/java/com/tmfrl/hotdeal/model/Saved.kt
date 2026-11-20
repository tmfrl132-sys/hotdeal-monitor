package com.tmfrl.hotdeal.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 댓글을 지켜보려고 저장한 글. 기기에만 저장한다. */
@Serializable
data class SavedLink(
    val url: String,
    val title: String,
    val sourceLabel: String = "",
    val savedAt: String = "",
    /** 마지막으로 읽은 댓글 수. 읽을 수 없는 사이트면 null. */
    val comments: Int? = null,
    /** 사용자가 마지막으로 확인한 댓글 수. 이보다 늘어난 만큼이 새 댓글이다. */
    val seenComments: Int? = null,
    val checkedAt: String? = null,
    /** 분류 id(SavedCategory.id). 옛 저장 파일에는 없어서 null이고, 읽을 때 자동으로 배정한다. */
    val category: String? = null,
)

val SavedLink.newComments: Int
    get() = if (comments == null || seenComments == null) 0 else maxOf(0, comments - seenComments)

private val PPOMPPU_ID = Regex("""[?&]id=([^&#]+)""")
private val PPOMPPU_NO = Regex("""[?&]no=(\d+)""")
private val CLIEN_PATH = Regex("""clien\.net(/service/board/[^?#]+)""")
private val PPOMPPU_COUNT = Regex("""class="cmnb">\s*(\d+)""")
private val CLIEN_COUNT = Regex("""value="(\d+)"\s+id="commentCount"""")
private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** 댓글 수를 읽을 페이지. 뽐뿌·클리앙만 지원하고, 그 밖의 사이트는 null이다. */
fun commentPageUrl(url: String): String? {
    if ("ppomppu.co.kr" in url) {
        val id = PPOMPPU_ID.find(url)?.groupValues?.get(1) ?: return null
        val no = PPOMPPU_NO.find(url)?.groupValues?.get(1) ?: return null
        return "https://m.ppomppu.co.kr/new/bbs_view.php?id=$id&no=$no"
    }
    return CLIEN_PATH.find(url)?.let { "https://m.clien.net${it.groupValues[1]}" }
}

fun parseCommentCount(url: String, html: String): Int? {
    val pattern = if ("ppomppu.co.kr" in url) PPOMPPU_COUNT else CLIEN_COUNT
    return pattern.find(html)?.groupValues?.get(1)?.toIntOrNull()
}

fun parseHtmlTitle(html: String): String? =
    TITLE.find(html)?.groupValues?.get(1)?.trim()
        ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&#39;", "'")
        ?.replace("&amp;", "&")
        ?.takeIf { it.isNotEmpty() }

/** 뽐뿌는 EUC-KR, 나머지는 UTF-8이다. 페이지 앞부분의 charset 선언으로 고른다. */
fun decodeHtml(bytes: ByteArray): String {
    val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).lowercase()
    return String(bytes, if ("euc-kr" in head) charset("EUC-KR") else Charsets.UTF_8)
}

fun isSavableUrl(text: String): Boolean {
    val t = text.trim()
    return (t.startsWith("https://") || t.startsWith("http://")) && t.none { it.isWhitespace() }
}

fun addSaved(list: List<SavedLink>, link: SavedLink): List<SavedLink> =
    if (list.any { it.url == link.url }) list else listOf(link) + list

fun markSeen(list: List<SavedLink>, url: String): List<SavedLink> =
    list.map { if (it.url == url) it.copy(seenComments = it.comments) else it }

/** 확인 결과를 반영한다. 처음 읽은 댓글 수는 기준값이라 새 댓글로 세지 않는다. 실패(null)면 이전 값을 유지한다. */
fun applyCheck(link: SavedLink, count: Int?, title: String?, now: String): SavedLink = link.copy(
    comments = count ?: link.comments,
    seenComments = link.seenComments ?: count,
    title = if (link.title == link.url && title != null) title else link.title,
    checkedAt = if (count != null) now else link.checkedAt,
)

private val json = Json { ignoreUnknownKeys = true }
private val listSerializer = ListSerializer(SavedLink.serializer())

/** 깨진 파일이면 빈 목록. */
fun parseSaved(text: String): List<SavedLink> = try {
    json.decodeFromString(listSerializer, text)
} catch (e: IllegalArgumentException) {
    emptyList()
}

fun encodeSaved(list: List<SavedLink>): String = json.encodeToString(listSerializer, list)
