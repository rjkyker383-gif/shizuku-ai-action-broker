package com.rjkyker.shizukuai.broker.voice

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a spoken utterance to the box-side Muse voice relay and waits (bounded) for a reply.
 *
 *   POST https://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse/voice                 {text, session}
 *   GET  https://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse/voice/reply?id=…      -> {reply, audio_url?, voice?}
 *   GET  https://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse/voice/audio/<id>.mp3  (X-Broker-Token)
 *
 * Muse's answer worker synthesizes the reply with Muse's own TTS (default male voice Static,
 * avocado_v2:vdc_3944; RJ can pick another male voice and it is sent as "voice" with each utterance) and uploads the MP3 to the relay (POST /voice/answer). The phone downloads that MP3
 * with its token and plays it; Android TTS is only the fallback when no audio arrives in time.
 *
 * Hard rule: only RJ's own host is ever contacted. Auth = the broker's own MCP token (same value the
 * box keeps as BROKER_TOKEN); it is sent only over HTTPS to that host and never logged.
 */
class MuseVoiceClient(private val tokenProvider: () -> String) {

    data class Reply(val id: String, val text: String, val audioUrl: String?, val voice: String?, val from: String) {
        /** UI/log label, e.g. "Muse voice — Static". */
        val voiceLabel: String get() = "Muse voice — ${shortVoiceName(voice)}"
    }

    fun isAllowed(url: String): Boolean = isAllowedUrl(url)

    /**
     * Blocking; call off the main thread. Returns null if no reply within [waitMs]. Throws on transport errors.
     * If a text-only reply arrives first, keeps polling up to [AUDIO_GRACE_MS] (bounded by the deadline) in case
     * Muse's MP3 lands right after it.
     */
    fun ask(text: String, session: String, voice: String = DEFAULT_VOICE, waitMs: Long = DEFAULT_WAIT_MS, cancelled: () -> Boolean = { false }): Reply? {
        val post = http("POST", "$BASE/voice", JSONObject().put("text", text).put("session", session)
            .put("source", "broker-talk").put("voice", normalizeVoice(voice)).toString())
        val id = post.optString("id")
        if (id.isEmpty()) throw IllegalStateException(post.optString("error", "relay refused"))
        var textOnly: Reply? = parseReply(post.put("id", id))
        if (textOnly?.audioUrl != null) return textOnly
        val deadline = System.currentTimeMillis() + waitMs
        var graceEnd = if (textOnly != null) System.currentTimeMillis() + AUDIO_GRACE_MS else Long.MAX_VALUE
        while (System.currentTimeMillis() < minOf(deadline, graceEnd) && !cancelled()) {
            Thread.sleep(POLL_MS)
            val r = runCatching { http("GET", "$BASE/voice/reply?id=$id", null) }.getOrNull() ?: continue
            val p = parseReply(r.put("id", id)) ?: continue
            if (p.audioUrl != null) return p
            if (textOnly == null) { textOnly = p; graceEnd = System.currentTimeMillis() + AUDIO_GRACE_MS }
        }
        return textOnly
    }

    /**
     * Download Muse's MP3 (token header, allowed host only, ≤ [MAX_AUDIO_BYTES], must look like MP3) into [dest].
     * Returns the byte count. Throws on any failure (caller falls back to Android TTS).
     */
    fun downloadAudio(url: String, dest: File): Long {
        require(isAllowedUrl(url)) { "audio host not allowed" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("ngrok-skip-browser-warning", "1")
            val tok = tokenProvider()
            if (tok.isNotBlank()) c.setRequestProperty("X-Broker-Token", tok)
            val code = c.responseCode
            if (code != 200) throw IllegalStateException("audio HTTP $code")
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            var total = 0L
            c.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        total += n
                        if (total > MAX_AUDIO_BYTES) throw IllegalStateException("audio too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            val head = ByteArray(4)
            val got = tmp.inputStream().use { it.read(head) }
            if (got < 4 || !looksLikeMp3(head)) { tmp.delete(); throw IllegalStateException("not mp3") }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            return total
        } finally {
            c.disconnect()
        }
    }

    private fun http(method: String, url: String, body: String?): JSONObject {
        require(isAllowedUrl(url)) { "host not allowed" }
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
        /** Muse synthesizes per reply; 60 s covers poll + TTS + upload. */
        const val DEFAULT_WAIT_MS = 60_000L
        const val AUDIO_GRACE_MS = 8_000L
        const val MAX_AUDIO_BYTES = 5L * 1024 * 1024
        const val DEFAULT_VOICE = "Static"
        /** Muse's male voices (RJ's rule: male only). Name -> Muse TTS voice id. */
        val MUSE_VOICES = linkedMapOf(
            "Static" to "avocado_v2:vdc_3944", "Jordan" to "avocado_v2:MAI_03", "Morgan" to "avocado_v2:casper",
            "Bubba" to "avocado_v2:vdc_13854", "Prophet" to "avocado_v2:briggs", "Ronan" to "avocado_v2:ronan"
        )

        /** Known Muse voice name (case-insensitive, or its id) -> canonical name; anything else -> Static. */
        fun normalizeVoice(v: String?): String {
            val s = v?.trim().orEmpty()
            return MUSE_VOICES.entries.firstOrNull { it.key.equals(s, true) || it.value.equals(s, true) }?.key
                ?: DEFAULT_VOICE
        }
        private const val POLL_MS = 1_500L

        fun isAllowedUrl(url: String): Boolean = runCatching {
            val u = URL(url); u.protocol == "https" && u.host == HOST && u.userInfo == null
        }.getOrDefault(false)

        /** Pure parser for a /voice or /voice/reply JSON body. Null while pending / empty. */
        fun parseReply(o: JSONObject): Reply? {
            val t = o.optString("reply").trim()
            if (t.isEmpty()) return null
            val audio = o.optString("audio_url").trim().takeIf { it.isNotEmpty() && isAllowedUrl(it) }
            val voice = o.optString("voice").trim().takeIf { it.isNotEmpty() }
            return Reply(o.optString("id"), t, audio, voice, o.optString("from").ifBlank { "muse" })
        }

        /** "Static (avocado_v2:vdc_3944)" -> "Static"; blank -> default. */
        fun shortVoiceName(voice: String?): String {
            val v = voice?.trim().orEmpty()
            if (v.isEmpty()) return DEFAULT_VOICE
            return v.substringBefore(" (").trim().ifEmpty { DEFAULT_VOICE }
        }

        fun looksLikeMp3(b: ByteArray): Boolean {
            if (b.size < 3) return false
            if (b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) return true
            val b0 = b[0].toInt() and 0xFF; val b1 = b[1].toInt() and 0xFF
            return b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) != 0
        }
    }
}
