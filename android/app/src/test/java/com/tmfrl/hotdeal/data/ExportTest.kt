package com.tmfrl.hotdeal.data

import com.tmfrl.hotdeal.model.DEFAULT_CATEGORIES
import com.tmfrl.hotdeal.model.ETC_ID
import com.tmfrl.hotdeal.model.PHONE_ID
import com.tmfrl.hotdeal.model.SavedLink
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime

class ExportTest {
    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()
    private val now = ZonedDateTime.of(2026, 11, 20, 21, 5, 0, 0, ZoneId.of("Asia/Seoul"))

    private val ppomppu = SavedLink(
        "https://www.ppomppu.co.kr/zboard/view.php?id=phone&no=3939103", "퀀텀7", "뽐뿌 휴대폰포럼", category = PHONE_ID,
    )
    private val broken = SavedLink("https://www.clien.net/service/board/jirum/1", "없어진 글", "클리앙 알뜰구매", category = "gone")
    private val youtube = SavedLink("https://www.youtube.com/watch?v=abc", "카드 영상", "정가거부", category = ETC_ID)

    private val fetch: suspend (String) -> String = { url ->
        when {
            url.endsWith("no=3939103") -> page("ppomppu_phone_p1.html")
            url.endsWith("c_page=2") -> page("ppomppu_phone_p2.html")
            else -> throw IOException("HTTP 404")
        }
    }

    @Test
    fun bundleKeepsGoingAfterAFailure() = runTest {
        val progress = mutableListOf<ExportProgress>()
        val result = exportMarkdown(listOf(broken, ppomppu, youtube), DEFAULT_CATEGORIES, now, fetch) { progress += it }
        assertEquals("저장한글_20261120_2105.md", result.fileName)
        assertEquals(3, result.count)
        assertEquals(2, result.failed)
        val md = result.markdown
        assertTrue(md.contains("> ⚠ 변환 실패: 원문을 받지 못했습니다 (HTTP 404)"))
        assertTrue(md.contains("## 슬슬 퀀텀7도 차비로 나오네요~"))
        assertTrue(md.contains("### 댓글 (97개)"))
        assertTrue(md.contains("- 분류: 알뜰폰·쌀먹"))
        assertTrue(md.contains("- 분류: 기타")) // 없어진 분류를 가리키던 글
        assertTrue(md.contains("> 본문을 읽을 수 없는 사이트입니다."))
        // 진행: 글 0/3 → 1/3(댓글 1/2쪽, 2/2쪽) → … → 3/3
        assertEquals(ExportProgress(3, 3, ""), progress.last())
        assertTrue(progress.any { it.done == 1 && it.page == 2 && it.pages == 2 })
    }

    @Test
    fun singleExportIsItsOwnFile() = runTest {
        val result = exportMarkdown(listOf(ppomppu), DEFAULT_CATEGORIES, now, fetch)
        assertEquals("슬슬 퀀텀7도 차비로 나오네요~_20261120_2105.md", result.fileName)
        assertTrue(result.markdown.startsWith("# 슬슬 퀀텀7도 차비로 나오네요~\n"))
        assertTrue(result.markdown.contains("**댓글 50** · "))
        assertTrue(result.markdown.contains("> **└ 답글 (댓글 1)**"))
        assertTrue(result.markdown.contains("> > **└└ 답글"))
        assertEquals(0, result.failed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun exportCanBeCancelled() = runTest {
        var asked = 0
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            exportMarkdown(listOf(ppomppu, ppomppu.copy(url = ppomppu.url + "&x=1")), DEFAULT_CATEGORIES, now, { url ->
                asked++
                fetch(url)
            })
        }
        advanceTimeBy(100) // 첫 글 1쪽을 받고 2쪽 앞에서 쉬는 중
        job.cancel()
        assertTrue(job.isCancelled)
        assertEquals(1, asked)
    }
}
