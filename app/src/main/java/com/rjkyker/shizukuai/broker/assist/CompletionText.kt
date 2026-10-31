package com.rjkyker.shizukuai.broker.assist

/** Pure post-processing for model completions so the chip only ever shows a short, single-line continuation. */
object CompletionText {
    const val MAX_WORDS = 12
    const val MAX_CHARS = 90

    /**
     * @param before text before the cursor
     * @param raw model output (raw continuation or instruct reply)
     * @param rawContinuation true when [raw] is a literal continuation (leading space significant)
     */
    fun clean(before: String, raw: String, rawContinuation: Boolean): String {
        var s = raw.replace("\r", "")
        s = s.substringBefore("\n")
        if (!rawContinuation) {
            s = s.trim().trim('"', '\u201c', '\u201d', '\'', '`').trim()
            // instruct models sometimes echo the tail of the input
            val tail = before.takeLast(40).trim()
            if (tail.length >= 4 && s.startsWith(tail, ignoreCase = true)) s = s.substring(tail.length)
            s = s.trimStart()
            if (s.isNotEmpty() && before.isNotEmpty() && !before.last().isWhitespace() && s.first().isLetterOrDigit()) s = " $s"
        }
        // stop after the first sentence end
        val end = Regex("[.!?](\\s|$)").find(s)
        if (end != null) s = s.substring(0, end.range.first + 1)
        // word / char caps
        val words = s.split(Regex("(?<=\\S)(?=\\s)"))
        if (words.size > MAX_WORDS) s = words.take(MAX_WORDS).joinToString("")
        if (s.length > MAX_CHARS) s = s.take(MAX_CHARS).let { c -> c.substring(0, c.lastIndexOf(' ').takeIf { it > 0 } ?: c.length) }
        if (before.isNotEmpty() && before.last().isWhitespace()) s = s.trimStart()
        return s.trimEnd().takeIf { it.isNotBlank() } ?: ""
    }
}
