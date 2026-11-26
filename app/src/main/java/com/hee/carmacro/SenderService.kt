package com.hee.carmacro

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * 리모컨: 명령을 서명해 ntfy로 보내고 픽셀1의 ack/result를 기다린다.
 * 명령마다 알림 하나가 '전송 → 픽셀1 받음 → 차 응답 → 결과'로 바뀐다.
 * 여러 명령이 오면 순서대로 처리.
 */
class SenderService : Service() {
    private val queue = LinkedBlockingQueue<Pair<String, String>>()   // (cmd, source)
    @Volatile private var worker: Thread? = null
    @Volatile private var stream: Ntfy.Stream? = null
    private var seq = 0
    @Volatile private var fgId = 0
    @Volatile private var fgNote: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent?.getStringExtra(EXTRA_CMD) ?: Prefs.CMD_LOCK
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "앱"
        val label = Prefs.cmdLabel(cmd)
        // 같은 명령이 이미 대기 중이면 중복으로 보고 무시
        val duplicate = queue.any { it.first == cmd }
        synchronized(this) {
            val note = fgNote
            if (worker != null && note != null) startFg(fgId, note)   // 진행 중인 알림 유지
            else startFg(Notifier.ID_CMD_BASE, Notifier.progress(this, "$label 준비 중", "잠시만요", 1, popup = false))
            if (!duplicate) queue.add(cmd to source)
            if (worker == null) worker = Thread({ loop() }, "sender").apply { start() }
        }
        return START_NOT_STICKY
    }

    private fun startFg(id: Int, n: Notification) {
        fgId = id; fgNote = n
        ServiceCompat.startForeground(
            this, id, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    private fun loop() {
        var lastFinal: Pair<Int, Notification>? = null
        while (true) {
            val next = synchronized(this) {
                queue.poll() ?: run {
                    worker = null
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                    fgNote = null
                    null
                }
            } ?: break
            val (cmd, source) = next
            val nid = Notifier.ID_CMD_BASE + (++seq)
            val label = Prefs.cmdLabel(cmd)
            startFg(nid, Notifier.progress(this, "🚗 $label", "전송 중 · $source", 1))
            lastFinal?.let { Notifier.notify(this, it.first, it.second) }
            lastFinal = nid to send(cmd, label, source, nid)
        }
        lastFinal?.let { Notifier.notify(this, it.first, it.second) }
        synchronized(this) { if (worker == null) stopSelf() }
    }

    private fun stage(nid: Int, label: String, text: String, step: Int) {
        val n = Notifier.progress(this, "🚗 " + label.removeSuffix(" 진행 중"), text, step)
        if (nid == fgId) fgNote = n
        Notifier.notify(this, nid, n)
    }

    /** 명령 하나 처리 → 최종 알림 반환 */
    private fun send(cmd: String, label: String, source: String, nid: Int): Notification {
        val prefs = Prefs(this)
        if (!prefs.isPaired) return finish(prefs, label, false, "페어링 코드가 없어요. 앱에서 먼저 설정하세요.", source)
        val id = Protocol.newId()
        val sentAt = System.currentTimeMillis() / 1000
        try {
            AppLog.i(this, "[리모컨] $label 전송 ($source) id=$id")
            Ntfy.publish(prefs.server, prefs.topic, Protocol.encode(Msg("cmd", cmd, id), prefs.secret))
        } catch (e: Exception) {
            return finish(prefs, label, false, "전송 실패: ${e.message}. 인터넷 연결을 확인하세요.", source)
        }
        stage(nid, "$label 진행 중", "전송됨 · 픽셀1 응답 기다리는 중", 1)
        prefs.liveProgress = "$label\n전송됨 · 픽셀1 응답 기다리는 중"

        var result: Msg? = null
        var gotAck = false
        val start = System.currentTimeMillis()
        fun deadline() = start + (if (gotAck) WAIT_RESULT_MS else WAIT_ACK_MS)
        val watchdog = Thread {
            try {
                while (result == null && System.currentTimeMillis() < deadline()) Thread.sleep(500)
            } catch (_: InterruptedException) {
            }
            stream?.cancel()
        }.apply { isDaemon = true; start() }

        while (result == null && System.currentTimeMillis() < deadline()) {
            try {
                Ntfy.subscribe(prefs.server, prefs.topic, (sentAt - 2).toString(), onOpen = { stream = it }) { _, body ->
                    val m = Protocol.decode(body, prefs.secret)
                    if (m != null && m.id == id) {
                        if (m.type == "ack" && !gotAck) {
                            gotAck = true
                            val t = m.msg.ifEmpty { "픽셀1이 받음" }
                            stage(nid, "$label 진행 중", t, 2)
                            prefs.liveProgress = "$label\n$t"
                        } else if (m.type == "progress") {
                            gotAck = true
                            stage(nid, "$label 진행 중", m.msg, 2)
                            prefs.liveProgress = "$label\n${m.msg}"
                        } else if (m.type == "result") result = m
                    }
                    result == null
                }
            } catch (e: Exception) {
                if (result == null && System.currentTimeMillis() < deadline()) Thread.sleep(2000)
            }
        }
        watchdog.interrupt()

        val r = result
        return when {
            r != null -> {
                val d = r.dataJson()
                val code = d.optString("code", if (r.ok) Protocol.CODE_OK else Protocol.CODE_FAIL)
                applyCarState(prefs, cmd, code, d.optString("door"), d.optString("engine"), d.optInt("minutes"))
                if (code == Protocol.CODE_LOGIN) {
                    prefs.loginWarned = true
                    Notifier.result(this, "기아 앱 다시 로그인 필요",
                        "픽셀1의 기아 앱이 로그아웃됐어요. 픽셀1에서 로그인해 주세요.", Notifier.ID_WARN_LOGIN, Notifier.CH_WARN)
                }
                if (cmd == Prefs.CMD_UNLOCK && code == Protocol.CODE_OK) {
                    finish(prefs, label, true, r.msg, source)   // 기록 남기기
                    AutoLockWorker.schedule(this, nid)
                    Notifier.unlocked(this, r.msg, source, prefs.doorTime + Prefs.AUTO_LOCK_MS)
                } else finish(prefs, label, r.ok, r.msg, source, already = code == Protocol.CODE_ALREADY)
            }
            gotAck -> finish(prefs, label, false, "픽셀1이 받았지만 결과가 오지 않았어요. 기아 앱을 직접 확인하세요.", source)
            else -> finish(prefs, label, false, "픽셀1이 응답하지 않아요. 기아 앱으로 직접 해 주세요. (픽셀1 전원/인터넷 확인)", source)
        }
    }

    private fun applyCarState(prefs: Prefs, cmd: String, code: String, door: String, engine: String, minutes: Int) {
        val now = System.currentTimeMillis()
        if (door.isNotEmpty()) { prefs.door = door; prefs.doorTime = now }
        if (engine == "on" && (code == Protocol.CODE_OK || !prefs.engineRunning())) {
            if (code == Protocol.CODE_OK) {
                prefs.engineStart = now
                prefs.engineMinutes = minutes
            }
            prefs.engineOn = true
        } else if (engine == "off") prefs.engineOn = false
        if (cmd == Prefs.CMD_STOP && code != Protocol.CODE_FAIL) prefs.engineOn = false
    }

    private fun finish(prefs: Prefs, label: String, ok: Boolean, msg: String, source: String, already: Boolean = false): Notification {
        val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())
        val title = when {
            already -> "ℹ️ $label · $msg"
            ok -> "✅ $label 성공"
            else -> "❌ $label 실패"
        }
        AppLog.i(this, "[리모컨] $title - $msg")
        prefs.lastResult = "$title · $time"
        prefs.liveProgress = ""
        prefs.addHistory(source, title, msg)
        return Notifier.build(this, title, "$msg\n($time · $source)")
    }

    companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_SOURCE = "source"
        private const val WAIT_ACK_MS = 45_000L
        private const val WAIT_RESULT_MS = 180_000L

        fun intent(ctx: Context, cmd: String, source: String) =
            Intent(ctx, SenderService::class.java).putExtra(EXTRA_CMD, cmd).putExtra(EXTRA_SOURCE, source)

        fun send(ctx: Context, cmd: String, source: String) {
            ctx.startForegroundService(intent(ctx, cmd, source))
        }
    }
}
