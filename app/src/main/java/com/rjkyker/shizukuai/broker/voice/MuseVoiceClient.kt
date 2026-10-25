package com.rjkyker.shizukuai.broker.voice

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a spoken utterance to the box-side Muse voice relay and waits (bounded) for a reply.
 *
 *   POST https://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse/voice        {text, session}
 *   GET  https://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse/voice/reply?id=…
 *
 * The relay queues the utterance for Muse (voice_inbox / voice_reply MCP tools on the Muse bridge);
 * whatever answers it there (Muse's scheduled task, or another agent on RJ's box) posts the reply text,
 * optionally with an `audio_url` if real Muse TTS audio is ever available. Muse.ai exposes no public
 * TTS API, so normally only text comes back and the phone speaks it with Android TextToSpeech.
 *
 * Hard rule: only RJ's own host is ever contacted. Auth = the broker's own MCP token (same value the
 * box keeps as BROKER_TOKEN); it is sent only over HTTPS to that host and never logged.
 */
class MuseVoiceClient(private val tokenProvider: () -> String) {

    data class Reply(val text: String, val audioUrl: String?, val from: String)

    fun isAllowed(url: String): Boolean = runCatching {
        val u = URL(url); u.protocol == "https" && u.host == HOST
    }.getOrDefault(false)

    /** Blocking; call off the main thread. Returns null if no reply within [waitMs]. Throws on transport errors. */
    fun ask(text: String, session: String, waitMs: Long = DEFAULT_WAIT_MS, cancelled: () -> Boolean = { false }): Reply? {
        val post = http("POST", "$BASE/voice", JSONObject().put("text", text).put("session", session)
            .put("source", "broker-talk").toString())
        val id = post.optString("id")
        if (id.isEmpty()) throw IllegalStateException(post.optString("error", "relay refused"))
        replyFrom(post)?.let { return it }
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline && !cancelled()) {
            Thread.sleep(POLL_MS)
            val r = runCatching { http("GET", "$BASE/voice/reply?id=$id&wait=1", null) }.getOrNull() ?: continue
            replyFrom(r)?.let { return it }
        }
        return null
    }

    private fun replyFrom(o: JSONObject): Reply? {
        val t = o.optString("reply")
        if (t.isBlank()) return null
        val audio = o.optString("audio_url").takeIf { it.isNotBlank() && isAllowed(it) }
        return Reply(t, audio, o.optString("from", "muse"))
    }

    private fun http(method: String, url: String, body: String?): JSONObject {
        require(isAllowed(url)) { "host not allowed" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("ngrok-skip-browser-warning", "1")
            val tok = tokenProvider()
            if (tok.isNotBlank()) c.setRequestProperty("X-Broker-Token", tok)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val o = runCatching { JSONObject(txt) }.getOrDefault(JSONObject().put("error", "HTTP $code"))
            if (code !in 200..299 && !o.has("error")) o.put("error", "HTTP $code")
            return o
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val HOST = "presocial-endocrinopathic-fumiko.ngrok-free.dev"
        const val BASE = "https://$HOST/muse"
        const val DEFAULT_WAIT_MS = 45_000L
        private const val POLL_MS = 1_500L
    }
}
