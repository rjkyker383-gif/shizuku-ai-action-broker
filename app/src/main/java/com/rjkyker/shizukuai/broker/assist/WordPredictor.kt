package com.rjkyker.shizukuai.broker.assist

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Offline word prediction (NOT AI): completes the current word and proposes the next 1-3 words from a bundled
 * n-gram table (assets/predict/en_model.tsv.gz, ~0.5 MB; FrequencyWords CC-BY-SA-4.0 unigrams + public-domain
 * Gutenberg bigrams/trigrams). Optional per-user learning from accepted suggestions (only when memory is on).
 */
class WordPredictor private constructor(
    private val uni: HashMap<String, Int>,
    private val sorted: Array<String>,
    private val big: HashMap<String, MutableList<Pair<String, Int>>>,
    private val tri: HashMap<String, MutableList<Pair<String, Int>>>,
) {
    data class Prediction(val insert: String, val wordCompletion: String, val nextWords: List<String>, val alternatives: List<String>)

    private val userBig = HashMap<String, HashMap<String, Int>>()

    fun learn(text: String) {
        val t = tokens(text.lowercase())
        for (i in 0 until t.size - 1) userBig.getOrPut(t[i]) { HashMap() }.merge(t[i + 1], 1, Int::plus)
    }

    fun exportUser(): String = buildString { userBig.forEach { (a, m) -> m.forEach { (b, c) -> append("$a\t$b\t$c\n") } } }
    fun importUser(s: String) = s.lineSequence().forEach { l ->
        val p = l.split('\t'); if (p.size == 3) userBig.getOrPut(p[0]) { HashMap() }[p[1]] = p[2].toIntOrNull() ?: 0
    }
    fun clearUser() = userBig.clear()

    private fun nextCandidates(prev2: String?, prev1: String?): List<String> {
        val scored = HashMap<String, Double>()
        if (prev1 != null) {
            big[prev1]?.forEach { (w, c) -> scored.merge(w, c.toDouble(), Double::plus) }
            userBig[prev1]?.forEach { (w, c) -> scored.merge(w, 2e6 + c * 50.0, Double::plus) }
            // back-off: any trigram continuation outranks every bigram-only one
            if (prev2 != null) tri["$prev2 $prev1"]?.forEach { (w, c) -> scored.merge(w, 1e6 + c * 10.0, Double::plus) }
        }
        return scored.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun prefixBest(prefix: String, limit: Int): List<String> {
        var lo = sorted.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        val out = ArrayList<Pair<String, Int>>()
        while (lo < sorted.size && sorted[lo].startsWith(prefix)) { out += sorted[lo] to (uni[sorted[lo]] ?: 0); lo++ }
        return out.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    fun predict(before: String, maxNext: Int = 3): Prediction? {
        val tail = before.takeLast(200)
        if (tail.isBlank()) return null
        val sentenceStart = tail.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
        val sentence = if (sentenceStart >= 0) tail.substring(sentenceStart + 1) else tail
        val partialMatch = Regex("([A-Za-z']+)$").find(tail)
        val partialRaw = partialMatch?.value ?: ""
        val partial = partialRaw.lowercase()
        val ctxTokens = tokens((if (partialRaw.isNotEmpty()) sentence.dropLast(partialRaw.length.coerceAtMost(sentence.length)) else sentence).lowercase())
        var p2 = ctxTokens.getOrNull(ctxTokens.size - 2)
        var p1 = ctxTokens.lastOrNull()
        val alternatives: List<String>
        var completedWord: String
        if (partial.isNotEmpty()) {
            val ctxHits = nextCandidates(p2, p1).filter { it.startsWith(partial) && it != partial }
            val freqHits = prefixBest(partial, 6).filter { it != partial }
            val cands = (ctxHits + freqHits).distinct()
            alternatives = cands.take(3)
            completedWord = when {
                partial in uni && (cands.isEmpty() || (uni[partial] ?: 0) >= (uni[cands.first()] ?: 0)) -> partial
                cands.isNotEmpty() -> cands.first()
                else -> partial
            }
            p2 = p1; p1 = completedWord
        } else {
            completedWord = ""
            alternatives = nextCandidates(p2, p1).take(3)
        }
        val next = ArrayList<String>()
        if (p1 != null) {
            var a = p2; var b: String = p1
            repeat(maxNext) {
                val n = nextCandidates(a, b).firstOrNull { it !in next && it != b } ?: return@repeat
                next += n; a = b; b = n
            }
        }
        if (completedWord.length <= partial.length && next.isEmpty()) return null
        val atSentenceStart = ctxTokens.isEmpty() && partial.isEmpty()
        val wordRest = if (completedWord.length > partial.length) completedWord.substring(partial.length) else ""
        val sep = when {
            partial.isNotEmpty() -> " "
            tail.last().isWhitespace() -> ""
            else -> " "
        }
        val nextFmt = next.mapIndexed { i, w -> fixCase(w, capitalize = atSentenceStart && i == 0) }
        val restFmt = when {
            partialRaw.length > 1 && partialRaw.all { it.isUpperCase() || it == '\'' } -> wordRest.uppercase()
            else -> wordRest
        }
        val insert = buildString {
            append(restFmt)
            if (nextFmt.isNotEmpty()) { append(sep); append(nextFmt.joinToString(" ")) }
        }
        if (insert.isBlank()) return null
        return Prediction(insert, wordRest, nextFmt, alternatives.map { fixCase(it, false) })
    }

    companion object {
        private val WORD = Regex("[a-z]+(?:'[a-z]+)?")
        fun tokens(s: String): List<String> = WORD.findAll(s).map { it.value }.toList()

        fun fixCase(w: String, capitalize: Boolean): String = when {
            w == "i" -> "I"
            w.startsWith("i'") -> "I" + w.substring(1)
            capitalize -> w.replaceFirstChar { it.uppercase() }
            else -> w
        }

        fun load(input: InputStream, gz: Boolean = true): WordPredictor {
            val uni = HashMap<String, Int>(40_000)
            val big = HashMap<String, MutableList<Pair<String, Int>>>(20_000)
            val tri = HashMap<String, MutableList<Pair<String, Int>>>(30_000)
            BufferedReader(InputStreamReader(if (gz) GZIPInputStream(input) else input, Charsets.UTF_8)).useLines { lines ->
                for (l in lines) {
                    if (l.isEmpty() || l[0] == '#') continue
                    val p = l.split('\t')
                    when (p[0]) {
                        "U" -> if (p.size == 3) uni[p[1]] = p[2].toInt()
                        "B" -> if (p.size == 4) big.getOrPut(p[1]) { ArrayList(4) } += p[2] to p[3].toInt()
                        "T" -> if (p.size == 5) tri.getOrPut("${p[1]} ${p[2]}") { ArrayList(2) } += p[3] to p[4].toInt()
                    }
                }
            }
            return WordPredictor(uni, uni.keys.sorted().toTypedArray(), big, tri)
        }
    }
}
