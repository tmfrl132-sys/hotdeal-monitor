package com.tmfrl.hotdeal.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tmfrl.hotdeal.MainActivity
import com.tmfrl.hotdeal.data.HiddenStore
import com.tmfrl.hotdeal.data.KeywordStore
import com.tmfrl.hotdeal.data.Repository
import com.tmfrl.hotdeal.model.DealsData
import com.tmfrl.hotdeal.model.VisibleDeal
import com.tmfrl.hotdeal.model.formatUpdated
import com.tmfrl.hotdeal.model.inCategory
import com.tmfrl.hotdeal.model.metaLine
import com.tmfrl.hotdeal.model.nextCategory
import com.tmfrl.hotdeal.model.visibleDeals
import com.tmfrl.hotdeal.work.RefreshWorker
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

// 경제지표 위젯과 같은 어두운 카드 디자인
private val BACKGROUND = Color(0xFF111827)
private val ROW = Color(0xFF1B2433)
private val ROW_FIRE = Color(0xFF3A2A12)
private val FIRE = Color(0xFFF59E0B)
private val TEXT = Color(0xFFF3F4F6)
private val SUBTLE = Color(0xFF8B95A7)
private val CHIP = Color(0xFF2B3A55)

/** 카테고리 라벨 색. 단종 소식(신용카드·단종)도 신용카드 색을 쓴다. */
private fun tagColor(tag: String): Color = when {
    tag.startsWith("상품권") -> Color(0xFF34D399)
    tag.startsWith("알뜰폰") -> Color(0xFF60A5FA)
    tag.startsWith("신용카드") -> Color(0xFFF472B6)
    tag.startsWith("재테크") -> Color(0xFFFBBF24)
    else -> Color(0xFFA5B4FC)
}

/** 수집이 이보다 오래되면 갱신 시각을 주황으로 보여준다. */
private val STALE = Duration.ofMinutes(90)

/** 새 데이터를 받을 때마다 올리는 값. 상태가 바뀌어야 Glance가 다시 그린다. */
private val DATA_VERSION = longPreferencesKey("data_version")

/** 위젯마다 기억하는 카테고리 필터. 없으면 전체. */
private val CATEGORY = stringPreferencesKey("category")

private fun style(color: Color, size: Int, bold: Boolean = false) = TextStyle(
    color = ColorProvider(color),
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
)

class DealWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // 상태를 읽어 두면 DATA_VERSION·CATEGORY가 바뀔 때마다 캐시와 키워드를 새로 읽어 다시 그린다
            val state = currentState<Preferences>()
            state[DATA_VERSION]
            val category = state[CATEGORY]
            val data = Repository.forContext(context).cached()
            val hidden = HiddenStore.forContext(context).read()
            val deals = data?.let {
                inCategory(visibleDeals(it, KeywordStore.forContext(context).read(), hidden), category)
            }
            WidgetContent(data, deals, category, hidden.isNotEmpty(), Instant.now())
        }
    }
}

