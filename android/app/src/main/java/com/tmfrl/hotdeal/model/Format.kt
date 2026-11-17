package com.tmfrl.hotdeal.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

private fun parseInstant(text: String): Instant? = try {
    Instant.parse(text)
} catch (e: DateTimeParseException) {
    null
}

/** "방금", "3분", "2시간", "1일". 읽을 수 없으면 빈 문자열. */
fun formatAgo(postedAt: String, now: Instant): String {
    val at = parseInstant(postedAt) ?: return ""
    val minutes = Duration.between(at, now).toMinutes()
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분"
        minutes < 24 * 60 -> "${minutes / 60}시간"
        else -> "${minutes / (24 * 60)}일"
    }
}

/** "뽐뿌 휴대폰포럼 · 3분 · 👍2 💬11". 반응 수가 없으면 뺀다. */
fun metaLine(item: DealItem, now: Instant): String {
    val parts = mutableListOf(item.sourceLabel)
    formatAgo(item.postedAt, now).takeIf { it.isNotEmpty() }?.let(parts::add)
    val counts = listOfNotNull(item.recommends?.let { "👍$it" }, item.comments?.let { "💬$it" })
    if (counts.isNotEmpty()) parts.add(counts.joinToString(" "))
    return parts.joinToString(" · ")
}

/** 수집 시각을 그 지역 "HH:mm"으로. 없으면 빈 문자열. */
fun formatUpdated(generatedAt: String, zone: ZoneId): String =
    parseInstant(generatedAt)?.atZone(zone)?.format(HHMM) ?: ""
