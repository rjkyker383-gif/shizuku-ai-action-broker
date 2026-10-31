package com.rjkyker.shizukuai.broker.assist

import kotlin.random.Random

/**
 * Pure helpers for typewriter mode: sanitize, chunk, escape and validate `input text` commands.
 * The ONLY shell command typewriter mode may run is `input text <escaped printable ASCII>` (no keyevent/tap/swipe,
 * no ENTER, no newline).
 */
object TypeCommand {
    /** Shell-special characters that get a backslash. Everything else printable is passed literally. */
    private const val SHELL_SPECIAL = "()<>|;&*\\'\"`\$~!#?[]{}^="

    /** Newlines/tabs/CR -> single space; strips other control chars. Never produces a newline. */
    fun sanitize(text: String): String =
        text.replace(Regex("[\\r\\n\\t\\u2028\\u2029]+"), " ").filter { it == ' ' || !it.isISOControl() }

    fun isTypeable(c: Char) = c == ' ' || c.code in 0x21..0x7e

    /** Escape one chunk for `input text` run through `sh -c`: space -> %s, shell specials -> \c. Null if not typeable. */
    fun escape(chunk: String): String? {
        if (chunk.isEmpty() || !chunk.all(::isTypeable)) return null
        val sb = StringBuilder()
        for (c in chunk) when {
            c == ' ' -> sb.append("%s")
            c in SHELL_SPECIAL -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private val ALLOWED = Regex("""^input text (?:%s|\\[!-~]|[A-Za-z0-9.,:/@_+%-])+$""")

    /** Narrow allowlist: exactly `input text <escaped>`; nothing else may pass. */
    fun command(chunk: String): String? = escape(chunk)?.let { "input text $it" }?.takeIf { ALLOWED.matches(it) }
    fun isAllowed(cmd: String) = ALLOWED.matches(cmd)

    /**
     * Split into 1-3 char chunks. Runs of non-typeable chars (emoji, accents) form their own chunk so they can go
     * through accessibility paste instead.
     */
    fun chunks(text: String, rnd: Random = Random.Default): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            if (!isTypeable(text[i])) {
                var j = i; while (j < text.length && !isTypeable(text[j])) j++
                out += text.substring(i, j); i = j; continue
            }
            var n = 1 + rnd.nextInt(3)
            var j = i
            while (j < text.length && j - i < n && isTypeable(text[j])) { j++; if (text[j - 1] in ".,!?;:") break }
            out += text.substring(i, j); i = j
        }
        return out
    }

    /**
     * Humanlike gap after a chunk. speed 0.25..3 (1 = normal): base 35-90 ms, pause after punctuation,
     * slightly longer after a space.
     */
    fun delayMs(chunk: String, speed: Double, rnd: Random = Random.Default): Long {
        val s = speed.coerceIn(0.25, 3.0)
        var d = 35 + rnd.nextInt(56).toDouble()
        val last = chunk.lastOrNull()
        if (last != null && last in ".!?") d += 180 + rnd.nextInt(140)
        else if (last != null && last in ",;:") d += 90 + rnd.nextInt(80)
        else if (last == ' ') d += rnd.nextInt(40)
        return (d / s).toLong().coerceAtLeast(10)
    }
}
