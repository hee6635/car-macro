package com.hee.carmacro

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/** 앱 설정 저장소. 리모컨/수신기 공통. */
class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("carmacro", Context.MODE_PRIVATE)

    var mode: String
        get() = sp.getString("mode", MODE_SENDER) ?: MODE_SENDER
        set(v) = sp.edit().putString("mode", v).apply()

    var server: String
        get() = (sp.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER).trimEnd('/')
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    /** 페어링 코드 = "<토픽>.<비밀키>" */
    var pairing: String
        get() = sp.getString("pairing", "") ?: ""
        set(v) = sp.edit().putString("pairing", v.trim()).apply()

    val topic: String get() = pairing.substringBefore('.', "")
    val secret: String get() = pairing.substringAfter('.', "")
    val isPaired: Boolean get() = topic.length >= 8 && secret.length >= 12

    var targetPackage: String
        get() = sp.getString("target_pkg", "") ?: ""
        set(v) = sp.edit().putString("target_pkg", v.trim()).apply()

    fun script(cmd: String): String =
        sp.getString("script_$cmd", null) ?: defaultScript(cmd)

    fun setScript(cmd: String, text: String) = sp.edit().putString("script_$cmd", text).apply()

    var receiverEnabled: Boolean
        get() = sp.getBoolean("receiver_enabled", false)
        set(v) = sp.edit().putBoolean("receiver_enabled", v).apply()

    var receiverConn: String
        get() = sp.getString("receiver_conn", "-") ?: "-"
        set(v) = sp.edit().putString("receiver_conn", v).apply()

    var lastNtfyId: String
        get() = sp.getString("last_ntfy_id", "") ?: ""
        set(v) = sp.edit().putString("last_ntfy_id", v).apply()

    var senderStatus: String
        get() = sp.getString("sender_status", "대기 중") ?: "대기 중"
        set(v) = sp.edit().putString("sender_status", v).apply()

    var lastDump: String
        get() = sp.getString("last_dump", "") ?: ""
        set(v) = sp.edit().putString("last_dump", v).apply()

    /** 재전송 공격 방지용: 처리한 명령 id 목록(최근 50개) */
    @Synchronized
    fun markHandled(id: String): Boolean {
        val list = (sp.getString("handled_ids", "") ?: "").split(',').filter { it.isNotEmpty() }
        if (id in list) return false
        val next = (list + id).takeLast(50)
        sp.edit().putString("handled_ids", next.joinToString(",")).apply()
        return true
    }

    companion object {
        const val MODE_SENDER = "sender"
        const val MODE_RECEIVER = "receiver"
        const val DEFAULT_SERVER = "https://ntfy.sh"

        const val CMD_UNLOCK = "unlock"
        const val CMD_LOCK = "lock"

        fun cmdLabel(cmd: String) = when (cmd) {
            CMD_UNLOCK -> "문 열기"
            CMD_LOCK -> "문 잠그기"
            else -> cmd
        }

        fun generatePairing(): String {
            val rnd = SecureRandom()
            val abc = "abcdefghijkmnpqrstuvwxyz23456789"
            fun r(n: Int) = (1..n).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
            return "cm${r(18)}.${r(24)}"
        }

        fun defaultScript(cmd: String): String = when (cmd) {
            CMD_LOCK -> """
                # 문 잠그기 스크립트 (기아 앱 화면 문구에 맞게 고치세요)
                restart
                wait 5
                click 문 잠금|도어 잠금|잠그기
                # click 확인
                # pin 0000
                failon 실패|오류|시간 초과
                expect 90 잠금 완료|잠겼|성공
                home
            """.trimIndent()
            else -> """
                # 문 열기 스크립트 (기아 앱 화면 문구에 맞게 고치세요)
                restart
                wait 5
                click 문 열림|도어 열림|문 열기|잠금 해제
                # click 확인
                # pin 0000
                failon 실패|오류|시간 초과
                expect 90 열림 완료|해제 완료|열렸|성공
                home
            """.trimIndent()
        }
    }
}
