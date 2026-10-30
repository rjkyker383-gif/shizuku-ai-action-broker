package com.rjkyker.shizukuai.broker.autonomy.context

/**
 * U4 context manager: fits system + memories + recent results + the user request into the small on-device
 * window (n_ctx 2048, minus generation budget). The user's request is never dropped; memories go first, then
 * the oldest recent items. Token count is a conservative estimate (chars/3.2) so we never overflow.
 */
object ContextManager {
    data class Built(val system: String, val prompt: String, val estTokens: Int, val droppedMemories: Int,
                     val droppedRecent: Int, val truncatedUser: Boolean)

    const val N_CTX = 2048
    const val TEMPLATE_OVERHEAD = 48

    fun estTokens(s: String): Int = if (s.isEmpty()) 0 else (s.length / 3.2).toInt() + 1

    fun build(
        system: String, user: String, memories: List<String> = emptyList(), recent: List<String> = emptyList(),
        maxNewTokens: Int = 256, nCtx: Int = N_CTX,
    ): Built {
        val budget = nCtx - maxNewTokens - TEMPLATE_OVERHEAD
        val sysT = estTokens(system)
        var userText = user
        var truncated = false
        val userMax = (budget - sysT).coerceAtLeast(64)
        if (estTokens(userText) > userMax) {
            userText = userText.take((userMax * 3.2).toInt() - 16) + " …[truncated]"; truncated = true
        }
        var remaining = budget - sysT - estTokens(userText)
        val keptMem = mutableListOf<String>()
        var droppedMem = 0
        for (m in memories) {
            val line = "- ${m.take(500)}"
            val c = estTokens(line) + 1
            if (c <= remaining) { keptMem += line; remaining -= c } else droppedMem++
        }
        val keptRecent = ArrayDeque<String>()
        var droppedRecent = 0
        for (r in recent.asReversed()) { // newest first survives
            val line = "- ${r.take(800)}"
            val c = estTokens(line) + 1
            if (c <= remaining) { keptRecent.addFirst(line); remaining -= c } else droppedRecent++
        }
        val sb = StringBuilder()
        if (keptMem.isNotEmpty()) sb.append("Owner notes:\n").append(keptMem.joinToString("\n")).append("\n\n")
        if (keptRecent.isNotEmpty()) sb.append("Context:\n").append(keptRecent.joinToString("\n")).append("\n\n")
        sb.append(userText)
        val prompt = sb.toString()
        return Built(system, prompt, sysT + estTokens(prompt) + TEMPLATE_OVERHEAD, droppedMem, droppedRecent, truncated)
    }
}