@Composable
private fun WidgetContent(
    data: DealsData?, deals: List<VisibleDeal>?, category: String?, canUndo: Boolean, now: Instant,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(BACKGROUND)
            .cornerRadius(20.dp)
            .padding(10.dp),
    ) {
        Header(data, category, canUndo, now)
        Spacer(GlanceModifier.height(6.dp))
        when {
            data == null || deals == null -> Message("핫딜을 불러오는 중입니다. 눌러서 새로고침")
            deals.isEmpty() -> Message(
                if (category == null) "조건에 맞는 글이 아직 없습니다" else "$category 글이 아직 없습니다",
            )
            else -> LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(deals, itemId = { it.item.id.hashCode().toLong() }) { deal ->
                    Column {
                        DealRow(deal, now)
                        Spacer(GlanceModifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = style(SUBTLE, 12),
        modifier = GlanceModifier.padding(top = 8.dp).clickable(actionRunCallback<RefreshAction>()),
    )
}

/** 이보다 좁으면 머리줄에서 제목·갱신 시각·설정을 빼고 필터와 새로고침만 남긴다(2칸 폭). */
private val NARROW = 220.dp

@Composable
private fun Header(data: DealsData?, category: String?, canUndo: Boolean, now: Instant) {
    val narrow = LocalSize.current.width < NARROW
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!narrow) {
            Text("핫딜", style = style(TEXT, 14, bold = true))
            Spacer(GlanceModifier.width(8.dp))
        }
        // 누를 때마다 전체 → 상품권 → 알뜰폰 → 신용카드 → 재테크 → 핫딜 → 전체
        Box(
            modifier = GlanceModifier.background(CHIP)
                .cornerRadius(6.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clickable(actionRunCallback<NextCategoryAction>()),
        ) {
            Text("${category ?: "전체"} ▾", style = style(TEXT, 11, bold = true))
        }
        Spacer(GlanceModifier.defaultWeight())
        if (data != null && !narrow) {
            val stale = try {
                Duration.between(Instant.parse(data.generatedAt), now) > STALE
            } catch (e: DateTimeParseException) {
                true
            }
            Text(formatUpdated(data.generatedAt, ZoneId.systemDefault()), style = style(if (stale) FIRE else SUBTLE, 10))
        }
        // ✕로 숨긴 글이 있을 때만 보인다. 누를 때마다 가장 최근에 숨긴 글부터 하나씩 되살린다.
        if (canUndo) {
            Spacer(GlanceModifier.width(6.dp))
            Box(
                modifier = GlanceModifier.background(ROW)
                    .cornerRadius(6.dp)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .clickable(actionRunCallback<UndoHideAction>()),
            ) {
                Text(if (narrow) "↶" else "↶ 되돌리기", style = style(TEXT, 11))
            }
        }
        if (!narrow) {
            Spacer(GlanceModifier.width(6.dp))
            Box(
                modifier = GlanceModifier.background(ROW)
                    .cornerRadius(6.dp)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                Text("설정", style = style(SUBTLE, 11))
            }
        }
        Box(
            modifier = GlanceModifier.padding(start = 8.dp, end = 2.dp)
                .clickable(actionRunCallback<RefreshAction>()),
        ) {
            Text("⟳", style = style(TEXT, 15, bold = true))
        }
    }
}

/**
 * 한 줄을 누르면 원문(브라우저·유튜브 앱)을 열고, 오른쪽 ✕를 누르면 그 글을 숨긴다.
 * 홈 화면 위젯은 길게 누르기를 홈 화면 앱이 가져가서(이동·크기 조절) ✕ 버튼으로 숨긴다.
 */
@Composable
private fun DealRow(deal: VisibleDeal, now: Instant) {
    val open = Intent(Intent.ACTION_VIEW, Uri.parse(deal.item.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    Row(
        modifier = GlanceModifier.fillMaxWidth()
            .background(if (deal.fire) ROW_FIRE else ROW)
            .cornerRadius(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = GlanceModifier.defaultWeight()
                .padding(start = 10.dp, top = 6.dp, bottom = 6.dp)
                .clickable(actionStartActivity(open)),
        ) {
            DealText(deal, now)
        }
        Box(
            modifier = GlanceModifier.padding(horizontal = 10.dp, vertical = 10.dp)
                .clickable(actionRunCallback<HideAction>(actionParametersOf(DEAL_ID to deal.item.id))),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", style = style(SUBTLE, 13))
        }
    }
}

@Composable
private fun DealText(deal: VisibleDeal, now: Instant) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (deal.fire) Text("🔥 ", style = style(FIRE, 12, bold = true))
            Text(deal.tag, style = style(tagColor(deal.tag), 11, bold = true))
            Spacer(GlanceModifier.width(6.dp))
            // 남은 폭을 채워야 긴 제목이 위젯 밖으로 나가지 않고 줄바꿈된다
            Text(
                deal.item.title,
                modifier = GlanceModifier.defaultWeight(),
                style = style(TEXT, 12, bold = deal.fire),
                maxLines = 2,
            )
        }
        Text(
            metaLine(deal.item, now) + if (deal.byKeyword) " · 내 키워드" else "",
            style = style(SUBTLE, 10),
            maxLines = 1,
        )
    }
}

private val DEAL_ID = ActionParameters.Key<String>("deal_id")

/** ✕ 버튼: 그 글을 숨기고 모든 위젯을 다시 그린다. 숨긴 글은 앱 설정에서 되돌린다. */
class HideAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[DEAL_ID] ?: return
        val current = Repository.forContext(context).cached()?.items?.map { it.id }?.toSet().orEmpty()
        HiddenStore.forContext(context).hide(id, current)
        redrawAllWidgets(context)
    }
}

/** 되돌리기 버튼: 가장 최근에 숨긴 글을 다시 보이게 한다. */
class UndoHideAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HiddenStore.forContext(context).undo()
        redrawAllWidgets(context)
    }
}

/** 필터 버튼: 다음 카테고리로 바꾸고 다시 그린다. */
class NextCategoryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            val next = nextCategory(prefs[CATEGORY])
            if (next == null) prefs.remove(CATEGORY) else prefs[CATEGORY] = next
        }
        DealWidget().update(context, glanceId)
    }
}

/**
 * 새로고침 버튼: 바로 새로 받는다.
 * 받는 일은 WorkManager에 맡긴다. 이 콜백은 수신기 시간 제한 안에서 돌아서 네트워크를 기다리면 끊길 수 있다.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.runOnce(context)
        RefreshWorker.schedule(context)
    }
}

/** 모든 위젯의 상태 버전을 올리고 다시 그린다. 새 데이터를 받았거나 키워드를 바꿨을 때 부른다. */
suspend fun redrawAllWidgets(context: Context) {
    val ids = GlanceAppWidgetManager(context).getGlanceIds(DealWidget::class.java)
    ids.forEach { id ->
        updateAppWidgetState(context, id) { it[DATA_VERSION] = System.currentTimeMillis() }
    }
    DealWidget().updateAll(context)
}
