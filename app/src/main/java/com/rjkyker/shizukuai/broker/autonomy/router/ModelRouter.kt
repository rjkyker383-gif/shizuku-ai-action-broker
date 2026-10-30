package com.rjkyker.shizukuai.broker.autonomy.router

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rjkyker.shizukuai.broker.autonomy.AutonomyFlags
import com.rjkyker.shizukuai.broker.autonomy.queue.AutonomyDb
import com.rjkyker.shizukuai.broker.autonomy.queue.ModelRunEntity
import com.rjkyker.shizukuai.broker.llm.LlmClient
import com.rjkyker.shizukuai.broker.llm.ModelCatalog
import com.rjkyker.shizukuai.broker.llm.ModelStore
import org.json.JSONArray
import org.json.JSONObject

/** U5 router: Qwen2.5-0.5B -> SmolLM2-360M -> hosted (opt-in; not wired in 1.9.0). Background thread only. */
object ModelRouter {
    const val HOSTED_ID = "hosted"

    fun backends(ctx: Context): List<RoutePolicy.Backend> =
        ModelCatalog.ALL.map {
            val present = ModelStore.isVerifiedPresent(ctx, it)
            RoutePolicy.Backend(it.id, local = true, available = present, why = if (present) "" else "not downloaded")
        } + RoutePolicy.Backend(HOSTED_ID, local = false, available = false, why = "hosted backend not configured in 1.9.0")

    fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    fun routeJson(ctx: Context): JSONObject {
        val r = RoutePolicy.route(backends(ctx), AutonomyFlags.get(ctx, AutonomyFlags.LLM),
            AutonomyFlags.get(ctx, AutonomyFlags.HOSTED), online(ctx))
        return JSONObject().put("order", JSONArray(r.order)).put("skipped", JSONObject(r.skipped as Map<*, *>))
    }

    fun generate(ctx: Context, system: String?, prompt: String, maxNew: Int = 128, temperature: Double = 0.7): JSONObject {
        val route = RoutePolicy.route(backends(ctx), AutonomyFlags.get(ctx, AutonomyFlags.LLM),
            AutonomyFlags.get(ctx, AutonomyFlags.HOSTED), online(ctx))
        val attempts = JSONArray()
        if (route.order.isEmpty()) {
            return JSONObject().put("ok", false).put("code", "no_backend")
                .put("error", "no model available: ${route.skipped}").put("skipped", JSONObject(route.skipped as Map<*, *>))
        }
        for (id in route.order) {
            if (id == HOSTED_ID) { attempts.put(JSONObject().put("backend", id).put("error", "not configured")); continue }
            val req = JSONObject().put("model", id).put("prompt", prompt).put("max_new_tokens", maxNew).put("temperature", temperature)
            if (!system.isNullOrBlank()) req.put("system", system)
            val res = LlmClient.generate(ctx, req)
            record(ctx, id, res)
            if (res.optBoolean("ok")) return res.put("route", JSONArray(route.order)).put("attempts", attempts)
            attempts.put(JSONObject().put("backend", id).put("code", res.optString("code")).put("error", res.optString("error")))
            if (!RoutePolicy.shouldFallback(res.optString("code", ""))) break
        }
        return JSONObject().put("ok", false).put("code", "all_failed").put("error", "every backend failed")
            .put("attempts", attempts).put("route", JSONArray(route.order))
    }

    private fun record(ctx: Context, id: String, r: JSONObject) {
        if (!r.has("gen_tokens")) return // nothing ran (missing model, busy, preflight refusal)
        runCatching {
            val dao = AutonomyDb.get(ctx).runs()
            dao.insert(ModelRunEntity(ts = System.currentTimeMillis(), model = id, backend = r.optString("backend", "llama.cpp-cpu"),
                promptTokens = r.optInt("prompt_tokens"), genTokens = r.optInt("gen_tokens"),
                decodeTps = r.optDouble("decode_tps", 0.0), prefillTps = r.optDouble("prefill_tps", 0.0),
                peakRssKb = r.optLong("peak_rss_kb", -1), ok = r.optBoolean("ok"), stop = r.optString("stop", r.optString("error"))))
            dao.trim(500)
        }
    }

    fun recentRunsJson(ctx: Context, limit: Int = 10): JSONArray = JSONArray().also { a ->
        AutonomyDb.get(ctx).runs().recent(limit).forEach {
            a.put(JSONObject().put("ts", it.ts).put("model", it.model).put("ok", it.ok).put("prompt_tokens", it.promptTokens)
                .put("gen_tokens", it.genTokens).put("decode_tps", it.decodeTps).put("prefill_tps", it.prefillTps)
                .put("peak_rss_kb", it.peakRssKb).put("stop", it.stop))
        }
    }
}
