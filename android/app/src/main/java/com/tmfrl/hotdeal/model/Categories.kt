package com.tmfrl.hotdeal.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 저장한 글의 분류. id는 바뀌지 않고, 이름은 사용자가 바꿀 수 있다. 기기에만 저장한다. */
@Serializable
data class SavedCategory(val id: String, val name: String)

const val ETC_ID = "etc"
const val GIFTCARD_ID = "giftcard"
const val PHONE_ID = "phone"
const val CARD_ID = "card"
const val FINANCE_ID = "finance"
const val HOTDEAL_ID = "deal"

/**
 * 앱이 처음에 만들어 두는 분류. 수집기(monitor/rules.py)의 카테고리(상품권·알뜰폰·신용카드·재테크·핫딜)에 맞췄다.
 * "기타"는 지울 수 없다(지운 분류의 글이 옮겨 가는 곳). 이름은 바꿀 수 있다.
 */
val DEFAULT_CATEGORIES = listOf(
    SavedCategory(GIFTCARD_ID, "상테크(상품권)"),
    SavedCategory(PHONE_ID, "알뜰폰·쌀먹"),
    SavedCategory(CARD_ID, "카드"),
    SavedCategory(FINANCE_ID, "재테크·이벤트"),
    SavedCategory(HOTDEAL_ID, "핫딜"),
    SavedCategory(ETC_ID, "기타"),
)

// 수집기 settings.yaml의 카테고리 키워드와 같은 단어. 수집기 카테고리가 없는 글(옛 저장 글)에 쓴다.
private val GIFTCARD_WORDS = listOf(
    "상품권", "컬쳐랜드", "해피머니", "문화상품권", "신세계", "롯데상품권", "현대상품권", "온누리", "지류",
    "모바일상품권", "북앤라이프", "도서문화상품권", "기프트카드", "페이머니", "상테크",
)
private val PHONE_WORDS = listOf("요금제", "알뜰폰", "유심", "쌀먹", "0원요금제", "데이터무제한", "mvno")
private val FINANCE_WORDS = listOf("적금", "예금", "특판", "금리", "이벤트", "증권", "캐시백", "앱테크", "포인트", "리워드")
private val DISCONTINUE_WORDS = listOf("단종", "발급중단", "막차")
private val CARD_WORDS = listOf("카드", "신용카드", "체크카드", "연회비")

/** 수집 게시판(sourceLabel)마다 기본 분류. settings.yaml의 sources 첫 카테고리와 같다. */
private val BOARD_DEFAULT = listOf(
    "휴대폰포럼" to PHONE_ID,
    "쿠폰게시판" to FINANCE_ID,
    "재테크포럼" to FINANCE_ID,
    "이벤트게시판" to FINANCE_ID,
    "증권포럼" to FINANCE_ID,
    "뽐뿌게시판" to HOTDEAL_ID,
    "쇼핑뽐뿌" to HOTDEAL_ID,
    "알뜰구매" to HOTDEAL_ID,
    "FM코리아" to HOTDEAL_ID,
    "정가거부" to HOTDEAL_ID,
)

private fun hasAny(title: String, words: List<String>): Boolean {
    val t = normalize(title)
    return words.any { normalize(it) in t }
}

/**
 * 글을 저장할 때 기본 분류를 정한다.
 * 수집기 판정(dealCategory·dealLabel)을 먼저 따르고, 없으면 제목 키워드와 게시판으로 고른다.
 * 링크만 붙여 넣은 글처럼 알 수 없으면 기타.
 */
