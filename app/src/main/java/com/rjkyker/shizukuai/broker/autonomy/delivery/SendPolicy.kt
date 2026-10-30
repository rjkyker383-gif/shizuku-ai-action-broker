package com.rjkyker.shizukuai.broker.autonomy.delivery

/**
 * U10 preview-first delivery policy (pure). Even when every gate passes the result is PENDING_CONFIRM:
 * a human must approve each message on the device. Defaults: kill switch OFF, allowlists EMPTY.
 */
object SendPolicy {
    data class Input(
        val sendEnabled: Boolean,
        val appAllowlist: Set<String>,
        /** Entries "package|recipient", exact match. */
        val recipientAllowlist: Set<String>,
        val pkg: String,
        val recipient: String,
        val body: String,
        val now: Long,
        val globalSendTimes: List<Long>,
        val recipientSendTimes: List<Long>,
    )

    data class Decision(val state: String, val reason: String) {
        val pending: Boolean get() = state == PENDING_CONFIRM
    }

    const val REJECTED = "REJECTED"
    const val PENDING_CONFIRM = "PENDING_CONFIRM"
    const val MAX_BODY = 1000
    const val PER_RECIPIENT_HOUR = 3
    const val PER_RECIPIENT_DAY = 10
    const val GLOBAL_DAY = 20
    const val MIN_GAP_MS = 30_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    private val OTP = Regex("""\b\d{6,8}\b""")
    private val SECRET = Regex("""(?i)\b(password|passcode|api[_ -]?key|secret|one[- ]time code|verification code)\b""")
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")

    fun key(pkg: String, recipient: String) = "${pkg.trim()}|${recipient.trim()}"

    fun evaluate(i: Input): Decision {
        if (!i.sendEnabled) return Decision(REJECTED, "kill switch is OFF (send_enabled=false)")
        if (i.pkg.isBlank() || i.recipient.isBlank()) return Decision(REJECTED, "package and recipient required")
        if (i.pkg !in i.appAllowlist) return Decision(REJECTED, "app '${i.pkg}' not in send app allowlist")
        if (key(i.pkg, i.recipient) !in i.recipientAllowlist) return Decision(REJECTED, "recipient not in exact allowlist for ${i.pkg}")
        val b = i.body.trim()
        if (b.isEmpty()) return Decision(REJECTED, "empty message")
        if (b.length > MAX_BODY) return Decision(REJECTED, "message longer than $MAX_BODY chars")
        if (OTP.containsMatchIn(b)) return Decision(REJECTED, "looks like it contains a one-time code")
        if (SECRET.containsMatchIn(b)) return Decision(REJECTED, "mentions passwords/codes/keys")
        if (URL.containsMatchIn(b)) return Decision(REJECTED, "links are not allowed in automated sends")
        val lastAny = i.globalSendTimes.maxOrNull() ?: 0L
        if (i.now - lastAny < MIN_GAP_MS) return Decision(REJECTED, "rate: 30 s minimum gap")
        if (i.recipientSendTimes.count { i.now - it < HOUR } >= PER_RECIPIENT_HOUR) return Decision(REJECTED, "rate: $PER_RECIPIENT_HOUR/hour per recipient")
        if (i.recipientSendTimes.count { i.now - it < DAY } >= PER_RECIPIENT_DAY) return Decision(REJECTED, "rate: $PER_RECIPIENT_DAY/day per recipient")
        if (i.globalSendTimes.count { i.now - it < DAY } >= GLOBAL_DAY) return Decision(REJECTED, "rate: $GLOBAL_DAY/day total")
        return Decision(PENDING_CONFIRM, "all gates passed; waiting for on-device approval")
    }
}
