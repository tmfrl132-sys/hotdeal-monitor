package com.tmfrl.hotdeal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmfrl.hotdeal.data.HiddenStore
import com.tmfrl.hotdeal.data.KeywordStore
import com.tmfrl.hotdeal.data.Repository
import com.tmfrl.hotdeal.model.FILTERS
import com.tmfrl.hotdeal.model.Keyword
import com.tmfrl.hotdeal.model.UserKeywords
import com.tmfrl.hotdeal.model.formatUpdated
import com.tmfrl.hotdeal.widget.redrawAllWidgets
import kotlinx.coroutines.launch
import java.time.ZoneId

// 위젯과 같은 어두운 색
private val BACKGROUND = Color(0xFF111827)
private val TILE = Color(0xFF1B2433)
private val TEXT = Color(0xFFF3F4F6)
private val SUBTLE = Color(0xFF8B95A7)
private val ON = Color(0xFF4D8DFF)
private val WARN = Color(0xFFF59E0B)

private val COLORS = darkColorScheme(
    primary = ON,
    onPrimary = TEXT,
    background = BACKGROUND,
    onBackground = TEXT,
    surface = BACKGROUND,
    onSurface = TEXT,
    surfaceVariant = TILE,
    onSurfaceVariant = SUBTLE,
    surfaceContainerHigh = TILE,
    outline = SUBTLE,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onAddWidget: () -> Unit) {
    val context = LocalContext.current
    val store = remember { KeywordStore.forContext(context) }
    val scope = rememberCoroutineScope()
    var keywords by remember { mutableStateOf(store.read()) }
    var word by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val data = remember { Repository.forContext(context).cached() }
    val hiddenStore = remember { HiddenStore.forContext(context) }
    var hiddenCount by remember { mutableStateOf(hiddenStore.read().size) }

    fun change(transform: (UserKeywords) -> UserKeywords) {
        scope.launch {
            keywords = store.update(transform)
            redrawAllWidgets(context)
        }
    }

    MaterialTheme(colorScheme = COLORS) {
        Surface(modifier = Modifier.fillMaxSize(), color = BACKGROUND) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Text("핫딜 모니터", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "커뮤니티 핫딜·상품권·알뜰폰·카드 소식을 15분마다 받아 위젯에 보여줍니다. 줄을 누르면 원문이 열립니다.",
                        color = SUBTLE, fontSize = 13.sp,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAddWidget) { Text("홈 화면에 위젯 추가") }
                        // 위젯에서 ✕로 숨긴 글을 모두 다시 보이게 한다
                        OutlinedButton(
                            enabled = hiddenCount > 0,
                            onClick = {
                                scope.launch {
                                    hiddenStore.clear()
                                    hiddenCount = 0
                                    redrawAllWidgets(context)
                                }
                            },
                        ) { Text("숨긴 글 ${hiddenCount}개 되돌리기") }
                    }
                }

                item { Section("내 키워드") }
                item {
                    Text(
                        "즉시 보기: 반응이 없어도 위젯에 보입니다. 제외: 이 단어가 있으면 숨깁니다.\n" +
                            "기본으로 \"쌀먹\"은 즉시 보기, 종료·품절·질문글은 제외되어 있습니다. 키워드는 이 기기에만 저장됩니다.",
                        color = SUBTLE, fontSize = 12.sp,
                    )
                }
                item {
                    OutlinedTextField(
                        value = word,
                        onValueChange = { word = it },
                        label = { Text("키워드 (예: 컬쳐랜드)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf<String?>(null) + FILTERS) { c ->
                            FilterChip(
                                selected = category == c,
                                onClick = { category = c },
                                label = { Text(c ?: "전체 카테고리") },
                            )
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = word.isNotBlank(),
                            onClick = { val w = word; change { it.withInstant(w, category) }; word = "" },
                        ) { Text("즉시 보기 추가") }
                        OutlinedButton(
                            enabled = word.isNotBlank(),
                            onClick = { val w = word; change { it.withExclude(w, category) }; word = "" },
                        ) { Text("제외 추가") }
                    }
                }
                item { SubTitle("즉시 보기 (${keywords.instant.size})") }
                if (keywords.instant.isEmpty()) item { Empty() }
                items(keywords.instant) { k -> KeywordRow(k) { change { it.without(k) } } }
                item { SubTitle("제외 (${keywords.exclude.size})") }
                if (keywords.exclude.isEmpty()) item { Empty() }
                items(keywords.exclude) { k -> KeywordRow(k) { change { it.without(k) } } }

                item { Section("수집 상태") }
                if (data == null) {
                    item { Text("아직 받은 데이터가 없습니다. 인터넷 연결을 확인하세요.", color = SUBTLE, fontSize = 13.sp) }
                } else {
                    item {
                        Text(
                            "마지막 수집 ${formatUpdated(data.generatedAt, ZoneId.systemDefault())} · 글 ${data.items.size}건",
                            color = SUBTLE, fontSize = 13.sp,
                        )
                    }
                    items(data.sources) { s ->
                        Text(
                            if (s.ok) "✅ ${s.label} (${s.count}건)" else "⚠ ${s.label}: ${s.error ?: "실패"}",
                            color = if (s.ok) TEXT else WARN, fontSize = 13.sp,
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SubTitle(title: String) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SUBTLE)
}

@Composable
private fun Empty() {
    Text("없음", color = SUBTLE, fontSize = 13.sp)
}

@Composable
private fun KeywordRow(k: Keyword, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp)
            .background(TILE, RoundedCornerShape(10.dp))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(k.word, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(k.category ?: "전체", color = SUBTLE, fontSize = 12.sp)
        TextButton(onClick = onDelete) { Text("삭제") }
    }
}
