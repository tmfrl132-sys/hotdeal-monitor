package com.tmfrl.hotdeal.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmfrl.hotdeal.data.SavedStore
import com.tmfrl.hotdeal.model.SavedLink
import com.tmfrl.hotdeal.model.addSaved
import com.tmfrl.hotdeal.model.commentPageUrl
import com.tmfrl.hotdeal.model.isSavableUrl
import com.tmfrl.hotdeal.model.markSeen
import com.tmfrl.hotdeal.model.newComments
import com.tmfrl.hotdeal.widget.redrawAllWidgets
import com.tmfrl.hotdeal.work.RefreshWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** 갱신 작업이 댓글 수를 고쳤는지 다시 읽는 간격. 화면이 보일 때만 읽는다. */
private const val POLL_MS = 3_000L

/** 댓글을 지켜볼 글. 위젯의 ☆로 저장하거나 링크를 붙여 넣어 저장한다. */
@Composable
fun SavedTab() {
    val context = LocalContext.current
    val store = remember { SavedStore.forContext(context) }
    val scope = rememberCoroutineScope()
    var links by remember { mutableStateOf(store.read()) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            delay(POLL_MS)
            val latest = store.read()
            if (latest != links) links = latest
        }
    }

    fun change(transform: (List<SavedLink>) -> List<SavedLink>) {
        scope.launch {
            links = store.update(transform)
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
                "위젯의 ☆를 누르거나 링크를 붙여 넣으면 저장됩니다. 뽐뿌·클리앙 글은 15분마다 댓글 수를 확인해 " +
                    "새 댓글이 달리면 위젯에 💬+N으로 알려줍니다.",
                color = SUBTLE, fontSize = 13.sp,
            )
        }
        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("글 링크 붙여 넣기") },
                singleLine = true,
                isError = input.isNotBlank() && !isSavableUrl(input),
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = isSavableUrl(input),
                    onClick = {
                        val url = input.trim()
                        input = ""
                        change { addSaved(it, SavedLink(url, url, savedAt = Instant.now().toString())) }
                        RefreshWorker.runOnce(context) // 제목과 댓글 수를 바로 읽어 온다
                    },
                ) { Text("저장") }
                OutlinedButton(
                    enabled = links.isNotEmpty(),
                    onClick = { RefreshWorker.runOnce(context) },
                ) { Text("지금 댓글 확인") }
            }
        }
        if (links.isEmpty()) {
            item { Text("저장한 글이 없습니다.", color = SUBTLE, fontSize = 13.sp) }
        }
        items(links, key = { it.url }) { link ->
            SavedRow(
                link,
                onOpen = {
                    openLink(context, link.url)
                    change { markSeen(it, link.url) }
                },
                onDelete = { change { list -> list.filterNot { it.url == link.url } } },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // 링크를 열 앱이 없는 기기. 저장 목록은 그대로 둔다.
    }
}

private fun commentText(link: SavedLink): String = when {
    commentPageUrl(link.url) == null -> "이 사이트는 댓글 수를 확인할 수 없습니다"
    link.comments == null -> "댓글 수 확인 중"
    link.newComments > 0 -> "💬 ${link.comments} · 새 댓글 ${link.newComments}개"
    else -> "💬 ${link.comments} · 새 댓글 없음"
}

@Composable
private fun SavedRow(link: SavedLink, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)
            .background(TILE, RoundedCornerShape(10.dp))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 10.dp)) {
            Text(link.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(
                listOf(link.sourceLabel, commentText(link)).filter { it.isNotEmpty() }.joinToString(" · "),
                color = if (link.newComments > 0) WARN else SUBTLE,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onDelete) { Text("삭제") }
    }
}
