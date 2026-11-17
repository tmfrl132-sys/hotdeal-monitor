package com.tmfrl.hotdeal.work

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tmfrl.hotdeal.data.Repository
import com.tmfrl.hotdeal.widget.redrawAllWidgets
import java.util.concurrent.TimeUnit

/** 15분마다(안드로이드가 허용하는 최소 주기) deals.json을 받아 위젯을 다시 그린다. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Repository.forContext(applicationContext).refresh() // 실패하면 캐시가 그대로 남는다
        redrawAllWidgets(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "deals-refresh"
        private const val NOW = "deals-refresh-now"

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runOnce(context: Context) {
            val builder = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(network)
            // 거의 열지 않는 앱은 안드로이드가 작업을 크게 늦춰서 바로 돌게 요청한다(안드로이드 12 이상)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, builder.build())
        }
    }
}
