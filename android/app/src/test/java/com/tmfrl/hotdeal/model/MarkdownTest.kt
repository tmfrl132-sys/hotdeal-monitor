package com.tmfrl.hotdeal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MarkdownTest {
    private val now = ZonedDateTime.of(2026, 11, 20, 21, 5, 0, 0, ZoneId.of("Asia/Seoul"))

    private val article = Article(
        title = "[토스] 선스틱 990원",
        postedAt = "2026-11-19 15:08",
        body = "본문 첫 줄\n\n[가게](https://a.com)",
        comments = listOf(
            Comment(0, "2026-11-19 15:09:22", "좋은 정보 감사합니다", likes = 3),
            Comment(1, "2026-11-19 15:10:00", "저도요\n\n두 번째 문단", byAuthor = true),
            Comment(2, "2026-11-19 15:11:00", "답글의 답글", dislikes = 1),
            Comment(0, "2026-11-19 15:12:00", ""),
        ),
        commentTotal = 5,
        recommends = 53,
        links = listOf("https://service.toss.im/x"),
    )
    private val link = SavedLink("https://www.ppomppu.co.kr/zboard/view.php?id=pmarket&no=1", "저장 제목", "뽐뿌 쇼핑뽐뿌")
    private val read = ExportEntry(link, "핫딜", ArticleResult.Read(article))

    @Test
    fun commentsAreAnonymousAndRepliesIndented() {
        assertEquals(
            """
            **댓글 1** · 2026-11-19 15:09:22 · 추천 3

            좋은 정보 감사합니다

            > **└ 답글 (댓글 1)** (글쓴이) · 2026-11-19 15:10:00
            >
            > 저도요
            >
            > 두 번째 문단

            > > **└└ 답글 (댓글 1)** · 2026-11-19 15:11:00 · 비추천 1
            > >
            > > 답글의 답글

            **댓글 2** · 2026-11-19 15:12:00

            (내용 없음)
            """.trimIndent(),
            renderComments(article.comments),
        )
    }

    @Test
    fun singleArticleHasMetaBodyAndComments() {
        val md = renderSingle(read, now)
        assertTrue(md.startsWith("# [토스] 선스틱 990원\n\n- 사이트: 뽐뿌 쇼핑뽐뿌\n- 작성 시각: 2026-11-19 15:08\n"))
        assertTrue(md.contains("- 원문: ${link.url}\n- 분류: 핫딜\n- 추천: 53\n- 상품 링크: https://service.toss.im/x"))
        assertTrue(md.contains("## 본문\n\n본문 첫 줄\n\n[가게](https://a.com)"))
        assertTrue(md.contains("## 댓글 (4개 · 사이트 표시 5개)"))
        assertTrue(md.contains("_내보낸 시각: 2026-11-20 21:05 (Asia/Seoul) · 핫딜 모니터_"))
    }

    @Test
    fun bundleHasTocTimestampAndSeparators() {
        val unsupported = ExportEntry(
            SavedLink("https://www.fmkorea.com/1", "FM 글", "FM코리아 핫딜"), "핫딜",
            ArticleResult.Unsupported("FM코리아는 보안 시스템이 자동 요청을 막습니다"),
        )
        val failed = ExportEntry(
            SavedLink("https://m.clien.net/service/board/jirum/9", "클리앙 글 [특가", ""), "기타",
            ArticleResult.Failed("원문을 받지 못했습니다 (HTTP 404)"),
        )
        val md = renderBundle(listOf(read, unsupported, failed), now)
        assertTrue(md.startsWith("# 저장한 글 모음\n\n- 만든 시각: 2026-11-20 21:05 (Asia/Seoul)\n- 글 수: 3개 (본문을 읽지 못한 글 2개)\n\n## 목차\n\n"))
        assertTrue(md.contains("1. [\\[토스\\] 선스틱 990원](#post-1) · 뽐뿌 쇼핑뽐뿌 · 핫딜\n"))
        assertTrue(md.contains("2. [FM 글](#post-2) · FM코리아 핫딜 · 핫딜 (본문 없음)\n"))
        assertTrue(md.contains("3. [클리앙 글 \\[특가](#post-3) · 클리앙 · 기타 (실패)\n"))
        assertEquals(3, Regex("\n---\n\n<a id=\"post-\\d\"></a>").findAll(md).count())
        // 묶음에서는 글 제목이 ##, 소제목이 ###
        assertTrue(md.contains("<a id=\"post-1\"></a>\n\n## [토스] 선스틱 990원\n"))
        assertTrue(md.contains("### 본문"))
        assertTrue(md.contains("### 댓글 (4개 · 사이트 표시 5개)"))
        assertTrue(md.contains("## FM 글\n\n- 사이트: FM코리아 핫딜\n- 원문: https://www.fmkorea.com/1\n- 분류: 핫딜\n\n> 본문을 읽을 수 없는 사이트입니다. (FM코리아는 보안 시스템이 자동 요청을 막습니다)"))
        assertTrue(md.contains("> ⚠ 변환 실패: 원문을 받지 못했습니다 (HTTP 404)"))
    }

    @Test
    fun partialCommentsNoteAndEmptyComments() {
        val partial = article.copy(comments = emptyList(), commentTotal = 0, note = "댓글 2쪽부터 받지 못했습니다 (HTTP 503)")
        val md = renderArticle(ExportEntry(link, "핫딜", ArticleResult.Read(partial)))
        assertTrue(md.contains("## 댓글 (0개)\n\n> ⚠ 댓글 2쪽부터 받지 못했습니다 (HTTP 503)\n\n(댓글 없음)"))
    }

    @Test
    fun fileNames() {
        assertEquals("[토스] 선스틱 990원_20261120_2105.md", exportFileName(listOf(read), now))
        val slash = ExportEntry(SavedLink("u", "a/b:c*d?\"e\"<f>|g"), "기타", ArticleResult.Unsupported(""))
        assertEquals("a b c d e f g_20261120_2105.md", exportFileName(listOf(slash), now))
        assertEquals("저장한글_20261120_2105.md", exportFileName(listOf(read, slash), now))
        assertFalse(exportFileName(listOf(read), now).contains("/"))
    }
}
