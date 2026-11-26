package com.hee.carmacro

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** ntfy.sh (또는 자체 ntfy 서버)로 메시지를 보내고 받는 간단한 클라이언트 */
object Ntfy {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** ntfy는 45초마다 keepalive를 보내므로 그보다 길게 */
    private val streamClient = client.newBuilder()
        .readTimeout(80, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    fun publish(server: String, topic: String, body: String) {
        val req = Request.Builder()
            .url("$server/$topic")
            .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("ntfy 전송 실패 HTTP ${resp.code}")
        }
    }

    class Stream(private val call: okhttp3.Call) {
        fun cancel() = call.cancel()
    }

    /**
     * 토픽을 구독. onMessage(ntfyId, body) 는 'message' 이벤트마다 호출.
     * onMessage가 false를 반환하면 구독 종료. 연결이 끊기면 예외를 던짐.
     * @param since ntfy since 파라미터 (메시지 id 또는 unix 초). null이면 지금부터.
     */
    @Throws(IOException::class)
    fun subscribe(
        server: String,
        topic: String,
        since: String?,
        onOpen: (Stream) -> Unit = {},
        onMessage: (String, String) -> Boolean,
    ) {
        val url = buildString {
            append("$server/$topic/json")
            if (!since.isNullOrEmpty()) append("?since=$since")
        }
        val call = streamClient.newCall(Request.Builder().url(url).build())
        onOpen(Stream(call))
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("ntfy 구독 실패 HTTP ${resp.code}")
            val src = resp.body!!.source()
            while (true) {
                val line = src.readUtf8Line() ?: throw IOException("연결 종료")
                if (line.isBlank()) continue
                val j = try { JSONObject(line) } catch (e: Exception) { continue }
                if (j.optString("event") != "message") continue
                if (!onMessage(j.optString("id"), j.optString("message"))) return
            }
        }
    }
}