fun autoCategory(
    title: String,
    sourceLabel: String = "",
    dealCategory: String? = null,
    dealLabel: String? = null,
): String {
    if (dealLabel?.contains("단종") == true || dealCategory == "신용카드") return CARD_ID
    if (hasAny(title, listOf("쌀먹"))) return PHONE_ID
    when (dealCategory) {
        "상품권" -> return GIFTCARD_ID
        "알뜰폰" -> return PHONE_ID
        "재테크" -> return FINANCE_ID
        "핫딜" -> return HOTDEAL_ID
    }
    // 수집기 판정이 없는 글: 수집기와 같은 순서(단종 카드 → 상품권 → 알뜰폰 → 재테크)로 제목을 본다
    val known = sourceLabel.isNotBlank() || (title.isNotBlank() && !isSavableUrl(title))
    if (!known) return ETC_ID
    if (hasAny(title, DISCONTINUE_WORDS) && hasAny(title, CARD_WORDS)) return CARD_ID
    if (hasAny(title, GIFTCARD_WORDS)) return GIFTCARD_ID
    if (hasAny(title, PHONE_WORDS)) return PHONE_ID
    BOARD_DEFAULT.firstOrNull { (board, _) -> board in sourceLabel }?.let { return it.second }
    if (hasAny(title, FINANCE_WORDS)) return FINANCE_ID
    return ETC_ID
}

/** 옛 저장 파일(분류 필드 없음)의 글에 분류를 매긴다. 이미 분류가 있으면 그대로 둔다. */
fun assignMissingCategories(list: List<SavedLink>): List<SavedLink> =
    list.map { if (it.category == null) it.copy(category = autoCategory(it.title, it.sourceLabel)) else it }

/** 글의 실제 분류 id. 없어진 분류를 가리키면 기타. */
fun categoryOf(link: SavedLink, categories: List<SavedCategory>): String {
    val id = link.category ?: autoCategory(link.title, link.sourceLabel)
    return if (categories.any { it.id == id }) id else ETC_ID
}

fun categoryName(id: String, categories: List<SavedCategory>): String =
    categories.firstOrNull { it.id == id }?.name ?: categories.firstOrNull { it.id == ETC_ID }?.name ?: "기타"

/** 분류 칩으로 거른 목록. id가 null이면 전체. */
fun inSavedCategory(list: List<SavedLink>, categories: List<SavedCategory>, id: String?): List<SavedLink> =
    if (id == null) list else list.filter { categoryOf(it, categories) == id }

/** 이름이 비었거나 같은 이름이 있으면 그대로 돌려준다. */
fun addCategory(categories: List<SavedCategory>, name: String, newId: String): List<SavedCategory> {
    val n = name.trim()
    if (n.isEmpty() || categories.any { it.name == n || it.id == newId }) return categories
    // 기타는 늘 맨 끝에 둔다
    val etc = categories.filter { it.id == ETC_ID }
    return categories.filterNot { it.id == ETC_ID } + SavedCategory(newId, n) + etc
}

fun renameCategory(categories: List<SavedCategory>, id: String, name: String): List<SavedCategory> {
    val n = name.trim()
    if (n.isEmpty() || categories.any { it.name == n && it.id != id }) return categories
    return categories.map { if (it.id == id) it.copy(name = n) else it }
}

/** 분류를 지운다. 기타는 지울 수 없다. 그 분류의 글은 기타로 옮긴다. */
fun deleteCategory(
    categories: List<SavedCategory>,
    links: List<SavedLink>,
    id: String,
): Pair<List<SavedCategory>, List<SavedLink>> {
    if (id == ETC_ID) return categories to links
    return categories.filterNot { it.id == id } to moveLinks(links, links.filter { it.category == id }.map { it.url }.toSet(), ETC_ID)
}

fun moveLinks(links: List<SavedLink>, urls: Set<String>, categoryId: String): List<SavedLink> =
    links.map { if (it.url in urls) it.copy(category = categoryId) else it }

/** 기타가 빠진 파일이면 다시 넣는다. */
fun withEtc(categories: List<SavedCategory>): List<SavedCategory> =
    if (categories.any { it.id == ETC_ID }) categories else categories + DEFAULT_CATEGORIES.last()

private val json = Json { ignoreUnknownKeys = true }
private val categorySerializer = ListSerializer(SavedCategory.serializer())

/** 깨진 파일이면 기본 분류. */
fun parseCategories(text: String): List<SavedCategory> = try {
    withEtc(json.decodeFromString(categorySerializer, text))
} catch (e: IllegalArgumentException) {
    DEFAULT_CATEGORIES
}

fun encodeCategories(list: List<SavedCategory>): String = json.encodeToString(categorySerializer, list)
