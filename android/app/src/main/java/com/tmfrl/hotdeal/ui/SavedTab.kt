package com.tmfrl.hotdeal.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.core.content.FileProvider
import com.tmfrl.hotdeal.data.CategoryStore
import com.tmfrl.hotdeal.data.ExportProgress
import com.tmfrl.hotdeal.data.ExportResult
import com.tmfrl.hotdeal.data.SavedStore
import com.tmfrl.hotdeal.data.exportMarkdown
import com.tmfrl.hotdeal.data.pageFetcher
import com.tmfrl.hotdeal.data.writeExportFile
import com.tmfrl.hotdeal.model.ETC_ID
import com.tmfrl.hotdeal.model.SavedCategory
import com.tmfrl.hotdeal.model.SavedLink
import com.tmfrl.hotdeal.model.addCategory
import com.tmfrl.hotdeal.model.addSaved
import com.tmfrl.hotdeal.model.autoCategory
import com.tmfrl.hotdeal.model.categoryName
import com.tmfrl.hotdeal.model.categoryOf
import com.tmfrl.hotdeal.model.commentPageUrl
import com.tmfrl.hotdeal.model.deleteCategory
import com.tmfrl.hotdeal.model.inSavedCategory
import com.tmfrl.hotdeal.model.isSavableUrl
import com.tmfrl.hotdeal.model.markSeen
import com.tmfrl.hotdeal.model.moveLinks
import com.tmfrl.hotdeal.model.newComments
import com.tmfrl.hotdeal.model.renameCategory
import com.tmfrl.hotdeal.widget.redrawAllWidgets
import com.tmfrl.hotdeal.work.RefreshWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime

/** 갱신 작업이 댓글 수를 고쳤는지 다시 읽는 간격. 화면이 보일 때만 읽는다. */
private const val POLL_MS = 3_000L

