package com.hee.carmacro

import org.json.JSONObject
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ntfy 메시지 본문(JSON)
 *  cmd    : 리모컨 → 수신기  {"type":"cmd","cmd":"unlock",...}
 *  ack    : 수신기 → 리모컨  명령 받음
 *  result : 수신기 → 리모컨  결과. data = {"code","door","engine","minutes"}
 *  status : 수신기 → 리모컨  15분마다 상태. data = {"battery","charging","wifi","kia","door","engine",...}
 * sig = HMAC-SHA256(비밀키, type|cmd|id|ok|msg|data|ts)
 */
data class Msg(
    val type: String,
    val cmd: String,
    val id: String,
    val ok: Boolean = false,
    val msg: String = "",
    val data: String = "",
    val ts: Long = System.currentTimeMillis() / 1000,
) {
    fun dataJson(): JSONObject = try { JSONObject(data) } catch (e: Exception) { JSONObject() }
}

object Protocol {
    const val MAX_SKEW_SEC = 120

    const val CODE_OK = "OK"
    const val CODE_ALREADY = "ALREADY"
    const val CODE_FAIL = "FAIL"
    const val CODE_LOGIN = "LOGIN"

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun canonical(m: Msg) = "${m.type}|${m.cmd}|${m.id}|${m.ok}|${m.msg}|${m.data}|${m.ts}"

    private fun hmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun encode(m: Msg, secret: String): String = JSONObject().apply {
        put("type", m.type)
        put("cmd", m.cmd)
        put("id", m.id)
        put("ok", m.ok)
        put("msg", m.msg)
        put("data", m.data)
        put("ts", m.ts)
        put("sig", hmac(secret, canonical(m)))
    }.toString()

    /** 서명이 맞으면 Msg. 명령(cmd)은 시간까지 유효해야 함. */
    fun decode(body: String, secret: String): Msg? = try {
        val j = JSONObject(body)
        val m = Msg(
            type = j.getString("type"),
            cmd = j.optString("cmd"),
            id = j.getString("id"),
            ok = j.optBoolean("ok"),
            msg = j.optString("msg"),
            data = j.optString("data"),
            ts = j.getLong("ts"),
        )
        val now = System.currentTimeMillis() / 1000
        when {
            !constantEq(j.getString("sig"), hmac(secret, canonical(m))) -> null
            m.type == "cmd" && kotlin.math.abs(now - m.ts) > MAX_SKEW_SEC -> null
            else -> m
        }
    } catch (e: Exception) {
        null
    }

    private fun constantEq(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
}
