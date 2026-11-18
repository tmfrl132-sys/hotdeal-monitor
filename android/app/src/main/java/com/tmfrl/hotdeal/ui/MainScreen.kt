package com.tmfrl.hotdeal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmfrl.hotdeal.data.Repository
import com.tmfrl.hotdeal.data.SavedStore
import com.tmfrl.hotdeal.model.newComments
import kotlinx.coroutines.delay

/** 화면이 보이는 동안 캐시를 다시 읽는 간격. 앱을 열 때 받은 새 결과가 바로 반영된다. */
private const val POLL_MS = 3_000L

private val TABS = listOf("설정", "저장한 글", "수집 기준")
private const val SAVED_TAB = 1

/** 앱 화면: 위 탭으로 설정(키워드·위젯·수집 상태), 저장한 글, 수집 기준 설명을 오간다. */
@Composable
fun MainScreen(onAddWidget: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository.forContext(context) }
    var data by remember { mutableStateOf(repo.cached()) }
    // 저장한 글에 새 댓글이 있으면 그 탭부터 보여준다(위젯의 💬+N 버튼으로 들어온 경우)
    var tab by remember {
        mutableIntStateOf(if (SavedStore.forContext(context).read().any { it.newComments > 0 }) SAVED_TAB else 0)
    }

    // 앱을 열면 MainActivity가 새로 받기를 요청한다. 끝나면 캐시가 바뀌므로 몇 초마다 다시 읽는다.
    LaunchedEffect(Unit) {
        while (true) {
            delay(POLL_MS)
            val latest = repo.cached()
            if (latest != data) data = latest
        }
    }

    MaterialTheme(colorScheme = COLORS) {
        Surface(modifier = Modifier.fillMaxSize(), color = BACKGROUND) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Text(
                    "핫딜 모니터",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                )
                TabRow(selectedTabIndex = tab, containerColor = BACKGROUND) {
                    TABS.forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 -> SettingsTab(data, onAddWidget)
                    SAVED_TAB -> SavedTab()
                    else -> RulesTab(data)
                }
            }
        }
    }
}
