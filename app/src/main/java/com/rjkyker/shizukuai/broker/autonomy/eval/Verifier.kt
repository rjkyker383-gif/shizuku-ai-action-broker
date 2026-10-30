package com.rjkyker.shizukuai.broker.autonomy.eval

/**
 * U6 self-evaluation (1.9.0 = rule checks, no second model call). A draft that fails is not delivered:
 * the task goes to NEEDS_USER/FAILED instead of silently continuing.
 */
object Verifier {
    data class Result(val ok: Boolean, val issues: List<String>)

    private val OTP = Regex("""\b\d{6,8}\b""")
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val SECRET = Regex("""(?i)\b(password|passcode|api[_ -]?key|secret|token|pin code|ssn)\b""")
    private val LEAK = listOf("as an ai", "i am an ai", "language model", "you are a concise writing assistant",
        "<|im_start|>", "<|im_end|>", "<|endoftext|>")

    fun verifyDraft(draft: String, input: String = "", maxChars: Int = 1000): Result {
        val issues = mutableListOf<String>()
        val d = draft.trim()
        if (d.isEmpty()) issues += "empty draft"
        if (d.length > maxChars) issues += "too long (${d.length} > $maxChars)"
        if (OTP.containsMatchIn(d) && OTP.findAll(d).any { !input.contains(it.value) }) issues += "contains a code-like number not in the request"
        if (URL.containsMatchIn(d) && URL.findAll(d).any { !input.contains(it.value) }) issues += "contains a link not in the request"
        if (SECRET.containsMatchIn(d) && !SECRET.containsMatchIn(input)) issues += "mentions secrets"
        val low = d.lowercase()
        LEAK.firstOrNull { low.contains(it) }?.let { issues += "prompt/template leakage: '$it'" }
        if (d.length > 40 && repetitive(d)) issues += "repetitive output"
        return Result(issues.isEmpty(), issues)
    }

    private fun repetitive(s: String): Boolean {
        val words = s.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 12) return false
        val tri = words.windowed(3).map { it.joinToString(" ") }
        return tri.toSet().size < tri.size / 2
    }
}
