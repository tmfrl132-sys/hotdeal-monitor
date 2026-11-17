package com.tmfrl.hotdeal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTest {
    private val data = parseDeals(SAMPLE)

    private fun ids(list: List<VisibleDeal>) = list.map { it.item.id }

    @Test
    fun showsCollectorPicksNewestFirstAndHidesEnded() {
        val v = visibleDeals(data, UserKeywords())
        assertEquals(listOf("ppomppu:phone:1", "ppomppu:coupon:4", "youtube:jgb:v"), ids(v))
    }

    @Test
    fun tagUsesLabelThenCategory() {
        val v = visibleDeals(data, UserKeywords()).associateBy { it.item.id }
        assertEquals("신용카드·단종", v.getValue("youtube:jgb:v").tag)
        assertEquals("알뜰폰", v.getValue("ppomppu:phone:1").tag)
    }

    @Test
    fun userInstantKeywordAddsUnpickedPost() {
        val kw = UserKeywords(instant = listOf(Keyword("컬쳐랜드")))
        val v = visibleDeals(data, kw)
        assertTrue("ppomppu:ppomppu:2" in ids(v))
        assertTrue(v.first { it.item.id == "ppomppu:ppomppu:2" }.byKeyword)
    }

    @Test
    fun scopedKeywordOnlyMatchesItsCategory() {
        val kw = UserKeywords(instant = listOf(Keyword("컬쳐랜드", "상품권")))
        assertFalse("ppomppu:ppomppu:2" in ids(visibleDeals(data, kw)))
    }

    @Test
    fun matchIgnoresSpacesAndCase() {
        assertTrue(matches("○○모바일 7개월 0원 쌀 먹", Keyword("쌀먹"), "알뜰폰"))
        assertTrue(matches("KT MVNO 특가", Keyword("mvno"), "알뜰폰"))
        assertFalse(matches("아무 제목", Keyword("  "), "핫딜"))
    }

    @Test
    fun userExcludeHidesEvenPickedPosts() {
        val kw = UserKeywords(exclude = listOf(Keyword("신세계")))
        assertFalse("ppomppu:coupon:4" in ids(visibleDeals(data, kw)))
    }

    @Test
    fun ssalmeokKeywordMakesFire() {
        val unpicked = data.copy(items = listOf(data.items[2].copy(title = "쌀먹 요금제", pick = null)))
        val v = visibleDeals(unpicked, UserKeywords(instant = listOf(Keyword("쌀먹"))))
        assertTrue(v.single().fire)
    }

    @Test
    fun categoryFilter() {
        val v = visibleDeals(data, UserKeywords())
        assertEquals(listOf("ppomppu:coupon:4"), ids(inCategory(v, "상품권")))
        assertEquals(3, inCategory(v, null).size)
        // 단종 소식은 신용카드로 본다
        assertEquals(listOf("youtube:jgb:v"), ids(inCategory(v, "신용카드")))
    }

    @Test
    fun nextCategoryCycles() {
        assertEquals("상품권", nextCategory(null))
        assertEquals(null, nextCategory(FILTERS.last()))
        assertEquals(null, nextCategory("모르는값"))
    }
}
