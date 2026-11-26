package com.hee.carmacro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifier {
    const val CH_SERVICE = "service"
    const val CH_RESULT = "result"
    const val CH_WARN = "warn"
    const val CH_PROGRESS = "progress_popup"

    const val ID_RECEIVER_FG = 20
    const val ID_RESULT = 30
    const val ID_DUMP = 40
    const val ID_WARN_PIXEL = 50
    const val ID_WARN_LOGIN = 51
    const val ID_CMD_BASE = 1000

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "진행 상태", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "실행 결과", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "명령 진행 (팝업)", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARN, "픽셀1 경고", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun ongoing(ctx: Context, title: String, text: String): Notification {
        ensureChannels(ctx)
        return Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    /** 명령 진행 알림: step 1 전송, 2 픽셀1 받음, 3 차 응답 */
    fun progress(ctx: Context, title: String, text: String, step: Int, popup: Boolean = true): Notification {
        ensureChannels(ctx)
        return Notification.Builder(ctx, if (popup) CH_PROGRESS else CH_SERVICE)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(listOf("전송", "픽셀1 받음", "차 응답").take(step.coerceIn(1, 3)).joinToString(" → "))
            .setProgress(3, step, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    fun build(ctx: Context, title: String, text: String, channel: String = CH_RESULT): Notification {
        ensureChannels(ctx)
        return Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    /** 문 열기 성공: 위쪽 팝업과 알림창에서 '자동 잠김까지' 초가 줄어듦 */
    fun unlocked(ctx: Context, msg: String, source: String, lockAt: Long): Notification {
        ensureChannels(ctx)
        return Notification.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("✅ 문 열기 성공 · 자동 잠김까지")
            .setContentText("30초 안에 문을 열지 않으면 다시 잠겨요")
            .setStyle(Notification.BigTextStyle().bigText("30초 안에 문을 열지 않으면 다시 잠겨요\n$msg ($source)"))
            .setWhen(lockAt).setShowWhen(true)
            .setUsesChronometer(true).setChronometerCountDown(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    /** 30초가 지난 뒤 (조용히 내용만 바꿈) */
    fun lockTimeout(ctx: Context): Notification {
        ensureChannels(ctx)
        return Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("문 상태 모름")
            .setContentText("문을 열지 않았다면 자동으로 다시 잠겼어요")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    fun notify(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun result(ctx: Context, title: String, text: String, id: Int = ID_RESULT, channel: String = CH_RESULT) =
        notify(ctx, id, build(ctx, title, text, channel))

    fun cancel(ctx: Context, id: Int) = ctx.getSystemService(NotificationManager::class.java).cancel(id)
}