/**
 * 댓글을 지켜볼 글. 위젯의 ☆로 저장하거나 링크를 붙여 넣어 저장한다.
 * 위쪽 분류 칩으로 걸러 보고, 글을 골라 원문(본문·댓글 전체)을 마크다운 파일로 내보낸다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedTab() {
    val context = LocalContext.current
    val store = remember { SavedStore.forContext(context) }
    val categoryStore = remember { CategoryStore.forContext(context) }
    val scope = rememberCoroutineScope()
    var links by remember { mutableStateOf(store.read()) }
    var categories by remember { mutableStateOf(categoryStore.read()) }
    var input by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var moving by remember { mutableStateOf<Set<String>?>(null) }
    var managing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var finished by remember { mutableStateOf<Pair<ExportResult, File>?>(null) }

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

    fun export(targets: List<SavedLink>) {
        if (targets.isEmpty() || exportJob != null) return
        progress = ExportProgress(0, targets.size, targets.first().title)
        exportJob = scope.launch {
            try {
                val result = exportMarkdown(targets, categories, ZonedDateTime.now(), pageFetcher) { progress = it }
                val file = withContext(Dispatchers.IO) { writeExportFile(context, result) }
                finished = result to file
                selecting = false
                selected = emptySet()
            } catch (e: CancellationException) {
                Toast.makeText(context, "내보내기를 취소했습니다", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Toast.makeText(context, "파일을 만들지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress = null
                exportJob = null
            }
        }
    }

    fun moveTo(urls: Set<String>, categoryId: String) {
        change { moveLinks(it, urls, categoryId) }
        moving = null
    }

    // 지운 분류를 보고 있었다면 전체로 돌아간다
    val activeFilter = filter?.takeIf { id -> categories.any { it.id == id } }
    val visible = inSavedCategory(links, categories, activeFilter)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "위젯의 ☆를 누르거나 링크를 붙여 넣으면 저장됩니다. 뽐뿌·클리앙 글은 15분마다 댓글 수를 확인해 " +
                    "새 댓글이 달리면 위젯에 💬+N으로 알려줍니다. 글을 길게 누르거나 ⋮를 누르면 마크다운 파일로 내보낼 수 있습니다.",
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
                        // 링크만으로는 무슨 글인지 알 수 없어서 기타에 넣는다
                        change { addSaved(it, SavedLink(url, url, savedAt = Instant.now().toString(), category = autoCategory(url))) }
                        RefreshWorker.runOnce(context) // 제목과 댓글 수를 바로 읽어 온다
                    },
                ) { Text("저장") }
                OutlinedButton(
                    enabled = links.isNotEmpty(),
                    onClick = { RefreshWorker.runOnce(context) },
                ) { Text("지금 댓글 확인") }
            }
        }
        // 분류 칩: 전체 + 분류들. 오른쪽 끝에서 분류를 추가·이름 변경·삭제한다.
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = activeFilter == null,
                        onClick = { filter = null },
                        label = { Text("전체 ${links.size}") },
                    )
                }
                items(categories, key = { it.id }) { c ->
                    FilterChip(
                        selected = activeFilter == c.id,
                        onClick = { filter = c.id },
                        label = { Text("${c.name} ${inSavedCategory(links, categories, c.id).size}") },
                    )
                }
                item {
                    TextButton(onClick = { managing = true }) { Text("분류 관리") }
                }
            }
        }
        item {
            if (!selecting) {
                OutlinedButton(
                    enabled = visible.isNotEmpty(),
                    onClick = { selecting = true },
                ) { Text("골라서 마크다운으로 내보내기") }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${selected.size}개 선택", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        // 칩으로 거른 글만 모두 고른다
                        TextButton(onClick = { selected = selected + visible.map { it.url } }) { Text("전체 선택") }
                        TextButton(enabled = selected.isNotEmpty(), onClick = { selected = emptySet() }) { Text("선택 해제") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = selected.isNotEmpty(),
                            onClick = { export(links.filter { it.url in selected }) },
                        ) { Text("하나로 묶어 내보내기") }
                        OutlinedButton(
                            enabled = selected.isNotEmpty(),
                            onClick = { moving = selected },
                        ) { Text("분류 옮기기") }
                        TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("취소") }
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    if (links.isEmpty()) "저장한 글이 없습니다." else "이 분류에 저장한 글이 없습니다.",
                    color = SUBTLE, fontSize = 13.sp,
                )
            }
        }
        items(visible, key = { it.url }) { link ->
            SavedRow(
                link = link,
                category = categoryName(categoryOf(link, categories), categories),
                selecting = selecting,
                checked = link.url in selected,
                onToggle = { selected = if (link.url in selected) selected - link.url else selected + link.url },
                onOpen = {
                    openLink(context, link.url)
                    change { markSeen(it, link.url) }
                },
                onExport = { export(listOf(link)) },
                onMove = { moving = setOf(link.url) },
                onDelete = { change { list -> list.filterNot { it.url == link.url } } },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    moving?.let { urls ->
        MoveDialog(
            categories = categories,
            count = urls.size,
            onPick = { id -> moveTo(urls, id) },
            onCreate = { name ->
                scope.launch {
                    categories = categoryStore.update { addCategory(it, name, newCategoryId()) }
                    categories.firstOrNull { it.name == name.trim() }?.let { moveTo(urls, it.id) }
                }
            },
            onDismiss = { moving = null },
        )
    }

    if (managing) {
        ManageDialog(
            categories = categories,
            onAdd = { name -> scope.launch { categories = categoryStore.update { addCategory(it, name, newCategoryId()) } } },
            onRename = { id, name -> scope.launch { categories = categoryStore.update { renameCategory(it, id, name) } } },
            onDelete = { id ->
                scope.launch {
                    val before = categories
                    categories = categoryStore.update { deleteCategory(it, emptyList(), id).first }
                    links = store.update { deleteCategory(before, it, id).second }
                }
            },
            onDismiss = { managing = false },
        )
    }

    progress?.let { p ->
        ProgressDialog(p, onCancel = { exportJob?.cancel() })
    }

    finished?.let { (result, file) ->
        AlertDialog(
            onDismissRequest = { finished = null },
            title = { Text("마크다운 파일을 만들었습니다") },
            text = {
                Text(
                    "${result.fileName}\n글 ${result.count}개" +
                        (if (result.failed > 0) " · 본문을 읽지 못한 글 ${result.failed}개(파일 안에 이유를 적었습니다)" else "") +
                        " · ${(result.markdown.toByteArray().size + 1023) / 1024}KB",
                )
            },
            confirmButton = {
                Button(onClick = { shareFile(context, file) }) { Text("공유") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { copyText(context, result.markdown) }) { Text("클립보드 복사") }
                    TextButton(onClick = { finished = null }) { Text("닫기") }
                }
            },
        )
    }
}

private fun newCategoryId() = "c" + System.currentTimeMillis().toString(36)

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // 링크를 열 앱이 없는 기기. 저장 목록은 그대로 둔다.
    }
}

/** 안드로이드 공유 창으로 .md 파일을 보낸다. 받는 앱이 많도록 text/plain으로 보낸다. */
private fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, "마크다운 파일 공유"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "공유할 앱이 없습니다", Toast.LENGTH_SHORT).show()
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    try {
        clipboard.setPrimaryClip(ClipData.newPlainText("마크다운", text))
        Toast.makeText(context, "복사했습니다", Toast.LENGTH_SHORT).show()
    } catch (e: RuntimeException) { // 글이 너무 길면 안드로이드가 거절한다
        Toast.makeText(context, "너무 길어서 복사하지 못했습니다. 공유를 쓰세요", Toast.LENGTH_LONG).show()
    }
}

