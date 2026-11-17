package com.tmfrl.hotdeal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 수집기(monitor/publish.py)가 만드는 deals.json 형식 그대로 */
const val SAMPLE = """{"version":1,"generated_at":"2026-11-17T03:00:00Z","new_field":1,
"sources":[{"name":"ppomppu:phone","label":"뽐뿌 휴대폰포럼","ok":true,"error":null,"count":15},
           {"name":"fmkorea:hotdeal","label":"FM코리아 핫딜","ok":false,"error":"차단 응답 403","count":0}],
"items":[
 {"id":"ppomppu:phone:1","source":"ppomppu:phone","source_label":"뽐뿌 휴대폰포럼","category":"알뜰폰",
  "title":"○○모바일 7개월 0원 쌀먹","url":"https://x/1","posted_at":"2026-11-17T02:57:00Z",
  "recommends":2,"comments":11,"ended":false,"pick":"instant","fire":true,"label":""},
 {"id":"youtube:jgb:v","source":"youtube:jgb","source_label":"정가거부","category":"신용카드",
  "title":"○○카드 단종 막차","url":"https://y/v","posted_at":"2026-11-17T01:00:00Z",
  "recommends":null,"comments":null,"ended":false,"pick":"instant","fire":true,"label":"신용카드·단종"},
 {"id":"ppomppu:ppomppu:2","source":"ppomppu:ppomppu","source_label":"뽐뿌게시판","category":"핫딜",
  "title":"[쿠팡] 컬쳐랜드 굿즈","url":"https://x/2","posted_at":"2026-11-17T02:50:00Z",
  "recommends":0,"comments":1,"ended":false,"pick":null,"fire":false,"label":""},
 {"id":"ppomppu:ppomppu:3","source":"ppomppu:ppomppu","source_label":"뽐뿌게시판","category":"핫딜",
  "title":"[쿠팡] 니트 품절","url":"https://x/3","posted_at":"2026-11-17T02:40:00Z",
  "recommends":9,"comments":30,"ended":true,"pick":"queue","fire":false,"label":""},
 {"id":"ppomppu:coupon:4","source":"ppomppu:coupon","source_label":"뽐뿌 쿠폰게시판","category":"상품권",
  "title":"신세계상품권 6% 할인","url":"https://x/4","posted_at":"2026-11-17T02:30:00Z",
  "recommends":3,"comments":9,"ended":false,"pick":"instant","fire":false,"label":""}
]}"""

class DealsTest {
    @Test
    fun parsesCollectorOutputAndIgnoresUnknownFields() {
        val d = parseDeals(SAMPLE)
        assertEquals("2026-11-17T03:00:00Z", d.generatedAt)
        assertEquals(5, d.items.size)
        val yt = d.items[1]
        assertEquals("정가거부", yt.sourceLabel)
        assertNull(yt.recommends)
        assertEquals("신용카드·단종", yt.label)
        assertNull(d.items[2].pick)
        assertTrue(d.items[3].ended)
        assertFalse(d.sources[1].ok)
    }

    @Test
    fun roundTrips() {
        val d = parseDeals(SAMPLE)
        assertEquals(d, parseDeals(encodeDeals(d)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun brokenJsonThrows() {
        parseDeals("{\"items\": [")
    }
}
