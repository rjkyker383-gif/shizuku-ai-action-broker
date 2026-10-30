package com.rjkyker.shizukuai.broker.voice

/**
 * Instant on-device answers for a few things the phone itself knows (no network, no LLM):
 * broker status, last scan meaning, help, time. Everything else goes to Muse via [MuseVoiceClient].
 * Pure / Android-free (facts are passed in) so it is unit-testable. Never takes actions.
 */
object LocalAgent {

    data class Facts(
        val serverRunning: Boolean,
        val boundAddresses: List<String>,
        val accessibilityOn: Boolean,
        val shizukuOk: Boolean,
        val lastScanEnglish: List<String>,
        val nowText: String,
    )

    /** Returns a spoken answer, or null if the question should go to Muse. */
    fun answer(utterance: String, f: Facts): String? {
        val u = utterance.lowercase().trim()
        if (u.isEmpty()) return null
        fun has(vararg w: String) = w.any { u.contains(it) }
        return when {
            has("help", "what can you do") && u.length < 40 ->
                "Ask me for broker status, what your last scan means, or anything else and I'll pass it to Muse. Say stop to end."
            has("status", "health", "are you online", "is the broker") && !has("muse") -> buildString {
                append(if (f.serverRunning) "The broker is running" else "The broker server is stopped")
                val ts = f.boundAddresses.firstOrNull { it.startsWith("100.") }
                append(if (ts != null) ", reachable on Tailscale at $ts" else ", loopback only, Tailscale isn't connected")
                append(". Accessibility is ${if (f.accessibilityOn) "on" else "off"}, Shizuku is ${if (f.shizukuOk) "ready" else "not ready"}.")
            }
            has("last scan", "that scan", "what did i scan", "what does this barcode", "what does it mean", "what's this barcode", "what is this barcode") ->
                if (f.lastScanEnglish.isEmpty()) "I don't have any scans yet. Scan a barcode and ask again."
                else "Your last scan: " + f.lastScanEnglish.take(4).joinToString(" ")
            has("what time", "the time") && u.length < 30 -> "It's ${f.nowText}."
            else -> null
        }
    }

    fun isStopPhrase(utterance: String): Boolean {
        val u = utterance.lowercase().trim().trimEnd('.', '!')
        return u in setOf("stop", "stop listening", "goodbye", "bye", "that's all", "end session", "cancel")
    }
}
