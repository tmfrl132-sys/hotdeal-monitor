package com.tmfrl.hotdeal.work

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tmfrl.hotdeal.data.CollectStatusStore
import com.tmfrl.hotdeal.data.TokenStore
import com.tmfrl.hotdeal.data.dispatchCollect
import com.tmfrl.hotdeal.model.COLLECT_NETWORK
import com.tmfrl.hotdeal.model.COLLECT_NO_TOKEN
import com.tmfrl.hotdeal.model.COLLECT_OK
import com.tmfrl.hotdeal.model.COLLECT_PENDING
import com.tmfrl.hotdeal.model.CollectStatus
import com.tmfrl.hotdeal.widget.redrawAllWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/**
 * 수집 버튼: GitHub에 collect.yml 실행을 요청한다.
 * 수집과 배포는 GitHub에서 1분쯤 걸려서, 성공하면 1분 30초·3분 뒤에 새 deals.json을 받게 예약한다.
 */
class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = CollectStatusStore.forContext(context)
        val requestedAt = Instant.now().toString()
        val token = TokenStore.forContext(context).read()
        val code = if (token.isEmpty()) {
            COLLECT_NO_TOKEN
        } else {
            store.write(CollectStatus(requestedAt, COLLECT_PENDING))
            redrawAllWidgets(context)
            withContext(Dispatchers.IO) {
                try {
                    dispatchCollect(token)
                } catch (e: IOException) {
                    COLLECT_NETWORK
                }
            }
        }
        store.write(CollectStatus(requestedAt, code))
        redrawAllWidgets(context)
        if (code == COLLECT_OK) {
            RefreshWorker.runLater(context, AFTER_SECONDS)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "collect-now"
        private val AFTER_SECONDS = listOf(90L, 180L)

        fun runOnce(context: Context) {
            val builder = OneTimeWorkRequestBuilder<CollectWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            // 여러 번 눌러도 요청은 하나만 보낸다
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, builder.build())
        }
    }
}
