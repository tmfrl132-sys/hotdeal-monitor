package com.tmfrl.hotdeal.data

import android.content.Context
import com.tmfrl.hotdeal.model.ArticleResult
import com.tmfrl.hotdeal.model.ExportEntry
import com.tmfrl.hotdeal.model.SavedCategory
import com.tmfrl.hotdeal.model.SavedLink
import com.tmfrl.hotdeal.model.categoryName
import com.tmfrl.hotdeal.model.categoryOf
import com.tmfrl.hotdeal.model.exportFileName
import com.tmfrl.hotdeal.model.readArticle
import com.tmfrl.hotdeal.model.renderBundle
import com.tmfrl.hotdeal.model.renderSingle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime

/** 사이트에 부담을 주지 않게 요청 사이에 쉬는 시간 */
const val EXPORT_GAP_MS = 700L

/** 내보내기 진행 상황: 글 done/total, 지금 받는 글의 댓글 쪽 page/pages */
data class ExportProgress(val done: Int, val total: Int, val title: String, val page: Int = 0, val pages: Int = 0)

data class ExportResult(val fileName: String, val markdown: String, val failed: Int, val count: Int)

/**
 * 고른 글의 원문을 지금 새로 받아(최신 댓글 반영) 마크다운으로 만든다.
 * 글이 하나면 단독 파일, 여럿이면 목차가 있는 묶음 파일이다. 한 글이 실패해도 그 자리에 이유를 적고 계속한다.
 */
suspend fun exportMarkdown(
    links: List<SavedLink>,
    categories: List<SavedCategory>,
    now: ZonedDateTime,
    fetch: suspend (String) -> String,
    gapMs: Long = EXPORT_GAP_MS,
    onProgress: (ExportProgress) -> Unit = {},
): ExportResult {
    val entries = links.mapIndexed { i, link ->
        if (i > 0) delay(gapMs)
        onProgress(ExportProgress(i, links.size, link.title))
        val result = try {
            readArticle(link.url, fetch, gapMs) { page, pages ->
                onProgress(ExportProgress(i, links.size, link.title, page, pages))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // 예상하지 못한 페이지 모양 등. 이 글만 실패로 적는다.
            ArticleResult.Failed("원문을 읽다가 오류가 났습니다 (${e.message ?: e.javaClass.simpleName})")
        }
        ExportEntry(link, categoryName(categoryOf(link, categories), categories), result)
    }
    onProgress(ExportProgress(links.size, links.size, ""))
    val markdown = if (entries.size == 1) renderSingle(entries.single(), now) else renderBundle(entries, now)
    return ExportResult(
        fileName = exportFileName(entries, now),
        markdown = markdown,
        failed = entries.count { it.result !is ArticleResult.Read },
        count = entries.size,
    )
}

/** 실제 기기에서 쓰는 받기 함수 */
val pageFetcher: suspend (String) -> String = { url -> withContext(Dispatchers.IO) { httpGetPage(url) } }

/** 공유할 파일을 cache/exports에 쓴다. 지난 내보내기 파일은 지운다. FileProvider(file_paths.xml)가 이 폴더를 연다. */
fun writeExportFile(context: Context, result: ExportResult): File {
    val dir = File(context.cacheDir, "exports")
    dir.mkdirs()
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, result.fileName)
    file.writeText(result.markdown)
    return file
}
