package com.tmfrl.hotdeal.data

import android.content.Context
import com.tmfrl.hotdeal.model.CollectStatus
import com.tmfrl.hotdeal.model.encodeCollectStatus
import com.tmfrl.hotdeal.model.parseCollectStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Actions의 collect.yml을 바로 실행하게 하는 주소(workflow_dispatch). */
const val COLLECT_DISPATCH_URL =
    "https://api.github.com/repos/tmfrl132-sys/hotdeal-monitor/actions/workflows/collect.yml/dispatches"
private const val TIMEOUT_MS = 15_000

/**
 * collect.yml 실행을 요청하고 HTTP 응답 코드를 돌려준다. 성공은 204.
 * 연결 자체가 안 되면 IOException. Dispatchers.IO에서 부른다.
 */
internal fun dispatchCollect(token: String): Int {
    val conn = URL(COLLECT_DISPATCH_URL).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write("""{"ref":"main"}""".toByteArray()) }
        return conn.responseCode
    } finally {
        conn.disconnect()
    }
}

/**
 * 수동 수집에 쓰는 GitHub 토큰(github_token.txt). 이 기기에만 저장한다.
 * 백업에서 빠지는 폴더(noBackupFilesDir)에 둬서 구글 백업으로 다른 기기에 넘어가지 않게 한다.
 */
class TokenStore(private val file: File) {

    fun read(): String = try {
        if (file.exists()) file.readText().trim() else ""
    } catch (e: IOException) {
        ""
    }

    suspend fun write(token: String) = withContext(Dispatchers.IO) {
        val value = token.trim()
        if (value.isEmpty()) file.delete() else writeAtomic(file, value)
    }

    companion object {
        fun forContext(context: Context) = TokenStore(File(context.noBackupFilesDir, "github_token.txt"))
    }
}

/** 마지막 수동 수집 요청 결과(collect_status.json). 위젯과 설정 화면이 읽는다. */
class CollectStatusStore(private val file: File) {

    fun read(): CollectStatus? = try {
        if (file.exists()) parseCollectStatus(file.readText()) else null
    } catch (e: IOException) {
        null
    }

    suspend fun write(status: CollectStatus) = withContext(Dispatchers.IO) {
        writeAtomic(file, encodeCollectStatus(status))
    }

    companion object {
        fun forContext(context: Context) = CollectStatusStore(File(context.filesDir, "collect_status.json"))
    }
}
