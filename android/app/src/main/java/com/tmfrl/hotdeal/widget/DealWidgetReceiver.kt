package com.tmfrl.hotdeal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.tmfrl.hotdeal.work.RefreshWorker

class DealWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = DealWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.schedule(context)
        RefreshWorker.runOnce(context)
    }

    // 앱 업데이트 뒤에도 불린다. 바로 다시 받고 주기 작업을 되살린다.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        RefreshWorker.schedule(context)
        RefreshWorker.runOnce(context)
    }
}
