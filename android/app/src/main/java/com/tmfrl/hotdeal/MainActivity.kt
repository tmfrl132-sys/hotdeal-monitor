package com.tmfrl.hotdeal

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tmfrl.hotdeal.ui.SettingsScreen
import com.tmfrl.hotdeal.widget.DealWidgetReceiver
import com.tmfrl.hotdeal.work.RefreshWorker

/** 설정 화면: 내 키워드 관리, 수집 상태, 홈 화면에 위젯 추가. 런처 아이콘과 위젯 "설정" 버튼이 연다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        RefreshWorker.schedule(this)
        RefreshWorker.runOnce(this)
        setContent { SettingsScreen(onAddWidget = ::requestPinWidget) }
    }

    private fun requestPinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, DealWidgetReceiver::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(provider, null, null)
        } else {
            Toast.makeText(this, R.string.pin_not_supported, Toast.LENGTH_LONG).show()
        }
    }
}
