package com.hee.carmacro

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.SecureRandom

/** 기아 앱 '시동 / 공조 설정'에 적용할 값. 빈 문자열/-1 = 건드리지 않음 */
data class Preset(
    val temp: String = "",       // "Low", "High", "25" ...
    val defrost: Int = -1,       // 앞유리 성에 제거   1 켬 / 0 끔 / -1 그대로
    val mirror: Int = -1,        // 사이드 미러 + 뒷유리 열선
    val wheel: Int = -1,         // 핸들 열선
    val seat: String = "",       // 시트 열선/통풍 하위 화면에서 고를 문구
    val duration: String = "",   // 시동 유지 시간 하위 화면에서 고를 문구 (예: "10분")
    val mode: String = "",       // 공조 모드 하위 화면에서 고를 문구
) {
    fun toJson(): String = JSONObject().apply {
        put("temp", temp); put("defrost", defrost); put("mirror", mirror); put("wheel", wheel)
        put("seat", seat); put("duration", duration); put("mode", mode)
    }.toString()

    val isEmpty get() = temp.isEmpty() && defrost < 0 && mirror < 0 && wheel < 0 &&
        seat.isEmpty() && duration.isEmpty() && mode.isEmpty()

    val minutes: Int get() = Regex("(\\d+)").find(duration)?.value?.toIntOrNull() ?: 0

    companion object {
        fun fromJson(s: String?): Preset? = try {
            val j = JSONObject(s ?: return null)
            Preset(
                j.optString("temp"), j.optInt("defrost", -1), j.optInt("mirror", -1),
                j.optInt("wheel", -1), j.optString("seat"), j.optString("duration"), j.optString("mode"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** 앱 설정 저장소. 리모컨/수신기 공통. */
class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("carmacro", Context.MODE_PRIVATE)

    private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun l(k: String) = sp.getLong(k, 0L)
    private fun putL(k: String, v: Long) = sp.edit().putLong(k, v).apply()
    private fun b(k: String, d: Boolean = false) = sp.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    // ── 공통 ──
    var mode: String
        get() = s("mode", MODE_SENDER)
        set(v) = put("mode", v)

    var server: String
        get() = s("server", DEFAULT_SERVER).trimEnd('/')
        set(v) = put("server", v.trim().trimEnd('/'))

    /** 페어링 코드 = "<토픽>.<비밀키>" */
    var pairing: String
        get() = s("pairing")
        set(v) = put("pairing", v.trim())

    val topic: String get() = pairing.substringBefore('.', "")
    val secret: String get() = pairing.substringAfter('.', "")
    val isPaired: Boolean get() = topic.length >= 8 && secret.length >= 12

    // ── 수신기(픽셀1) ──
    var targetPackage: String
        get() = s("target_pkg")
        set(v) = put("target_pkg", v.trim())

    var pin: String
        get() = s("pin")
        set(v) = put("pin", v.filter { it.isDigit() })

    fun preset(n: Int): Preset = Preset.fromJson(sp.getString("preset_$n", null)) ?: defaultPreset(n)
    fun setPreset(n: Int, p: Preset) = put("preset_$n", p.toJson())

    var receiverEnabled: Boolean
        get() = b("receiver_enabled")
        set(v) = putB("receiver_enabled", v)

    var receiverConn: String
        get() = s("receiver_conn", "-")
        set(v) = put("receiver_conn", v)

    var lastNtfyId: String
        get() = s("last_ntfy_id")
        set(v) = put("last_ntfy_id", v)

    /** 기아 앱 로그인 상태: "ok" / "logout" / "" (모름) */
    var kiaLogin: String
        get() = s("kia_login")
        set(v) = put("kia_login", v)

    var lastLoginCheckDay: String
        get() = s("login_check_day")
        set(v) = put("login_check_day", v)

    var lastDump: String
        get() = s("last_dump")
        set(v) = put("last_dump", v)

    /** 재전송 공격 방지용: 처리한 명령 id 목록(최근 50개) */
    @Synchronized
    fun markHandled(id: String): Boolean {
        val list = s("handled_ids").split(',').filter { it.isNotEmpty() }
        if (id in list) return false
        put("handled_ids", (list + id).takeLast(50).joinToString(","))
        return true
    }

    // ── 차 상태 (양쪽 모두 마지막으로 안 값) ──
    /** "locked" / "unlocked" / "" */
    var door: String
        get() = s("car_door")
        set(v) = put("car_door", v)
    var doorTime: Long
        get() = l("car_door_time")
        set(v) = putL("car_door_time", v)
    var engineOn: Boolean
        get() = b("engine_on")
        set(v) = putB("engine_on", v)
    var engineStart: Long
        get() = l("engine_start")
        set(v) = putL("engine_start", v)
    var engineMinutes: Int
        get() = sp.getInt("engine_minutes", 0)
        set(v) = sp.edit().putInt("engine_minutes", v).apply()

    /** 시동이 켜져 있다고 볼 수 있는지 (유지 시간이 지나면 꺼진 것으로 봄) */
    fun engineRunning(now: Long = System.currentTimeMillis()): Boolean {
        if (!engineOn) return false
        val mins = if (engineMinutes > 0) engineMinutes else 10
        return now < engineStart + mins * 60_000L
    }

    // ── 리모컨: 픽셀1 상태 ──
    var pixelStatus: String
        get() = s("pixel_status")
        set(v) = put("pixel_status", v)
    var pixelStatusTime: Long
        get() = l("pixel_status_time")
        set(v) = putL("pixel_status_time", v)
    var pixelWarned: Boolean
        get() = b("pixel_warned")
        set(v) = putB("pixel_warned", v)
    var loginWarned: Boolean
        get() = b("login_warned")
        set(v) = putB("login_warned", v)

    var widgetPreset: Int
        get() = sp.getInt("widget_preset", 2)
        set(v) = sp.edit().putInt("widget_preset", v).apply()

    var lastResult: String
        get() = s("last_result", "아직 실행한 명령이 없어요")
        set(v) = put("last_result", v)

    companion object {
        const val MODE_SENDER = "sender"
        const val MODE_RECEIVER = "receiver"
        const val DEFAULT_SERVER = "https://ntfy.sh"

        const val CMD_UNLOCK = "unlock"
        const val CMD_LOCK = "lock"
        const val CMD_START1 = "start1"
        const val CMD_START2 = "start2"
        const val CMD_START3 = "start3"
        const val CMD_START_MAX = "start_max"
        const val CMD_STOP = "stop"
        const val CMD_CHECK = "check"      // 기아 앱 로그인 점검 (수신기 내부용)
        const val CMD_SIGNAL = "signal"    // 퇴근 신호: 진동만, 전송 안 함

        val CAR_CMDS = setOf(CMD_UNLOCK, CMD_LOCK, CMD_START1, CMD_START2, CMD_START3, CMD_START_MAX, CMD_STOP)

        val PRESET_NAMES = mapOf(1 to "한파", 2 to "난방", 3 to "냉방")

        fun presetOf(cmd: String): Int = when (cmd) {
            CMD_START1 -> 1; CMD_START2 -> 2; CMD_START3 -> 3; else -> 0
        }

        fun startCmd(n: Int) = when (n) { 1 -> CMD_START1; 3 -> CMD_START3; else -> CMD_START2 }

        fun cmdLabel(cmd: String) = when (cmd) {
            CMD_UNLOCK -> "문 열기"
            CMD_LOCK -> "문 잠그기"
            CMD_START1, CMD_START2, CMD_START3 -> "시동 · ${PRESET_NAMES[presetOf(cmd)]}"
            CMD_START_MAX -> "시동 · 최대 난방"
            CMD_STOP -> "시동 끄기"
            CMD_CHECK -> "기아 앱 점검"
            else -> cmd
        }

        fun defaultPreset(n: Int) = when (n) {
            1 -> Preset(temp = "High", defrost = 1, mirror = 1, wheel = 1, duration = "10분")
            3 -> Preset(temp = "Low", defrost = 0, mirror = 0, wheel = 0, duration = "10분")
            else -> Preset(temp = "25", defrost = 0, mirror = 0, wheel = 1, duration = "10분")
        }

        fun generatePairing(): String {
            val rnd = SecureRandom()
            val abc = "abcdefghijkmnpqrstuvwxyz23456789"
            fun r(n: Int) = (1..n).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
            return "cm${r(18)}.${r(24)}"
        }
    }
}
