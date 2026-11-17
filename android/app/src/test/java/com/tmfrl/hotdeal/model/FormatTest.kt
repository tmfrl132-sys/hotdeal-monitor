package com.tmfrl.hotdeal.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FormatTest {
    private val now = Instant.parse("2026-11-17T03:00:00Z")

    @Test
    fun ago() {
        assertEquals("방금", formatAgo("2026-11-17T02:59:40Z", now))
        assertEquals("3분", formatAgo("2026-11-17T02:57:00Z", now))
        assertEquals("2시간", formatAgo("2026-11-17T01:00:00Z", now))
        assertEquals("1일", formatAgo("2026-11-16T02:00:00Z", now))
        assertEquals("", formatAgo("깨진값", now))
    }

    @Test
    fun metaLineOmitsMissingCounts() {
        val d = parseDeals(SAMPLE)
        assertEquals("뽐뿌 휴대폰포럼 · 3분 · 👍2 💬11", metaLine(d.items[0], now))
        assertEquals("정가거부 · 2시간", metaLine(d.items[1], now))
    }

    @Test
    fun updatedInSeoulTime() {
        val seoul = ZoneId.of("Asia/Seoul")
        assertEquals("12:00", formatUpdated("2026-11-17T03:00:00Z", seoul))
        assertEquals("", formatUpdated("", seoul))
    }
}
