package com.tmfrl.hotdeal.model

/** 위젯에 보일 글 한 줄. */
data class VisibleDeal(
    val item: DealItem,
    /** 🔥 강조: 수집기 판정(쌀먹, 단종) 또는 사용자 키워드 "쌀먹" */
    val fire: Boolean,
    /** 줄 앞 라벨. 예: "알뜰폰", "신용카드·단종" */
    val tag: String,
    /** 수집기는 고르지 않았고 사용자 키워드로 보이는 글 */
    val byKeyword: Boolean,
)

private const val FIRE_WORD = "쌀먹"

/** 공백을 지우고 소문자로 바꿔 비교한다. 수집기(rules._norm)와 같은 규칙이다. */
internal fun normalize(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

fun matches(title: String, k: Keyword, category: String): Boolean {
    val word = normalize(k.word)
    return word.isNotEmpty() && (k.category == null || k.category == category) && word in normalize(title)
}

/**
 * 종료된 글과 사용자 제외 키워드에 걸린 글은 숨긴다.
 * 수집기가 고른 글과 사용자 즉시 키워드에 맞는 글을 최신순으로 돌려준다.
 */
fun visibleDeals(data: DealsData, keywords: UserKeywords): List<VisibleDeal> =
    data.items.mapNotNull { item ->
        if (item.ended) return@mapNotNull null
        if (keywords.exclude.any { matches(item.title, it, item.category) }) return@mapNotNull null
        val hit = keywords.instant.firstOrNull { matches(item.title, it, item.category) }
        if (item.pick == null && hit == null) return@mapNotNull null
        VisibleDeal(
            item = item,
            fire = item.fire || (hit != null && normalize(hit.word) == FIRE_WORD),
            tag = item.label.ifEmpty { item.category },
            byKeyword = item.pick == null,
        )
    }.sortedByDescending { it.item.postedAt }

/** null이면 전체. */
fun inCategory(list: List<VisibleDeal>, category: String?): List<VisibleDeal> =
    if (category == null) list else list.filter { it.item.category == category }

/** 위젯 필터 버튼: 전체 → 상품권 → … → 핫딜 → 전체. 모르는 값이면 전체로 돌아간다. */
fun nextCategory(current: String?): String? {
    if (current == null) return FILTERS.first()
    val i = FILTERS.indexOf(current)
    return if (i < 0 || i == FILTERS.lastIndex) null else FILTERS[i + 1]
}
