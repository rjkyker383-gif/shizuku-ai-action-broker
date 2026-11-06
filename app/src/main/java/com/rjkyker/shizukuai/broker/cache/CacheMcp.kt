package com.rjkyker.shizukuai.broker.cache

import android.content.Context
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.PermissionEngine
import org.json.JSONArray
import org.json.JSONObject

object CacheMcp {
    const val TOOL = "broker_cache"
    val TOOLS = setOf(TOOL)
    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    fun call(ctx: Context, name: String, args: JSONObject, confirmed: Boolean): JSONObject {
        if (name != TOOL) return err("unknown $name")
        val v = PermissionEngine.check(Actor.MCP_AI, name, confirmed)
        if (!v.allowed) return err(v.reason)
        return when (args.optString("op", "status")) {
            "status" -> CacheCleaner.status(ctx)
            "clear_own" -> {
                if (!confirmed) return err("clear_own needs confirm=true")
                CacheCleaner.clearOwn(ctx)
            }
            "trim_system" -> {
                if (!confirmed) return err("trim_system needs confirm=true (pm trim-caches; other apps' caches only)")
                CacheCleaner.trimSystem(args.optString("size", "2G"))
            }
            else -> err("op must be status|clear_own|trim_system")
        }
    }

    private fun tool(n: String, d: String, props: JSONObject, req: List<String> = emptyList()) =
        JSONObject().put("name", n).put("description", d)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", props)
                .also { if (req.isNotEmpty()) it.put("required", JSONArray(req)) })
    private fun p(t: String, d: String = "") = JSONObject().put("type", t).also { if (d.isNotEmpty()) it.put("description", d) }
    private fun e(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))

    fun definitions() = listOf(
        tool(TOOL, "Broker cache hygiene. op=status|clear_own (confirm=true; broker cacheDir only)|trim_system (confirm=true; pm trim-caches via Shizuku, other apps' caches). Never pm clear, never deletes user files.",
            JSONObject().put("op", e("status", "clear_own", "trim_system")).put("size", p("string", "e.g. 2G")).put("confirm", p("boolean")),
            listOf("op")),
    )
}
