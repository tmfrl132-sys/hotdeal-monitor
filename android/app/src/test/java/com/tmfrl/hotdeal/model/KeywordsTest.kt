package com.tmfrl.hotdeal.model

import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordsTest {
    @Test
    fun addTrimsAndDeduplicates() {
        val k = UserKeywords().withInstant("  컬쳐랜드 ", "상품권").withInstant("컬쳐랜드", "상품권").withInstant(" ", null)
        assertEquals(listOf(Keyword("컬쳐랜드", "상품권")), k.instant)
    }

    @Test
    fun sameWordDifferentScopeIsKept() {
        val k = UserKeywords().withInstant("쌀먹", null).withInstant("쌀먹", "알뜰폰")
        assertEquals(2, k.instant.size)
    }

    @Test
    fun excludeAndRemove() {
        val k = UserKeywords().withExclude("품절", null).withInstant("페이코", null)
        assertEquals(listOf(Keyword("품절")), k.exclude)
        val removed = k.without(Keyword("페이코"))
        assertEquals(emptyList<Keyword>(), removed.instant)
        assertEquals(1, removed.exclude.size)
    }

    @Test
    fun jsonRoundTripAndBrokenFallsBack() {
        val k = UserKeywords().withInstant("쌀먹", null).withExclude("품절", "핫딜")
        assertEquals(k, parseKeywords(encodeKeywords(k)))
        assertEquals(UserKeywords(), parseKeywords("not json"))
    }
}
