package com.tmfrl.hotdeal.data

import android.content.Context
import com.tmfrl.hotdeal.model.DealsData
import com.tmfrl.hotdeal.model.SavedLink
import com.tmfrl.hotdeal.model.UserKeywords
import com.tmfrl.hotdeal.model.applyCheck
import com.tmfrl.hotdeal.model.commentPageUrl
import com.tmfrl.hotdeal.model.decodeHtml
import com.tmfrl.hotdeal.model.encodeDeals
import com.tmfrl.hotdeal.model.encodeKeywords
import com.tmfrl.hotdeal.model.encodeSaved
import com.tmfrl.hotdeal.model.parseCommentCount
import com.tmfrl.hotdeal.model.parseDeals
import com.tmfrl.hotdeal.model.parseHtmlTitle
import com.tmfrl.hotdeal.model.parseKeywords
import com.tmfrl.hotdeal.model.parseSaved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

const val DEALS_URL = "https://tmfrl132-sys.github.io/hotdeal-monitor/deals.json"
private const val TIMEOUT_MS = 15_000
private const val CHECK_GAP_MS = 1_000L // 저장한 글을 확인할 때 사이트에 부담을 주지 않는 간격
private const val PAGE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

/** 위젯이 쓰다 만 파일을 읽지 않게 임시 파일에 다 쓴 뒤 한 번에 바꿔치기한다. */
internal fun writeAtomic(target: File, text: String) {
    target.parentFile?.mkdirs()
    val temp = File.createTempFile(target.nameWithoutExtension, ".tmp", target.parentFile)
    try {
        temp.writeText(text)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temp.delete()
    }
}

/** GET 요청 본문. 200이 아니면 IOException. Dispatchers.IO에서 부른다. */
internal fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.useCaches = false
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

/** 글 페이지 본문. 뽐뿌 모바일 페이지는 휴대기기 이름과 Referer가 없으면 403을 준다. */
internal fun httpGetPage(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", PAGE_USER_AGENT)
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
        conn.setRequestProperty("Referer", "https://m.ppomppu.co.kr/")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
        return decodeHtml(conn.inputStream.use { it.readBytes() })
    } finally {
        conn.disconnect()
    }
}

/**
 * 저장한 글의 댓글 수를 읽는다. 뽐뿌·클리앙만 읽을 수 있고, 붙여 넣은 링크는 제목도 채운다.
 * 한 글이 실패해도 나머지는 계속 확인하고, 실패한 글은 이전 값을 유지한다.
 */
suspend fun checkSavedLinks(store: SavedStore, now: String, fetch: (String) -> String) = withContext(Dispatchers.IO) {
    store.read().forEach { link ->
        val page = commentPageUrl(link.url)
        val target = page ?: link.url.takeIf { link.title == link.url } ?: return@forEach
        val html = try {
            fetch(target)
        } catch (e: IOException) {
            return@forEach
        } catch (e: IllegalArgumentException) {
            return@forEach
        }
        val count = if (page != null) parseCommentCount(link.url, html) else null
        val title = parseHtmlTitle(html)
        store.update { list -> list.map { if (it.url == link.url) applyCheck(it, count, title, now) else it } }
        delay(CHECK_GAP_MS)
    }
}

/** 댓글을 지켜보려고 저장한 글(saved.json). 기기에만 저장한다. */
class SavedStore(private val file: File) {

    fun read(): List<SavedLink> = try {
        if (file.exists()) parseSaved(file.readText()) else emptyList()
    } catch (e: IOException) {
        emptyList()
    }

    suspend fun update(transform: (List<SavedLink>) -> List<SavedLink>): List<SavedLink> = withContext(Dispatchers.IO) {
        lock.withLock {
            val after = transform(read())
            writeAtomic(file, encodeSaved(after))
            after
        }
    }

    companion object {
        private val lock = Mutex()

        fun forContext(context: Context) = SavedStore(File(context.filesDir, "saved.json"))
    }
}

/** deals.json을 받아 기기에 캐시한다. 받지 못하거나 깨졌으면 마지막 캐시를 쓴다. */
class Repository(private val cacheFile: File, private val fetch: () -> String) {

    suspend fun refresh(): DealsData? = withContext(Dispatchers.IO) {
        try {
            val text = fetch()
            val data = parseDeals(text)
            writeAtomic(cacheFile, encodeDeals(data))
            data
        } catch (e: IOException) {
            cached()
        } catch (e: IllegalArgumentException) { // 잘못된 JSON (SerializationException 포함)
            cached()
        }
    }

    fun cached(): DealsData? = try {
        if (cacheFile.exists()) parseDeals(cacheFile.readText()) else null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IOException) {
        null
    }

    companion object {
        fun forContext(context: Context) = Repository(File(context.filesDir, "deals.json")) { httpGet(DEALS_URL) }
    }
}

/**
 * 위젯에서 ✕로 숨긴 글 id(hidden.json). 기기에만 저장한다.
 * 숨긴 순서대로 저장해서 되돌리기(undo)는 가장 최근에 숨긴 글부터 살린다.
 */
class HiddenStore(private val file: File) {

    fun read(): Set<String> = readOrdered().toSet()

    private fun readOrdered(): List<String> = try {
        if (file.exists()) Json.decodeFromString(ListSerializer(String.serializer()), file.readText()) else emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    } catch (e: IOException) {
        emptyList()
    }

    /** id를 숨긴다. 지금 deals.json에 없는 옛 id는 함께 지워 파일이 계속 커지지 않게 한다. */
    suspend fun hide(id: String, current: Set<String>) {
        write { list -> list.filter { it in current && it != id } + id }
    }

    /** 가장 최근에 숨긴 글을 되살리고 그 id를 돌려준다. 숨긴 글이 없으면 null. */
    suspend fun undo(): String? {
        var restored: String? = null
        write { list ->
            restored = list.lastOrNull()
            list.dropLast(1)
        }
        return restored
    }

    suspend fun clear() {
        write { emptyList() }
    }

    private suspend fun write(transform: (List<String>) -> List<String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val after = transform(readOrdered())
            writeAtomic(file, Json.encodeToString(ListSerializer(String.serializer()), after))
        }
    }

    companion object {
        private val lock = Mutex()

        fun forContext(context: Context) = HiddenStore(File(context.filesDir, "hidden.json"))
    }
}

/** 사용자 키워드 파일(keywords.json). 기기에만 저장한다. */
class KeywordStore(private val file: File) {

    fun read(): UserKeywords = try {
        if (file.exists()) parseKeywords(file.readText()) else UserKeywords()
    } catch (e: IOException) {
        UserKeywords()
    }

    /** 읽고 고쳐서 원자적으로 쓴다. 동시에 고쳐도 한쪽 변경이 사라지지 않게 하나씩 한다. */
    suspend fun update(transform: (UserKeywords) -> UserKeywords): UserKeywords = withContext(Dispatchers.IO) {
        lock.withLock {
            val after = transform(read())
            writeAtomic(file, encodeKeywords(after))
            after
        }
    }

    companion object {
        private val lock = Mutex()

        fun forContext(context: Context) = KeywordStore(File(context.filesDir, "keywords.json"))
    }
}
