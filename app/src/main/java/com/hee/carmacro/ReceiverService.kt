package com.hee.carmacro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 수신기(픽셀1): ntfy 토픽을 구독하다가 서명이 맞는 명령이 오면 순서대로 기아 앱을 조작하고
 * 결과를 돌려보낸다. 15분마다 상태 신호, 매일 08시 이후 한 번 기아 앱 로그인 점검.
 */
class ReceiverService : Service() {
    @Volatile private var running = false
    @Volatile private var stream: Ntfy.Stream? = null
    @Volatile private var pending = 0
    private var listenThread: Thread? = null
    private var statusThread: Thread? = null
    private val worker = Executors.newSingleThreadExecutor()
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
            listenThread = Thread({ listenLoop() }, "receiver-listen").apply { start() }
            statusThread = Thread({ statusLoop() }, "receiver-status").apply { start() }
        }
        intent?.getStringExtra(EXTRA_LOCAL_CMD)?.let { enqueue(it, null) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stream?.cancel()
        listenThread?.interrupt()
        statusThread?.interrupt()
        worker.shutdownNow()
        cpuLock?.release()
        wifiLock?.release()
        Prefs(this).receiverConn = "중지됨"
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
        if (!running) return
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(
                Notifier.ID_RECEIVER_FG, Notifier.ongoing(this, "차량 매크로 수신기", text)
            )
        } catch (_: SecurityException) {
        }
    }

    // ───────────────────────── 명령 수신 ─────────────────────────

    private fun listenLoop() {
        var backoff = 2000L
        while (running) {
            val prefs = Prefs(this)
            if (!prefs.isPaired) {
                setConn("페어링 코드 없음"); sleep(10_000); continue
            }
            try {
                setConn("연결 중…")
                val since = prefs.lastNtfyId.ifEmpty { null }
                Ntfy.subscribe(prefs.server, prefs.topic, since, onOpen = {
                    stream = it
                    setConn("대기 중 · 연결됨")
                }) { ntfyId, body ->
                    backoff = 2000L
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
        if (m.type != "cmd" || m.cmd !in Prefs.CAR_CMDS) return
        if (!prefs.markHandled(m.id)) return
        AppLog.i(this, "[수신기] 명령 수신: ${Prefs.cmdLabel(m.cmd)} (id=${m.id})")
        if (MacroAccessibilityService.instance == null) {
            reply(prefs, Msg("result", m.cmd, m.id, false, "픽셀1의 접근성 권한이 꺼져 있어요",
                MacroAccessibilityService.Result(Protocol.CODE_FAIL, "").dataJson()))
            return
        }
        reply(prefs, Msg("ack", m.cmd, m.id, msg = if (pending > 0) "앞 명령 처리 후 실행" else ""))
        enqueue(m.cmd, m.id)
    }

    /** 명령은 한 번에 하나씩 순서대로. id == null 이면 픽셀1에서 직접 실행(테스트/점검) */
    private fun enqueue(cmd: String, id: String?) {
        pending++
        worker.execute {
            try {
                val prefs = Prefs(this)
                val svc = MacroAccessibilityService.instance
                setConn("${Prefs.cmdLabel(cmd)} 실행 중…")
                val r = svc?.run(cmd, prefs)
                    ?: MacroAccessibilityService.Result(Protocol.CODE_FAIL, "접근성 권한이 꺼져 있어요")
                if (r.door.isNotEmpty()) { prefs.door = r.door; prefs.doorTime = System.currentTimeMillis() }
                if (id != null) reply(prefs, Msg("result", cmd, id, r.ok, r.message, r.dataJson()))
                val title = when (r.code) {
                    Protocol.CODE_OK -> "✅ "; Protocol.CODE_ALREADY -> "ℹ️ "; else -> "❌ "
                } + Prefs.cmdLabel(cmd) + if (id == null) " (픽셀1 테스트)" else ""
                Notifier.result(this, title, r.message, Notifier.ID_RESULT)
                if (r.code == Protocol.CODE_LOGIN) publishStatus()
            } finally {
                pending--
                setConn("대기 중 · 연결됨")
            }
        }
    }

    private fun reply(prefs: Prefs, m: Msg) {
        for (i in 1..3) {
            try {
                Ntfy.publish(prefs.server, prefs.topic, Protocol.encode(m, prefs.secret)); return
            } catch (e: Exception) {
                AppLog.i(this, "[수신기] 응답 전송 실패($i): ${e.message}"); sleep(2000)
            }
        }
    }

    // ───────────────────────── 상태 신호 / 로그인 점검 ─────────────────────────

    private fun statusLoop() {
        sleep(5_000)
        while (running) {
            publishStatus()
            dailyLoginCheck()
            sleep(STATUS_INTERVAL_MS)
        }
    }

    private fun dailyLoginCheck() {
        val prefs = Prefs(this)
        val today = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < 8 || prefs.lastLoginCheckDay == today || pending > 0) return
        if (prefs.targetPackage.isEmpty() || MacroAccessibilityService.instance == null) return
        prefs.lastLoginCheckDay = today
        AppLog.i(this, "[수신기] 매일 기아 앱 로그인 점검")
        enqueue(Prefs.CMD_CHECK, null)
        worker.execute { publishStatus() }
    }

    private fun publishStatus() {
        val prefs = Prefs(this)
        if (!prefs.isPaired) return
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0) l * 100 / s else -1
        } ?: -1
        val charging = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cm = getSystemService(ConnectivityManager::class.java)
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val data = JSONObject().apply {
            put("battery", level); put("charging", charging); put("wifi", wifi)
            put("kia", prefs.kiaLogin)
            put("a11y", MacroAccessibilityService.instance != null)
            put("door", prefs.door); put("doorTime", prefs.doorTime)
        }.toString()
        try {
            Ntfy.publish(prefs.server, prefs.topic, Protocol.encode(Msg("status", "", Protocol.newId(), data = data), prefs.secret))
        } catch (e: Exception) {
            AppLog.i(this, "[수신기] 상태 신호 실패: ${e.message}")
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    companion object {
        const val EXTRA_LOCAL_CMD = "local_cmd"
        const val STATUS_INTERVAL_MS = 15 * 60_000L

        fun start(ctx: Context) {
            Prefs(ctx).receiverEnabled = true
            ctx.startForegroundService(Intent(ctx, ReceiverService::class.java))
        }

        fun stop(ctx: Context) {
            Prefs(ctx).receiverEnabled = false
            ctx.stopService(Intent(ctx, ReceiverService::class.java))
        }

        /** 픽셀1에서 직접 테스트 실행 (수신 서비스 큐를 통해 순서대로) */
        fun runLocal(ctx: Context, cmd: String) {
            ctx.startForegroundService(Intent(ctx, ReceiverService::class.java).putExtra(EXTRA_LOCAL_CMD, cmd))
        }
    }
}
