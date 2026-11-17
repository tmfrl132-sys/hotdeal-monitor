package com.tmfrl.hotdeal.data

import android.content.Context
import com.tmfrl.hotdeal.model.DealsData
import com.tmfrl.hotdeal.model.UserKeywords
import com.tmfrl.hotdeal.model.encodeDeals
import com.tmfrl.hotdeal.model.encodeKeywords
import com.tmfrl.hotdeal.model.parseDeals
import com.tmfrl.hotdeal.model.parseKeywords
import kotlinx.coroutines.Dispatchers
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

/** 위젯에서 ✕로 숨긴 글 id(hidden.json). 기기에만 저장한다. */
class HiddenStore(private val file: File) {

    fun read(): Set<String> = try {
        if (file.exists()) Json.decodeFromString(ListSerializer(String.serializer()), file.readText()).toSet() else emptySet()
    } catch (e: IllegalArgumentException) {
        emptySet()
    } catch (e: IOException) {
        emptySet()
    }

    /** id를 숨긴다. 지금 deals.json에 없는 옛 id는 함께 지워 파일이 계속 커지지 않게 한다. */
    suspend fun hide(id: String, current: Set<String>) = write { (it intersect current) + id }

    suspend fun clear() = write { emptySet() }

    private suspend fun write(transform: (Set<String>) -> Set<String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val after = transform(read())
            writeAtomic(file, Json.encodeToString(ListSerializer(String.serializer()), after.sorted()))
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
