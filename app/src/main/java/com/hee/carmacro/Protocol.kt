package com.hee.carmacro

import org.json.JSONObject
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 메시지 형식 (ntfy 메시지 본문에 JSON으로 들어감)
 *  cmd    : {"type":"cmd","cmd":"unlock","id":..,"ts":..,"sig":..}
 *  ack    : {"type":"ack","cmd":..,"id":..,"ts":..,"sig":..}
 *  result : {"type":"result","cmd":..,"id":..,"ok":true,"msg":"..","ts":..,"sig":..}
 * sig = HMAC-SHA256(비밀키, type|cmd|id|ok|msg|ts)
 */
data class Msg(
    val type: String,
    val cmd: String,
    val id: String,
    val ok: Boolean = false,
    val msg: String = "",
    val ts: Long = System.currentTimeMillis() / 1000,
)

object Protocol {
    const val MAX_SKEW_SEC = 120

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun canonical(m: Msg) = "${m.type}|${m.cmd}|${m.id}|${m.ok}|${m.msg}|${m.ts}"

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
        put("ts", m.ts)
        put("sig", hmac(secret, canonical(m)))
    }.toString()

    /** 서명이 맞고 시간이 유효하면 Msg, 아니면 null */
    fun decode(body: String, secret: String): Msg? = try {
        val j = JSONObject(body)
        val m = Msg(
            type = j.getString("type"),
            cmd = j.optString("cmd"),
            id = j.getString("id"),
            ok = j.optBoolean("ok"),
            msg = j.optString("msg"),
            ts = j.getLong("ts"),
        )
        val sig = j.getString("sig")
        val now = System.currentTimeMillis() / 1000
        if (!constantEq(sig, hmac(secret, canonical(m)))) null
        else if (kotlin.math.abs(now - m.ts) > MAX_SKEW_SEC && m.type == "cmd") null
        else m
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
