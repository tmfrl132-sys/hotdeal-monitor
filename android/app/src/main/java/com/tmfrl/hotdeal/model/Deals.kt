package com.tmfrl.hotdeal.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 수집기(monitor/publish.py)가 Pages에 올리는 deals.json. 필드를 바꾸면 양쪽을 함께 고친다. */
@Serializable
data class DealsData(
    val version: Int = 1,
    @SerialName("generated_at") val generatedAt: String = "",
    val sources: List<SourceStatus> = emptyList(),
    val items: List<DealItem> = emptyList(),
    /** 수집기가 실제로 쓰는 기준 값(settings.yaml). 앱의 "수집 기준" 화면이 보여준다. */
    val rules: Rules = Rules(),
)

@Serializable
data class Rules(
    @SerialName("instant_keywords") val instantKeywords: List<String> = emptyList(),
    @SerialName("exclude_keywords") val excludeKeywords: List<String> = emptyList(),
    @SerialName("giftcard_keywords") val giftcardKeywords: List<String> = emptyList(),
    @SerialName("giftcard_min_discount") val giftcardMinDiscount: Double = 0.0,
    @SerialName("discontinue_keywords") val discontinueKeywords: List<String> = emptyList(),
    @SerialName("card_keywords") val cardKeywords: List<String> = emptyList(),
    val reaction: Map<String, ReactionRule> = emptyMap(),
    @SerialName("late_multiplier") val lateMultiplier: Int = 2,
    @SerialName("watch_hours") val watchHours: Int = 6,
    @SerialName("keep_hours") val keepHours: Int = 24,
    @SerialName("keep_picked_hours") val keepPickedHours: Int = 48,
    val sources: List<RuleSource> = emptyList(),
)

@Serializable
data class ReactionRule(
    @SerialName("window_min") val windowMin: Int = 0,
    val recommends: Int? = null,
    val comments: Int? = null,
)

@Serializable
data class RuleSource(val label: String = "", val categories: List<String> = emptyList())

@Serializable
data class SourceStatus(
    val name: String = "",
    val label: String = "",
    val ok: Boolean = true,
    val error: String? = null,
    val count: Int = 0,
)

@Serializable
data class DealItem(
    val id: String,
    val source: String = "",
    @SerialName("source_label") val sourceLabel: String = "",
    val category: String = "",
    val title: String,
    val url: String,
    @SerialName("posted_at") val postedAt: String,
    val recommends: Int? = null,
    val comments: Int? = null,
    val ended: Boolean = false,
    /** "instant"(바로 볼 글) | "queue"(반응 기준 통과) | null(수집기가 고르지 않음) */
    val pick: String? = null,
    val fire: Boolean = false,
    /** 비어 있지 않으면 category 대신 보일 라벨. 예: "신용카드·단종" */
    val label: String = "",
    /** 수집기가 처음 고른 시각. 반응이 늦게 붙은 글도 이 시각 기준으로 맨 위에 온다. */
    @SerialName("picked_at") val pickedAt: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/** 잘못된 JSON이면 IllegalArgumentException(SerializationException 포함). */
fun parseDeals(text: String): DealsData = json.decodeFromString(DealsData.serializer(), text)

fun encodeDeals(data: DealsData): String = json.encodeToString(DealsData.serializer(), data)
