package com.rjkyker.shizukuai.broker.policy

import android.content.Context
import com.rjkyker.shizukuai.broker.assist.AssistSettings
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.PermissionEngine
import org.json.JSONArray
import org.json.JSONObject

/** 1.10.0 MCP: read/set soft broker policies. Locked denylist is read-only. */
object PoliciesMcp {
    const val TOOL = "broker_policies"
    val TOOLS = setOf(TOOL)

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    fun call(ctx: Context, name: String, args: JSONObject, confirmed: Boolean): JSONObject {
        if (name != TOOL) return err("unknown $name")
        val v = PermissionEngine.check(Actor.MCP_AI, name, confirmed)
        if (!v.allowed) return err(v.reason)
        return when (args.optString("op", "status")) {
            "status", "get" -> JSONObject().put("ok", true).put("policies", BrokerPolicies.toJson(ctx))
            "set" -> {
                val key = args.optString("key")
                if (key.isBlank() || !args.has("value")) return err("key and value required")
                if (!confirmed) return err("broker_policies set needs confirm=true")
                val refuse = BrokerPolicies.set(ctx, key, args.getBoolean("value"))
                if (refuse != null) err(refuse) else JSONObject().put("ok", true).put("policies", BrokerPolicies.toJson(ctx))
            }
            "set_hosts" -> {
                if (!confirmed) return err("set_hosts needs confirm=true")
                val hosts = mutableSetOf<String>()
                args.optJSONArray("hosts")?.let { a -> for (i in 0 until a.length()) hosts += a.getString(i) }
                val refuse = BrokerPolicies.setHostAllowlist(ctx, hosts)
                if (refuse != null) err(refuse) else JSONObject().put("ok", true).put("mcp_hosts", JSONArray(BrokerPolicies.hostAllowlist(ctx).sorted()))
            }
            "exclude_app" -> {
                val pkg = args.optString("package")
                if (pkg.isBlank()) return err("package required")
                val ex = AssistSettings.excluded(ctx).toMutableSet()
                if (args.optBoolean("exclude", true)) ex += pkg else ex -= pkg
                AssistSettings.setExcluded(ctx, ex)
                JSONObject().put("ok", true).put("excluded", JSONArray(ex.sorted()))
            }
            else -> err("op must be status|set|set_hosts|exclude_app")
        }
    }

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", props)
                .also { if (required.isNotEmpty()) it.put("required", JSONArray(required)) })
    private fun p(t: String, d: String = "") = JSONObject().put("type", t).also { if (d.isNotEmpty()) it.put("description", d) }
    private fun e(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))

    fun definitions() = listOf(
        tool(TOOL, "Broker policies. op=status (toggles + locked denylist view)|set (confirm=true; cannot disable skip_sensitive_fields or preview_first_messaging)|set_hosts|exclude_app. Hard denylist is locked.",
            JSONObject().put("op", e("status", "set", "set_hosts", "exclude_app"))
                .put("key", e(*BrokerPolicies.TOGGLE_DEFAULTS.keys.toTypedArray()))
                .put("value", p("boolean")).put("hosts", JSONObject().put("type", "array").put("items", p("string")))
                .put("package", p("string")).put("exclude", p("boolean")).put("confirm", p("boolean")),
            listOf("op")),
    )
}
