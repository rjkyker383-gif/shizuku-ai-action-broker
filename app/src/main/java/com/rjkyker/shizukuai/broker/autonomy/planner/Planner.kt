package com.rjkyker.shizukuai.broker.autonomy.planner

import org.json.JSONObject

/**
 * U1 planner (1.9.0 = deterministic templates, no LLM tool-calling). A goal maps to an ordered list of
 * registry steps; "{prev}" in a step arg is replaced with the previous step's text output at run time.
 * Unknown goals are rejected rather than improvised.
 */
object Planner {
    data class Step(val kind: String, val args: JSONObject)
    data class Plan(val template: String, val steps: List<Step>)

    const val MAX_STEPS = 5
    private val SEND_RE = Regex("""^(?:draft and send|send)\s+to\s+([a-zA-Z0-9_.]+)\s*:\s*([^:]{1,80})\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val DRAFT_RE = Regex("""^(?:draft|write|rewrite|reply)\b[:\s]*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun plan(goal: String): Plan? {
        val g = goal.trim()
        if (g.isEmpty() || g.length > 2000) return null
        val lower = g.lowercase()
        SEND_RE.matchEntire(g)?.let { m ->
            val (pkg, recipient, what) = m.destructured
            return Plan("draft_and_send", listOf(
                Step("llm_draft", JSONObject().put("prompt", "Write a short message: $what").put("max_new_tokens", 96)),
                Step("send_request", JSONObject().put("package", pkg).put("recipient", recipient.trim()).put("body", "{prev}")),
            ))
        }
        if (lower.startsWith("status report") || lower.startsWith("summarize status") || lower == "status") {
            return Plan("status_report", listOf(
                Step("status_probe", JSONObject()),
                Step("llm_draft", JSONObject().put("prompt",
                    "Summarize this phone status for the owner in 3 short bullet points:\n{prev}").put("max_new_tokens", 120)),
            ))
        }
        DRAFT_RE.matchEntire(g)?.let { m ->
            return Plan("draft", listOf(Step("llm_draft", JSONObject().put("prompt", m.groupValues[1].trim()).put("max_new_tokens", 160))))
        }
        if (lower == "noop" || lower == "selftest") return Plan("noop", listOf(Step("noop", JSONObject())))
        return null
    }

    fun substitute(args: JSONObject, prev: String?): JSONObject {
        val out = JSONObject(args.toString())
        for (k in out.keys().asSequence().toList()) {
            val v = out.opt(k)
            if (v is String && v.contains("{prev}")) out.put(k, v.replace("{prev}", prev ?: ""))
        }
        return out
    }
}
