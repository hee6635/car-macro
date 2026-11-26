package com.hee.carmacro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 리모컨 쪽: 명령을 서명해서 ntfy로 보내고, 수신기(픽셀1)의 ack/result를 기다려
 * 알림으로 보여준다. 위젯 버튼과 앱 버튼 모두 이 서비스를 시작한다.
 */
class SenderService : Service() {
    private val busy = AtomicBoolean(false)
    @Volatile private var stream: Ntfy.Stream? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent?.getStringExtra(EXTRA_CMD) ?: Prefs.CMD_UNLOCK
        val label = Prefs.cmdLabel(cmd)
        startFg("$label 전송 중…")
        if (!busy.compareAndSet(false, true)) {
            status("이전 명령 처리 중입니다")
            return START_NOT_STICKY
        }
        Thread({ run(cmd, label) }, "sender").start()
        return START_NOT_STICKY
    }

    private fun startFg(text: String) {
        val n = Notifier.ongoing(this, "차량 매크로", text)
        ServiceCompat.startForeground(
            this, Notifier.ID_SENDER_FG, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    private fun status(text: String) {
        val prefs = Prefs(this)
        prefs.senderStatus = text
        CarWidget.updateAll(this, text)
        try {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(Notifier.ID_SENDER_FG, Notifier.ongoing(this, "차량 매크로", text))
        } catch (_: SecurityException) {
        }
    }

    private fun run(cmd: String, label: String) {
        val prefs = Prefs(this)
        try {
            if (!prefs.isPaired) {
                finish(false, label, "페어링 코드가 없습니다. 앱에서 먼저 설정하세요.")
                return
            }
            val id = Protocol.newId()
            val sentAt = System.currentTimeMillis() / 1000
            status("$label 전송 중…")
            AppLog.i(this, "[리모컨] $label 전송 id=$id")
            Ntfy.publish(prefs.server, prefs.topic, Protocol.encode(Msg("cmd", cmd, id), prefs.secret))
            status("$label 전송됨 · 수신기 응답 대기")

            var resultMsg: Msg? = null
            var gotAck = false
            val deadline = System.currentTimeMillis() + WAIT_MS
            // 타임아웃 감시: 시간이 지나면 스트림을 끊는다
            val watchdog = Thread {
                try {
                    while (System.currentTimeMillis() < deadline && resultMsg == null) Thread.sleep(500)
                } catch (_: InterruptedException) {
                }
                stream?.cancel()
            }.apply { isDaemon = true; start() }

            while (resultMsg == null && System.currentTimeMillis() < deadline) {
                try {
                    Ntfy.subscribe(prefs.server, prefs.topic, (sentAt - 2).toString(),
                        onOpen = { stream = it }) { _, body ->
                        val m = Protocol.decode(body, prefs.secret)
                        if (m != null && m.id == id) {
                            when (m.type) {
                                "ack" -> if (!gotAck) {
                                    gotAck = true
                                    status("수신기가 받음 · 기아 앱 조작 중…")
                                }
                                "result" -> resultMsg = m
                            }
                        }
                        resultMsg == null
                    }
                } catch (e: Exception) {
                    if (resultMsg == null && System.currentTimeMillis() < deadline) Thread.sleep(2000)
                }
            }
            watchdog.interrupt()

            val r = resultMsg
            when {
                r != null -> finish(r.ok, label, r.msg)
                gotAck -> finish(false, label, "수신기가 명령을 받았지만 결과가 오지 않았습니다(시간 초과). 기아 앱을 직접 확인하세요.")
                else -> finish(false, label, "수신기(픽셀1)가 응답하지 않습니다. 전원/인터넷/수신 서비스를 확인하세요.")
            }
        } catch (e: Exception) {
            finish(false, label, "전송 오류: ${e.message}")
        }
    }

    private fun finish(ok: Boolean, label: String, msg: String) {
        val title = if (ok) "✅ $label 성공" else "❌ $label 실패"
        AppLog.i(this, "[리모컨] $title - $msg")
        Prefs(this).senderStatus = "$title · ${time()}"
        CarWidget.updateAll(this, "$title · ${time()}")
        Notifier.result(this, title, msg)
        busy.set(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun time() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date())

    companion object {
        const val EXTRA_CMD = "cmd"
        private const val WAIT_MS = 150_000L

        fun intent(ctx: Context, cmd: String) =
            Intent(ctx, SenderService::class.java).putExtra(EXTRA_CMD, cmd)

        fun send(ctx: Context, cmd: String) {
            ctx.startForegroundService(intent(ctx, cmd))
        }
    }
}
