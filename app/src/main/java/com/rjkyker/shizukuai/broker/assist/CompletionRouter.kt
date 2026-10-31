package com.rjkyker.shizukuai.broker.assist

import android.content.Context
import com.rjkyker.shizukuai.broker.autonomy.AutonomyFlags
import com.rjkyker.shizukuai.broker.autonomy.router.ModelRouter
import com.rjkyker.shizukuai.broker.llm.ModelCatalog
import com.rjkyker.shizukuai.broker.llm.ModelStore
import com.rjkyker.shizukuai.broker.thermal.ThermalPolicy
import com.rjkyker.shizukuai.broker.thermal.ThermalReader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sentence completion router. Order: on-device LLM (if a verified model is present + thermal OK)
 * -> box server (opt-in, shown with a REMOTE badge) -> offline word predictor (labelled "word prediction, not AI")
 * -> nothing, with the reasons. Never fakes a suggestion. Call off the main thread.
 */
object CompletionRouter {
    const val SRC_LOCAL = "local_llm"
    const val SRC_BOX = "box"
    const val SRC_PREDICTOR = "predictor"
    const val SRC_NONE = "none"

    data class Result(
        val ok: Boolean, val text: String, val source: String, val remote: Boolean, val label: String,
        val latencyMs: Long, val reasons: List<String>, val alternatives: List<String> = emptyList(), val model: String = "",
    ) {
        fun toJson(includeText: Boolean = true): JSONObject = JSONObject().put("ok", ok).put("source", source).put("remote", remote)
            .put("label", label).put("latency_ms", latencyMs).put("len", text.length).put("model", model)
            .put("skipped", JSONArray(reasons)).also { if (includeText) it.put("suggestion", text).put("alternatives", JSONArray(alternatives)) }
    }

    /** Pure route plan (for tests + status): which sources are eligible and why the others are skipped. */
    fun plan(localPresent: Boolean, llmEnabled: Boolean, thermalStop: Boolean, remoteEnabled: Boolean, tokenSet: Boolean,
             predictorLoaded: Boolean, force: String? = null): Pair<List<String>, List<String>> {
        val order = ArrayList<String>(); val why = ArrayList<String>()
        fun want(s: String) = force == null || force == "auto" || force == s
        if (want(SRC_LOCAL)) when {
            !llmEnabled -> why += "local_llm: llm_enabled=false"
            !localPresent -> why += "local_llm: no on-phone model downloaded"
            thermalStop -> why += "local_llm: thermal STOP"
            else -> order += SRC_LOCAL
        }
        if (want(SRC_BOX)) when {
            !remoteEnabled -> why += "box: remote completion is off (opt-in)"
            !tokenSet -> why += "box: no remote token set"
            else -> order += SRC_BOX
        }
        if (want(SRC_PREDICTOR)) { if (predictorLoaded) order += SRC_PREDICTOR else why += "predictor: asset failed to load" }
        return order to why
    }

    fun localPresent(ctx: Context) = ModelCatalog.ALL.any { ModelStore.isVerifiedPresent(ctx, it) }

    fun statusJson(ctx: Context): JSONObject {
        val (order, why) = plan(localPresent(ctx), AutonomyFlags.get(ctx, AutonomyFlags.LLM), false,
            AssistSettings.remoteEnabled(ctx), AssistSettings.hasToken(ctx), true)
        return JSONObject().put("order", JSONArray(order)).put("skipped", JSONArray(why))
    }

    fun complete(ctx: Context, before: String, force: String? = null): Result {
        val t0 = System.currentTimeMillis()
        if (before.isBlank()) return Result(false, "", SRC_NONE, false, "", 0, listOf("field is empty: type a few words first"))
        val thermalStop = runCatching { ThermalReader.verdict(ctx).level == ThermalPolicy.Level.STOP }.getOrDefault(false)
        val predictor = Predictors.get(ctx)
        val (order, why) = plan(localPresent(ctx), AutonomyFlags.get(ctx, AutonomyFlags.LLM), thermalStop,
            AssistSettings.remoteEnabled(ctx), AssistSettings.hasToken(ctx), predictor != null, force)
        val reasons = ArrayList(why)
        for (src in order) {
            val ts = System.currentTimeMillis()
            when (src) {
                SRC_LOCAL -> {
                    val r = ModelRouter.generate(ctx,
                        "Continue the user's text with the next few words only (max 10 words). Output only the continuation.",
                        before.takeLast(600), maxNew = 24, temperature = 0.3)
                    val txt = if (r.optBoolean("ok")) CompletionText.clean(before, r.optString("text"), rawContinuation = false) else ""
                    if (txt.isNotBlank()) return Result(true, txt, SRC_LOCAL, false, "AI · on-device", System.currentTimeMillis() - ts, reasons,
                        model = r.optString("model"))
                    reasons += "local_llm: " + (r.optString("error").ifBlank { "empty completion" }).take(80)
                }
                SRC_BOX -> {
                    val r = BoxCompletionClient.complete(ctx, before)
                    val txt = if (r.ok) CompletionText.clean(before, r.completion, rawContinuation = true) else ""
                    if (txt.isNotBlank()) return Result(true, txt, SRC_BOX, true, "AI · REMOTE (box)", r.ms, reasons, model = r.model)
                    reasons += "box: " + (r.error ?: "empty completion").take(80)
                }
                SRC_PREDICTOR -> {
                    val pr = predictor?.predict(before)
                    if (pr != null && pr.insert.isNotBlank()) return Result(true, pr.insert, SRC_PREDICTOR, false,
                        "word prediction, not AI", System.currentTimeMillis() - ts, reasons, pr.alternatives)
                    reasons += "predictor: no confident prediction for this text"
                }
            }
        }
        return Result(false, "", SRC_NONE, false, "", System.currentTimeMillis() - t0, reasons)
    }
}
