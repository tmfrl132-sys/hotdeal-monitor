package com.tmfrl.hotdeal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {

    @Test
    fun autoAssignFollowsCollectorCategory() {
        assertEquals(GIFTCARD_ID, autoCategory("컬쳐랜드 8% 할인", "뽐뿌 쿠폰게시판", "상품권"))
        assertEquals(PHONE_ID, autoCategory("모요 0원 요금제", "뽐뿌 휴대폰포럼", "알뜰폰"))
        assertEquals(FINANCE_ID, autoCategory("토스 특판 적금", "뽐뿌 재테크포럼", "재테크"))
        assertEquals(HOTDEAL_ID, autoCategory("선스틱 990원", "뽐뿌 쇼핑뽐뿌", "핫딜"))
        assertEquals(CARD_ID, autoCategory("신용카드 추천", "정가거부", "신용카드"))
        // 단종 소식은 label로도 알 수 있다
        assertEquals(CARD_ID, autoCategory("○○카드 발급 중단", "뽐뿌게시판", "핫딜", "신용카드·단종"))
    }

    @Test
    fun ssalmeokGoesToPhoneWhateverTheBoard() {
        assertEquals(PHONE_ID, autoCategory("이번 달 쌀먹 정리", "뽐뿌 재테크포럼", "재테크"))
        assertEquals(PHONE_ID, autoCategory("쌀 먹 요금제", "", null))
    }

    @Test
    fun withoutCollectorCategoryUsesTitleThenBoard() {
        assertEquals(GIFTCARD_ID, autoCategory("신세계 상품권 92%", "뽐뿌게시판"))
        assertEquals(PHONE_ID, autoCategory("유심 무료", "뽐뿌게시판"))
        assertEquals(CARD_ID, autoCategory("체크카드 막차 탑승", "클리앙 알뜰구매"))
        assertEquals(PHONE_ID, autoCategory("갤럭시 차비 받았네요", "뽐뿌 휴대폰포럼"))
        assertEquals(HOTDEAL_ID, autoCategory("에어팟 최저가", "클리앙 알뜰구매"))
        assertEquals(FINANCE_ID, autoCategory("네이버페이 포인트 받기", ""))
    }

    @Test
    fun pastedLinkWithoutInfoIsEtc() {
        val url = "https://example.com/post/1"
        assertEquals(ETC_ID, autoCategory(url))
        assertEquals(ETC_ID, autoCategory(url, ""))
        assertEquals(ETC_ID, autoCategory("아무 제목", ""))
    }

    @Test
    fun oldSavedFileWithoutCategoryStillLoadsAndGetsAssigned() {
        // v0.3.0이 쓰던 saved.json (category 필드 없음)
        val old = """[
            {"url":"https://www.ppomppu.co.kr/zboard/view.php?id=phone&no=1","title":"모요 쌀먹 요금제",
             "sourceLabel":"뽐뿌 휴대폰포럼","savedAt":"2026-11-18T00:00:00Z","comments":3,"seenComments":3,"checkedAt":null},
            {"url":"https://a.com/x","title":"https://a.com/x","sourceLabel":"","savedAt":"2026-11-18T00:00:00Z"}
        ]"""
        val raw = parseSaved(old)
        assertEquals(2, raw.size)
        assertNull(raw[0].category)
        val list = assignMissingCategories(raw)
        assertEquals(listOf(PHONE_ID, ETC_ID), list.map { it.category })
        assertEquals(3, list[0].comments)
        // 다시 저장하고 읽어도 그대로
        assertEquals(list, parseSaved(encodeSaved(list)))
    }

    @Test
    fun assignedCategoryIsKept() {
        val link = SavedLink("u", "상품권", category = HOTDEAL_ID)
        assertEquals(HOTDEAL_ID, assignMissingCategories(listOf(link)).single().category)
    }

    @Test
    fun deleteMovesLinksToEtc() {
        val links = listOf(
            SavedLink("a", "A", category = GIFTCARD_ID),
            SavedLink("b", "B", category = PHONE_ID),
            SavedLink("c", "C", category = GIFTCARD_ID),
        )
        val (cats, moved) = deleteCategory(DEFAULT_CATEGORIES, links, GIFTCARD_ID)
        assertTrue(cats.none { it.id == GIFTCARD_ID })
        assertEquals(listOf(ETC_ID, PHONE_ID, ETC_ID), moved.map { it.category })
    }

    @Test
    fun etcCannotBeDeletedButCanBeRenamed() {
        val links = listOf(SavedLink("a", "A", category = ETC_ID))
        val (cats, same) = deleteCategory(DEFAULT_CATEGORIES, links, ETC_ID)
        assertEquals(DEFAULT_CATEGORIES, cats)
        assertEquals(links, same)
        assertEquals("미분류", categoryName(ETC_ID, renameCategory(DEFAULT_CATEGORIES, ETC_ID, " 미분류 ")))
    }

    @Test
    fun addRenameAndMoveToNewCategory() {
        val cats = addCategory(DEFAULT_CATEGORIES, " 여행 ", "c1")
        assertEquals("여행", cats.first { it.id == "c1" }.name)
        assertEquals(ETC_ID, cats.last().id) // 기타는 늘 맨 끝
        assertEquals(cats, addCategory(cats, "여행", "c2")) // 같은 이름은 안 만든다
        assertEquals(cats, addCategory(cats, "  ", "c3"))
        assertEquals(cats, renameCategory(cats, "c1", "핫딜")) // 다른 분류와 같은 이름은 안 된다
        val renamed = renameCategory(cats, HOTDEAL_ID, "특가")
        assertEquals("특가", categoryName(HOTDEAL_ID, renamed))

        val links = listOf(SavedLink("a", "A", category = HOTDEAL_ID), SavedLink("b", "B", category = PHONE_ID))
        val moved = moveLinks(links, setOf("a"), "c1")
        assertEquals(listOf("c1", PHONE_ID), moved.map { it.category })
        assertEquals(listOf("a"), inSavedCategory(moved, cats, "c1").map { it.url })
        assertEquals(2, inSavedCategory(moved, cats, null).size)
    }

    @Test
    fun linkPointingToMissingCategoryShowsAsEtc() {
        val link = SavedLink("a", "A", category = "gone")
        assertEquals(ETC_ID, categoryOf(link, DEFAULT_CATEGORIES))
        assertEquals(listOf(link), inSavedCategory(listOf(link), DEFAULT_CATEGORIES, ETC_ID))
    }

    @Test
    fun brokenCategoryFileFallsBackAndEtcIsAlwaysThere() {
        assertEquals(DEFAULT_CATEGORIES, parseCategories("{깨짐"))
        val custom = listOf(SavedCategory("c1", "여행"))
        assertEquals(listOf("c1", ETC_ID), parseCategories(encodeCategories(custom)).map { it.id })
    }
}
