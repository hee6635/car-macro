package com.hee.carmacro

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 리모컨: 15분마다 ntfy에서 픽셀1의 최근 상태 신호를 확인.
 * 40분 넘게 신호가 없으면 '픽셀1 연결 끊김', 기아 로그아웃이면 '다시 로그인 필요' 알림.
 */
class StatusWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        check(applicationContext)
        return Result.success()
    }

    companion object {
        private const val STALE_MS = 40 * 60_000L

        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "pixel-status", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StatusWorker>(15, TimeUnit.MINUTES).build()
            )
        }

        fun refreshNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<StatusWorker>().build())
        }

        fun check(ctx: Context) {
            val prefs = Prefs(ctx)
            if (!prefs.isPaired || prefs.mode != Prefs.MODE_SENDER) return
            try {
                val msgs = Ntfy.poll(prefs.server, prefs.topic, "2h")
                    .mapNotNull { Protocol.decode(it.second, prefs.secret) }
                val latest = msgs.filter { it.type == "status" }.maxByOrNull { it.ts }
                if (latest != null && latest.ts * 1000 > prefs.pixelStatusTime) {
                    prefs.pixelStatus = latest.data
                    prefs.pixelStatusTime = latest.ts * 1000
                    val d = latest.dataJson()
                    // 픽셀1이 마지막으로 본 문 상태가 더 최신이면 반영
                    val dt = d.optLong("doorTime")
                    if (d.optString("door").isNotEmpty() && dt > prefs.doorTime) {
                        prefs.door = d.optString("door"); prefs.doorTime = dt
                    }
                }
            } catch (e: Exception) {
                AppLog.i(ctx, "[리모컨] 상태 확인 실패: ${e.message}")
                return   // 내 폰 인터넷 문제일 수 있으니 경고하지 않음
            }

            val t = prefs.pixelStatusTime
            if (t == 0L) return   // 아직 한 번도 받은 적 없음
            val stale = System.currentTimeMillis() - t > STALE_MS
            if (stale && !prefs.pixelWarned) {
                prefs.pixelWarned = true
                val mins = (System.currentTimeMillis() - t) / 60_000
                Notifier.result(ctx, "픽셀1 연결 끊김",
                    "${mins}분째 픽셀1 신호가 없어요. 픽셀1 전원과 Wi-Fi를 확인하세요. 그동안 명령이 실행되지 않아요.",
                    Notifier.ID_WARN_PIXEL, Notifier.CH_WARN)
            } else if (!stale && prefs.pixelWarned) {
                prefs.pixelWarned = false
                Notifier.cancel(ctx, Notifier.ID_WARN_PIXEL)
            }

            val kia = try { org.json.JSONObject(prefs.pixelStatus).optString("kia") } catch (e: Exception) { "" }
            if (kia == "logout" && !prefs.loginWarned) {
                prefs.loginWarned = true
                Notifier.result(ctx, "기아 앱 다시 로그인 필요",
                    "픽셀1의 기아 앱이 로그아웃됐어요. 픽셀1에서 로그인해 주세요.", Notifier.ID_WARN_LOGIN, Notifier.CH_WARN)
            } else if (kia == "ok" && prefs.loginWarned) {
                prefs.loginWarned = false
                Notifier.cancel(ctx, Notifier.ID_WARN_LOGIN)
            }
        }
    }
}
