package com.hee.carmacro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat

/**
 * 수신기(픽셀1) 쪽: ntfy 토픽을 계속 구독하다가 서명이 맞는 명령이 오면
 * 접근성 서비스에 매크로 실행을 맡기고 결과를 다시 ntfy로 보낸다.
 */
class ReceiverService : Service() {
    @Volatile private var running = false
    @Volatile private var stream: Ntfy.Stream? = null
    private var thread: Thread? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg("연결 중…")
        cpuLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carmacro:receiver").apply {
                setReferenceCounted(false); acquire()
            }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "carmacro:wifi").apply {
                setReferenceCounted(false); acquire()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(Prefs(this).receiverConn)
        if (!running) {
            running = true
            thread = Thread({ loop() }, "receiver").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stream?.cancel()
        thread?.interrupt()
        cpuLock?.release()
        wifiLock?.release()
        setConn("중지됨")
        super.onDestroy()
    }

    private fun startFg(text: String) {
        ServiceCompat.startForeground(
            this, Notifier.ID_RECEIVER_FG, Notifier.ongoing(this, "차량 매크로 수신기", text),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun setConn(text: String) {
        Prefs(this).receiverConn = text
        if (running) {
            try {
                getSystemService(android.app.NotificationManager::class.java).notify(
                    Notifier.ID_RECEIVER_FG, Notifier.ongoing(this, "차량 매크로 수신기", text)
                )
            } catch (_: SecurityException) {
            }
        }
    }

    private fun loop() {
        var backoff = 2000L
        while (running) {
            val prefs = Prefs(this)
            if (!prefs.isPaired) {
                setConn("페어링 코드 없음")
                sleep(10_000); continue
            }
            try {
                setConn("연결 중…")
                // 마지막으로 본 메시지 이후부터 받음 (끊긴 동안 온 명령도 처리, 오래된 건 서명 시간검사로 버림)
                val since = prefs.lastNtfyId.ifEmpty { null }
                Ntfy.subscribe(prefs.server, prefs.topic, since, onOpen = {
                    stream = it
                }) { ntfyId, body ->
                    if (backoff != 2000L) backoff = 2000L
                    setConn("대기 중 · 연결됨")
                    if (ntfyId.isNotEmpty()) prefs.lastNtfyId = ntfyId
                    handle(prefs, body)
                    running
                }
            } catch (e: Exception) {
                if (!running) break
                AppLog.i(this, "[수신기] 연결 끊김: ${e.message}")
                setConn("재연결 대기 (${e.message})")
                sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun handle(prefs: Prefs, body: String) {
        val m = Protocol.decode(body, prefs.secret) ?: return
        if (m.type != "cmd") return
        if (!prefs.markHandled(m.id)) return
        if (m.cmd != Prefs.CMD_UNLOCK && m.cmd != Prefs.CMD_LOCK) return
        val label = Prefs.cmdLabel(m.cmd)
        AppLog.i(this, "[수신기] 명령 수신: $label (id=${m.id})")

        val svc = MacroAccessibilityService.instance
        if (svc == null) {
            reply(prefs, Msg("result", m.cmd, m.id, false, "수신기의 접근성 서비스가 꺼져 있습니다."))
            return
        }
        reply(prefs, Msg("ack", m.cmd, m.id))
        setConn("$label 실행 중…")
        // 매크로는 별도 스레드에서 실행 (구독 스트림은 계속 유지)
        Thread {
            val r = svc.runScript(prefs.script(m.cmd), prefs.targetPackage)
            reply(prefs, Msg("result", m.cmd, m.id, r.ok, r.message))
            Notifier.result(this, (if (r.ok) "✅ " else "❌ ") + label, r.message)
            setConn("대기 중 · 연결됨")
        }.start()
    }

    private fun reply(prefs: Prefs, m: Msg) {
        for (i in 1..3) {
            try {
                Ntfy.publish(prefs.server, prefs.topic, Protocol.encode(m, prefs.secret))
                return
            } catch (e: Exception) {
                AppLog.i(this, "[수신기] 응답 전송 실패($i): ${e.message}")
                sleep(2000)
            }
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    companion object {
        fun start(ctx: Context) {
            Prefs(ctx).receiverEnabled = true
            ctx.startForegroundService(Intent(ctx, ReceiverService::class.java))
        }

        fun stop(ctx: Context) {
            Prefs(ctx).receiverEnabled = false
            ctx.stopService(Intent(ctx, ReceiverService::class.java))
        }
    }
}
