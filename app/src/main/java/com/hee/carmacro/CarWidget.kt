package com.hee.carmacro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * 홈 화면 위젯: [문 (상태에 따라 열기/잠그기)] [시동 / 시동 끄기]
 * 위젯은 길게 누르기를 지원하지 않아서, 문 열기·시동은 '한 번 더 누르면 실행'(4초 안에 두 번).
 * 잠그기·시동 끄기는 한 번에 실행.
 */
class CarWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, views(ctx, null))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) return super.onReceive(ctx, intent)
        val cmd = intent.getStringExtra("cmd") ?: return
        val needConfirm = cmd != Prefs.CMD_LOCK && cmd != Prefs.CMD_STOP
        val now = System.currentTimeMillis()
        if (needConfirm && !(armedCmd == cmd && now - armedAt < 4000)) {
            armedCmd = cmd; armedAt = now
            updateAll(ctx, "한 번 더 누르면 ${Prefs.cmdLabel(cmd)}")
            return
        }
        armedCmd = null
        try {
            SenderService.send(ctx, cmd, "위젯")
        } catch (e: Exception) {
            updateAll(ctx, "실행 실패: ${e.message}")
        }
    }

    companion object {
        const val ACTION_TAP = "com.hee.carmacro.WIDGET_TAP"
        @Volatile private var armedCmd: String? = null
        @Volatile private var armedAt = 0L

        private fun pi(ctx: Context, cmd: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, req,
            Intent(ctx, CarWidget::class.java).setAction(ACTION_TAP).putExtra("cmd", cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** 마지막으로 안 문 상태가 '열림'이고 10분 안의 정보일 때만 잠그기 버튼을 보여줌 */
        fun doorShowsLock(p: Prefs) =
            p.door == "unlocked" && System.currentTimeMillis() - p.doorTime < 10 * 60_000L

        fun views(ctx: Context, statusOverride: String?): RemoteViews {
            val p = Prefs(ctx)
            val lock = doorShowsLock(p)
            val running = p.engineRunning()
            val doorText = when (p.door) { "locked" -> "문 잠김"; "unlocked" -> "문 열림"; else -> "문 상태 모름" }
            val pixel = if (p.pixelWarned) "픽셀1 연결 끊김" else "픽셀1 정상"
            return RemoteViews(ctx.packageName, R.layout.widget).apply {
                setTextViewText(R.id.w_title, "쏘렌토 · $doorText · ${if (running) "시동 켜짐" else "시동 꺼짐"} · $pixel")
                setTextViewText(R.id.w_door, if (lock) "🔒 잠그기" else "🔓 문 열기")
                setOnClickPendingIntent(R.id.w_door, pi(ctx, if (lock) Prefs.CMD_LOCK else Prefs.CMD_UNLOCK, 1))
                val startCmd = Prefs.startCmd(p.widgetPreset)
                setTextViewText(R.id.w_start, if (running) "⏹ 시동 끄기" else "🔥 시동 · ${Prefs.PRESET_NAMES[p.widgetPreset]}")
                setOnClickPendingIntent(R.id.w_start, pi(ctx, if (running) Prefs.CMD_STOP else startCmd, 2))
                setTextViewText(R.id.w_status, statusOverride ?: p.lastResult)
            }
        }

        fun updateAll(ctx: Context, status: String?) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CarWidget::class.java))
            for (id in ids) mgr.updateAppWidget(id, views(ctx, status))
        }
    }
}
