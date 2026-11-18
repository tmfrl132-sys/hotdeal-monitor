package com.tmfrl.hotdeal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmfrl.hotdeal.data.KeywordStore
import com.tmfrl.hotdeal.model.DealsData
import com.tmfrl.hotdeal.model.Keyword
import com.tmfrl.hotdeal.model.ReactionRule
import com.tmfrl.hotdeal.model.Rules

/** 반응 기준 표시 순서 */
private val REACTION_ORDER = listOf("핫딜", "상품권", "알뜰폰", "재테크")

/**
 * 수집 기준 설명. 값은 수집기가 deals.json에 함께 올린 실제 기준(rules)이고, 내 키워드는 이 기기의 값이다.
 * 기준 숫자를 바꾸려면 저장소의 settings.yaml을 고친다.
 */
@Composable
fun RulesTab(data: DealsData?) {
    val context = LocalContext.current
    val mine = remember { KeywordStore.forContext(context).read() }
    val rules = data?.rules
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "15분마다 아래 커뮤니티의 새 글을 읽어, 기준을 넘은 글만 위젯에 올립니다. " +
                    "1단계에 걸리면 반응을 기다리지 않고 바로, 아니면 2단계(추천·댓글) 기준을 넘어야 올라갑니다.",
                color = SUBTLE, fontSize = 13.sp,
            )
        }
        if (rules == null || rules.reaction.isEmpty()) {
            item {
                Text(
                    "수집기에서 기준을 아직 받지 못했습니다. 잠시 뒤 다시 열어 주세요.",
                    color = WARN, fontSize = 13.sp,
                )
            }
            return@LazyColumn
        }

        item { Section("1단계 · 바로 올라가는 글") }
        item {
            Card {
                Rule("즉시 키워드", "제목에 있으면 반응과 상관없이 바로 올라갑니다. \"쌀먹\"은 🔥로 강조합니다.")
                Words("기본", rules.instantKeywords)
                Words("내 키워드", mine.instant.map(::scoped))
            }
        }
        item {
            Card {
                Rule(
                    "상품권 할인",
                    "제목에 상품권 단어가 있고 할인율이 ${fmt(rules.giftcardMinDiscount)}% 이상이면 바로 올라갑니다.",
                )
                Words("상품권 단어", rules.giftcardKeywords)
            }
        }
        item {
            Card {
                Rule("카드 단종 🔥", "단종 단어와 카드 단어가 함께 있으면 바로 올라갑니다.")
                Words("단종 단어", rules.discontinueKeywords)
                Words("카드 단어", rules.cardKeywords)
            }
        }
        item {
            Card {
                Rule(
                    "정가거부 유튜브",
                    "단종 영상은 🔥로, 카드 영상은 바로 올라갑니다. 다른 영상(할인 소식)은 핫딜로 올라갑니다.",
                )
            }
        }

        item { Section("2단계 · 반응 기준을 넘은 글") }
        item {
            Card {
                Text(
                    "1단계에 걸리지 않은 글은 올라온 뒤 정해진 시간 안에 추천 또는 댓글이 기준 이상이어야 합니다. " +
                        "그 시간이 지나면 ${rules.watchHours}시간까지는 기준의 ${rules.lateMultiplier}배가 필요하고, " +
                        "${rules.watchHours}시간이 지나면 더 보지 않습니다.",
                    color = SUBTLE, fontSize = 13.sp,
                )
                val ordered = REACTION_ORDER.filter { it in rules.reaction } + rules.reaction.keys.filterNot { it in REACTION_ORDER }
                ordered.forEach { category ->
                    Spacer(Modifier.height(6.dp))
                    Text(reactionLine(category, rules.reaction.getValue(category), rules), fontSize = 14.sp)
                }
            }
        }

        item { Section("제외") }
        item {
            Card {
                Rule("숨기는 글", "사이트에 종료·품절 표시가 있거나, 제목에 아래 단어가 있으면 1·2단계와 상관없이 숨깁니다.")
                Words("기본", rules.excludeKeywords)
                Words("내 키워드", mine.exclude.map(::scoped))
            }
        }

        item {
            Card {
                Rule(
                    "디지털 폐지줍기",
                    "제목에 아래 단어가 있거나 ${rules.noiseMaxWon}원 이하의 금액이 있으면 숨깁니다. " +
                        "내 즉시 키워드에 맞으면 보여주고, 설정 탭에서 모두 켤 수 있습니다.",
                )
                Words("단어", rules.noiseKeywords)
            }
        }

        item { Section("보관과 갱신") }
        item {
            Card {
                Text(
                    "· 올라간 글은 ${rules.keepPickedHours}시간 동안 위젯에 남습니다. 늦게 기준을 넘은 글도 그때 맨 위에 옵니다.\n" +
                        "· 수집은 15분마다(실제로는 15~30분) 하고, 위젯도 15분마다 받습니다. ⟳를 누르면 바로 받습니다.\n" +
                        "· ✕로 숨긴 글은 위젯의 ↶ 되돌리기로 살립니다.",
                    color = SUBTLE, fontSize = 13.sp,
                )
            }
        }

        item { Section("수집하는 곳") }
        item {
            Card {
                rules.sources.forEach { s ->
                    Text("· ${s.label}  (${s.categories.joinToString("·")})", fontSize = 14.sp)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

private fun scoped(k: Keyword) = if (k.category == null) k.word else "${k.word}(${k.category})"

internal fun reactionLine(category: String, r: ReactionRule, rules: Rules): String {
    val needs = listOfNotNull(r.recommends?.let { "추천 $it" }, r.comments?.let { "댓글 $it" })
    val late = listOfNotNull(
        r.recommends?.let { "추천 ${it * rules.lateMultiplier}" },
        r.comments?.let { "댓글 ${it * rules.lateMultiplier}" },
    )
    return "$category: ${r.windowMin}분 안에 ${needs.joinToString(" 또는 ")} 이상 " +
        "(그 뒤엔 ${late.joinToString(" 또는 ")})"
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)
            .background(TILE, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) { content() }
}

@Composable
private fun Rule(title: String, text: String) {
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Text(text, color = SUBTLE, fontSize = 13.sp)
}

@Composable
private fun Words(label: String, words: List<String>) {
    Spacer(Modifier.height(4.dp))
    Text("$label: ${words.joinToString(", ").ifEmpty { "없음" }}", fontSize = 13.sp)
}
