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
)

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
)

private val json = Json { ignoreUnknownKeys = true }

/** 잘못된 JSON이면 IllegalArgumentException(SerializationException 포함). */
fun parseDeals(text: String): DealsData = json.decodeFromString(DealsData.serializer(), text)

fun encodeDeals(data: DealsData): String = json.encodeToString(DealsData.serializer(), data)
