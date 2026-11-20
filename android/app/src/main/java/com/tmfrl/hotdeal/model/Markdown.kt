package com.tmfrl.hotdeal.model

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 내보낼 글 하나: 저장한 글, 그 글의 분류 이름, 원문을 읽은 결과. */
data class ExportEntry(val link: SavedLink, val categoryName: String, val result: ArticleResult)

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
private val BAD_FILE_CHARS = Regex("""[\\/:*?"<>|\r\n\t]+""")

fun formatStamp(now: ZonedDateTime): String = "${now.format(STAMP)} (${now.zone.id})"

private fun heading(level: Int) = "#".repeat(level.coerceIn(1, 6))

/** 목차 링크 글자 안의 [ ]가 링크를 깨지 않게 한다. */
private fun escapeLinkText(text: String) = text.replace("[", "\\[").replace("]", "\\]")

private fun entryTitle(e: ExportEntry): String {
    val read = (e.result as? ArticleResult.Read)?.article?.title
    return read?.takeIf { it.isNotBlank() } ?: e.link.title.ifBlank { e.link.url }
}

private fun siteLabel(e: ExportEntry): String = e.link.sourceLabel.ifBlank { siteName(siteOf(e.link.url)) }

/** 댓글을 익명으로 적는다. 댓글은 "댓글 N", 답글은 인용(>)을 한 단계씩 깊게 하고 "└ 답글 (댓글 N)"로 적는다. */
fun renderComments(comments: List<Comment>): String {
    var top = 0
    return comments.joinToString("\n\n") { c ->
        val name = if (c.depth <= 0) {
            top++
            "댓글 $top"
        } else {
            "${"└".repeat(c.depth)} 답글 (댓글 ${top.coerceAtLeast(1)})"
        }
        val meta = listOfNotNull(
            "**$name**" + if (c.byAuthor) " (글쓴이)" else "",
            c.time.takeIf { it.isNotBlank() },
            c.likes?.takeIf { it > 0 }?.let { "추천 $it" },
            c.dislikes?.takeIf { it > 0 }?.let { "비추천 $it" },
        ).joinToString(" · ")
        val prefix = "> ".repeat(c.depth.coerceAtLeast(0))
        val text = c.text.ifBlank { "(내용 없음)" }
        (listOf(meta, "") + text.lines()).joinToString("\n") { line ->
            if (line.isEmpty()) prefix.trimEnd() else prefix + line
        }
    }
}

/**
 * 글 하나의 마크다운. level은 제목 단계다(단독 파일 1, 묶음 파일 2).
 * 원문을 읽지 못한 글도 제목·링크·이유를 남긴다.
 */
fun renderArticle(e: ExportEntry, level: Int = 1): String {
    val h = heading(level)
    val sub = heading(level + 1)
    val article = (e.result as? ArticleResult.Read)?.article
    val sb = StringBuilder()
    sb.append(h).append(' ').append(entryTitle(e)).append("\n\n")
    val meta = listOfNotNull(
        siteLabel(e).takeIf { it.isNotBlank() }?.let { "- 사이트: $it" },
        article?.postedAt?.takeIf { it.isNotBlank() }?.let { "- 작성 시각: $it" },
        "- 원문: ${e.link.url}",
        "- 분류: ${e.categoryName}",
        article?.recommends?.let { "- 추천: $it" },
    ) + article?.links.orEmpty().map { "- 상품 링크: $it" }
    sb.append(meta.joinToString("\n")).append("\n\n")
    when (val r = e.result) {
        is ArticleResult.Unsupported -> {
            sb.append("> 본문을 읽을 수 없는 사이트입니다.")
            if (r.reason.isNotBlank()) sb.append(" (").append(r.reason).append(')')
            sb.append('\n')
        }
        is ArticleResult.Failed -> sb.append("> ⚠ 변환 실패: ").append(r.reason).append('\n')
        is ArticleResult.Read -> {
            val a = r.article
            sb.append(sub).append(" 본문\n\n").append(a.body.ifBlank { "(본문 없음)" }).append("\n\n")
            val got = a.comments.size
            val countText = if (a.commentTotal != null && a.commentTotal != got) {
                "${got}개 · 사이트 표시 ${a.commentTotal}개"
            } else {
                "${got}개"
            }
            sb.append(sub).append(" 댓글 (").append(countText).append(")\n\n")
            if (a.note != null) sb.append("> ⚠ ").append(a.note).append("\n\n")
            sb.append(if (got == 0) "(댓글 없음)" else renderComments(a.comments)).append('\n')
        }
    }
    return sb.toString().trimEnd() + "\n"
}

/** 글 하나만 담은 파일. */
fun renderSingle(e: ExportEntry, now: ZonedDateTime): String =
    renderArticle(e, 1) + "\n---\n\n_내보낸 시각: ${formatStamp(now)} · 핫딜 모니터_\n"

/** 여러 글을 묶은 파일: 맨 위에 만든 시각과 목차, 글 사이에 구분선. */
fun renderBundle(entries: List<ExportEntry>, now: ZonedDateTime): String {
    val failed = entries.count { it.result !is ArticleResult.Read }
    val sb = StringBuilder()
    sb.append("# 저장한 글 모음\n\n")
    sb.append("- 만든 시각: ").append(formatStamp(now)).append('\n')
    sb.append("- 글 수: ").append(entries.size).append("개")
    if (failed > 0) sb.append(" (본문을 읽지 못한 글 ").append(failed).append("개)")
    sb.append("\n\n## 목차\n\n")
    entries.forEachIndexed { i, e ->
        val tail = listOf(siteLabel(e), e.categoryName).filter { it.isNotBlank() }.joinToString(" · ")
        val mark = when (e.result) {
            is ArticleResult.Read -> ""
            is ArticleResult.Unsupported -> " (본문 없음)"
            is ArticleResult.Failed -> " (실패)"
        }
        sb.append(i + 1).append(". [").append(escapeLinkText(entryTitle(e))).append("](#post-").append(i + 1).append(')')
        if (tail.isNotEmpty()) sb.append(" · ").append(tail)
        sb.append(mark).append('\n')
    }
    entries.forEachIndexed { i, e ->
        sb.append("\n---\n\n<a id=\"post-").append(i + 1).append("\"></a>\n\n")
        sb.append(renderArticle(e, 2))
    }
    return sb.toString()
}

/** 파일 이름. 단독은 글 제목, 묶음은 만든 시각으로 짓는다. */
fun exportFileName(entries: List<ExportEntry>, now: ZonedDateTime): String {
    if (entries.size != 1) return "저장한글_${now.format(FILE_STAMP)}.md"
    val title = entryTitle(entries.single()).replace(BAD_FILE_CHARS, " ").replace(Regex("\\s+"), " ").trim().take(40).trim()
    return "${title.ifEmpty { "저장한글" }}_${now.format(FILE_STAMP)}.md"
}
