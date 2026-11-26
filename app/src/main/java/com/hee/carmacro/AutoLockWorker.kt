package com.hee.carmacro

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** 문 열기 성공 30초 뒤: 알림을 '문 상태 모름'으로 바꾸고 상태판을 갱신 */
class AutoLockWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val nid = inputData.getInt("nid", 0)
        // 그 사이에 다른 문 명령이 왔으면 건드리지 않음
        if (prefs.door == "unlocked" && System.currentTimeMillis() - prefs.doorTime >= Prefs.AUTO_LOCK_MS - 1000) {
            if (nid != 0) Notifier.notify(applicationContext, nid, Notifier.lockTimeout(applicationContext))
            prefs.door = ""   // 상태 모름
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context, nid: Int) {
            WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<AutoLockWorker>()
                    .setInitialDelay(Prefs.AUTO_LOCK_MS, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("nid" to nid))
                    .build()
            )
        }
    }
}
