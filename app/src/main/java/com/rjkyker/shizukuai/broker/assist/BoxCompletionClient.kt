package com.rjkyker.shizukuai.broker.assist

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Remote (box) completion: POST {text} with Bearer token. Field text leaves the phone only when remote is opted in. */
object BoxCompletionClient {
    data class Reply(val ok: Boolean, val completion: String = "", val model: String = "", val url: String = "", val error: String? = null, val ms: Long = 0)

    fun complete(ctx: Context, before: String, maxWords: Int = 8): Reply {
        val token = AssistSettings.token(ctx) ?: return Reply(false, error = "no remote token set")
        var last: Reply = Reply(false, error = "no remote url")
        val deadline = System.currentTimeMillis() + 1500
        for (u in AssistSettings.remoteUrls(ctx)) {
            if (System.currentTimeMillis() > deadline) break
            val t0 = System.currentTimeMillis()
            last = runCatching {
                val c = (URL(u).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 600; readTimeout = 1300; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("ngrok-skip-browser-warning", "1")
                }
                c.outputStream.use { it.write(JSONObject().put("text", before.takeLast(1500)).put("max_words", maxWords).toString().toByteArray()) }
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                if (code !in 200..299) Reply(false, url = u, error = "HTTP $code", ms = System.currentTimeMillis() - t0)
                else JSONObject(body).let { j ->
                    Reply(j.optBoolean("ok"), j.optString("completion"), j.optString("model"), u,
                        j.optString("error").ifBlank { null }, System.currentTimeMillis() - t0)
                }
            }.getOrElse { Reply(false, url = u, error = "${it.javaClass.simpleName}: ${it.message?.take(80)}", ms = System.currentTimeMillis() - t0) }
            if (last.ok) return last
        }
        return last
    }
}
