package com.hee.carmacro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/** 홈 화면 위젯: [열기] [잠금] 버튼 + 상태 표시 */
class CarWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val status = Prefs(ctx).senderStatus
        for (id in ids) mgr.updateAppWidget(id, views(ctx, status))
    }

    companion object {
        private fun pi(ctx: Context, cmd: String, req: Int): PendingIntent =
            PendingIntent.getForegroundService(
                ctx, req, SenderService.intent(ctx, cmd),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        fun views(ctx: Context, status: String) =
            RemoteViews(ctx.packageName, R.layout.widget).apply {
                setOnClickPendingIntent(R.id.w_open, pi(ctx, Prefs.CMD_UNLOCK, 1))
                setOnClickPendingIntent(R.id.w_lock, pi(ctx, Prefs.CMD_LOCK, 2))
                setTextViewText(R.id.w_status, status)
            }

        fun updateAll(ctx: Context, status: String) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CarWidget::class.java))
            for (id in ids) mgr.updateAppWidget(id, views(ctx, status))
        }
    }
}
