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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmfrl.hotdeal.data.CollectStatusStore
import com.tmfrl.hotdeal.data.HiddenStore
import com.tmfrl.hotdeal.data.KeywordStore
import com.tmfrl.hotdeal.data.TokenStore
import com.tmfrl.hotdeal.model.DealsData
import com.tmfrl.hotdeal.model.FILTERS
import com.tmfrl.hotdeal.model.Keyword
import com.tmfrl.hotdeal.model.UserKeywords
import com.tmfrl.hotdeal.model.collectMessage
import com.tmfrl.hotdeal.model.formatUpdated
import com.tmfrl.hotdeal.widget.redrawAllWidgets
import com.tmfrl.hotdeal.work.CollectWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

// 위젯과 같은 어두운 색
internal val BACKGROUND = Color(0xFF111827)
internal val TILE = Color(0xFF1B2433)
internal val TEXT = Color(0xFFF3F4F6)
internal val SUBTLE = Color(0xFF8B95A7)
internal val ON = Color(0xFF4D8DFF)
internal val WARN = Color(0xFFF59E0B)

internal val COLORS = darkColorScheme(
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
fun SettingsTab(data: DealsData?, onAddWidget: () -> Unit) {
    val context = LocalContext.current
    val store = remember { KeywordStore.forContext(context) }
    val scope = rememberCoroutineScope()
    var keywords by remember { mutableStateOf(store.read()) }
    var word by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val hiddenStore = remember { HiddenStore.forContext(context) }
    var hiddenCount by remember { mutableStateOf(hiddenStore.read().size) }

    fun change(transform: (UserKeywords) -> UserKeywords) {
        scope.launch {
            keywords = store.update(transform)
            redrawAllWidgets(context)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
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

        item { Section("디지털 폐지줍기") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("폐지줍기 글도 위젯에 보기", fontSize = 15.sp)
                    Text("퀴즈 정답, 몇십 원 적립, 룰렛·뽑기 글입니다. 끄면 숨깁니다.", color = SUBTLE, fontSize = 12.sp)
                }
                Switch(
                    checked = keywords.showNoise,
                    onCheckedChange = { on -> change { it.copy(showNoise = on) } },
                )
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

        item { Section("지금 수집") }
        item { CollectSection() }

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

@Composable
internal fun Section(title: String) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
internal fun SubTitle(title: String) {
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

/** 화면이 보이는 동안 수집 요청 결과를 다시 읽는 간격 */
private const val STATUS_POLL_MS = 2_000L

/**
 * 15분을 기다리지 않고 GitHub에 바로 수집하라고 요청한다.
 * GitHub 토큰(hotdeal-monitor 저장소, Actions: Read and write)이 필요하고, 토큰은 이 기기에만 저장한다.
 */
@Composable
private fun CollectSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { TokenStore.forContext(context) }
    val statusStore = remember { CollectStatusStore.forContext(context) }
    var hasToken by remember { mutableStateOf(tokenStore.read().isNotEmpty()) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(statusStore.read()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(STATUS_POLL_MS)
            status = statusStore.read()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "15분 주기를 기다리지 않고 바로 수집합니다. 위젯의 \"수집\" 버튼과 같습니다. 반영까지 1~2분 걸립니다.",
            color = SUBTLE, fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = hasToken, onClick = { CollectWorker.runOnce(context) }) { Text("지금 수집") }
            Text(if (hasToken) "토큰 저장됨" else "토큰 없음", color = if (hasToken) SUBTLE else WARN, fontSize = 13.sp)
        }
        status?.let {
            Text(
                "마지막 요청 ${formatUpdated(it.requestedAt, ZoneId.systemDefault())} · ${collectMessage(it.code)}",
                color = if (it.code == 204 || it.code == 0) SUBTLE else WARN, fontSize = 13.sp,
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(if (hasToken) "새 GitHub 토큰으로 바꾸기" else "GitHub 토큰 (github_pat_...)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = input.isNotBlank(),
                onClick = {
                    val t = input
                    scope.launch {
                        tokenStore.write(t)
                        input = ""
                        hasToken = true
                        redrawAllWidgets(context)
                    }
                },
            ) { Text("토큰 저장") }
            TextButton(
                enabled = hasToken,
                onClick = {
                    scope.launch {
                        tokenStore.write("")
                        hasToken = false
                        redrawAllWidgets(context)
                    }
                },
            ) { Text("토큰 지우기") }
        }
        Text(
            "토큰 만드는 곳: GitHub 설정 → Developer settings → Fine-grained tokens. " +
                "저장소는 hotdeal-monitor 하나만, 권한은 Actions: Read and write만 주세요.",
            color = SUBTLE, fontSize = 12.sp,
        )
    }
}