private fun commentText(link: SavedLink): String = when {
    commentPageUrl(link.url) == null -> "이 사이트는 댓글 수를 확인할 수 없습니다"
    link.comments == null -> "댓글 수 확인 중"
    link.newComments > 0 -> "💬 ${link.comments} · 새 댓글 ${link.newComments}개"
    else -> "💬 ${link.comments} · 새 댓글 없음"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedRow(
    link: SavedLink,
    category: String,
    selecting: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)
            .background(TILE, RoundedCornerShape(10.dp))
            .padding(start = if (selecting) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(
            modifier = Modifier.weight(1f)
                .combinedClickable(onClick = if (selecting) onToggle else onOpen, onLongClick = { menu = true })
                .padding(vertical = 10.dp),
        ) {
            Text(link.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(
                listOf(category, link.sourceLabel, commentText(link)).filter { it.isNotEmpty() }.joinToString(" · "),
                color = if (link.newComments > 0) WARN else SUBTLE,
                fontSize = 12.sp,
            )
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 18.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("마크다운으로 내보내기") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("분류 옮기기") }, onClick = { menu = false; onMove() })
                DropdownMenuItem(text = { Text("삭제") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ProgressDialog(p: ExportProgress, onCancel: () -> Unit) {
    val current = (p.done + 1).coerceAtMost(p.total)
    val pageShare = if (p.pages > 0) p.page.toFloat() / p.pages else 0f
    val fraction = if (p.total == 0) 0f else ((p.done + pageShare) / p.total).coerceIn(0f, 1f)
    AlertDialog(
        onDismissRequest = {}, // 취소 버튼으로만 닫는다
        title = { Text("원문 받는 중 $current/${p.total}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.title, maxLines = 2, fontSize = 13.sp)
                if (p.pages > 1) Text("댓글 ${p.page}/${p.pages}쪽", color = SUBTLE, fontSize = 12.sp)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("사이트에 부담을 주지 않게 천천히 받습니다.", color = SUBTLE, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("취소") } },
    )
}

@Composable
private fun MoveDialog(
    categories: List<SavedCategory>,
    count: Int,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("글 ${count}개를 옮길 분류") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                categories.forEach { c ->
                    TextButton(onClick = { onPick(c.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(c.name, modifier = Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("새 분류 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = name.isNotBlank(), onClick = { onCreate(name) }) { Text("새 분류 만들고 옮기기") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun ManageDialog(
    categories: List<SavedCategory>,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("분류 관리") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("이름을 고친 뒤 '저장'을 누르세요. 분류를 지우면 그 글은 '기타'로 옮겨집니다.", color = SUBTLE, fontSize = 12.sp)
                categories.forEach { c ->
                    var text by remember(c.id, c.name) { mutableStateOf(c.name) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = text.isNotBlank() && text.trim() != c.name,
                            onClick = { onRename(c.id, text) },
                        ) { Text("저장") }
                        TextButton(enabled = c.id != ETC_ID, onClick = { onDelete(c.id) }) { Text("삭제") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("새 분류") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newName.isNotBlank() && categories.none { it.name == newName.trim() },
                        onClick = { onAdd(newName); newName = "" },
                    ) { Text("추가") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
