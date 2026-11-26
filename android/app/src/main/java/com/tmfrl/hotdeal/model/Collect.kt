package com.tmfrl.hotdeal.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** 수집 요청 결과 코드. HTTP 코드가 아닌 값은 음수로 둔다. */
const val COLLECT_PENDING = 0
const val COLLECT_NO_TOKEN = -1
const val COLLECT_NETWORK = -2
const val COLLECT_OK = 204

/** 위젯 머리줄에 요청 결과를 보여주는 시간. 지나면 평소처럼 갱신 시각만 보인다. */
val COLLECT_SHOW = Duration.ofMinutes(5)

/** 마지막 수동 수집 요청. requestedAt은 ISO 시각, code는 위 상수나 HTTP 응답 코드. */
@Serializable
data class CollectStatus(val requestedAt: String, val code: Int)

private val json = Json { ignoreUnknownKeys = true }

fun encodeCollectStatus(status: CollectStatus): String = json.encodeToString(CollectStatus.serializer(), status)

fun parseCollectStatus(text: String): CollectStatus? = try {
    json.decodeFromString(CollectStatus.serializer(), text)
} catch (e: IllegalArgumentException) {
    null
}

/** 응답 코드를 사람이 읽는 설명으로. 앱 설정 화면에 보인다. */
fun collectMessage(code: Int): String = when (code) {
    COLLECT_PENDING -> "요청 보내는 중"
    COLLECT_OK -> "요청 성공. 1~2분 뒤 새 글이 반영됩니다"
    COLLECT_NO_TOKEN -> "토큰이 없습니다. 아래에 GitHub 토큰을 넣어 주세요"
    COLLECT_NETWORK -> "인터넷 연결 실패"
    401 -> "토큰이 틀렸거나 만료됐습니다 (401)"
    403, 404 -> "토큰 권한이 부족합니다. Actions: Read and write, hotdeal-monitor 저장소인지 확인하세요 ($code)"
    422 -> "GitHub이 요청을 거절했습니다 (422)"
    else -> "실패 (HTTP $code)"
}

private fun parse(text: String): Instant? = try {
    Instant.parse(text)
} catch (e: DateTimeParseException) {
    null
}

/**
 * 위젯 머리줄에 갱신 시각 대신 보일 짧은 상태. null이면 평소처럼 갱신 시각을 보인다.
 * 요청이 성공한 뒤 새 deals.json(generatedAt이 요청 시각 이후)을 받으면 바로 null로 돌아간다.
 */
fun collectBadge(status: CollectStatus?, generatedAt: String?, now: Instant): String? {
    val at = status?.let { parse(it.requestedAt) } ?: return null
    if (Duration.between(at, now) > COLLECT_SHOW) return null
    return when (status.code) {
        COLLECT_PENDING -> "요청 중…"
        COLLECT_OK -> {
            val generated = generatedAt?.let(::parse)
            if (generated != null && generated.isAfter(at)) null else "수집 중…"
        }
        COLLECT_NO_TOKEN -> "토큰 필요"
        COLLECT_NETWORK -> "연결 실패"
        else -> "수집 실패 ${status.code}"
    }
}
