package com.tmfrl.hotdeal.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** 위젯 필터 순서. null(전체) 다음 이 순서로 돈다. */
val FILTERS = listOf("상품권", "알뜰폰", "신용카드", "재테크", "핫딜")

/** category가 null이면 모든 카테고리에 적용한다. */
@Serializable
data class Keyword(val word: String, val category: String? = null)

/**
 * 기기에만 저장하는 사용자 키워드. 수집기 기본값(쌀먹 즉시, 종료·품절·질문글 제외)에 더해진다.
 * instant: 수집기가 고르지 않은 글도 보이게 한다. exclude: 이 단어가 있으면 숨긴다.
 */
@Serializable
data class UserKeywords(
    val instant: List<Keyword> = emptyList(),
    val exclude: List<Keyword> = emptyList(),
) {
    fun withInstant(word: String, category: String?) = copy(instant = add(instant, word, category))
    fun withExclude(word: String, category: String?) = copy(exclude = add(exclude, word, category))
    fun without(k: Keyword) = copy(instant = instant - k, exclude = exclude - k)

    private fun add(list: List<Keyword>, word: String, category: String?): List<Keyword> {
        val k = Keyword(word.trim(), category)
        return if (k.word.isEmpty() || k in list) list else list + k
    }
}

private val json = Json { ignoreUnknownKeys = true }

/** 깨진 파일이면 빈 값. */
fun parseKeywords(text: String): UserKeywords = try {
    json.decodeFromString(UserKeywords.serializer(), text)
} catch (e: SerializationException) {
    UserKeywords()
} catch (e: IllegalArgumentException) {
    UserKeywords()
}

fun encodeKeywords(k: UserKeywords): String = json.encodeToString(UserKeywords.serializer(), k)
