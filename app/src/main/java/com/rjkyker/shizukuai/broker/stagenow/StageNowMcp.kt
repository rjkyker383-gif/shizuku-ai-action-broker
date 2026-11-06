package com.rjkyker.shizukuai.broker.stagenow

import android.content.Context
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.PermissionEngine
import org.json.JSONArray
import org.json.JSONObject

object StageNowMcp {
    const val TOOL = "stagenow"
    val TOOLS = setOf(TOOL)
    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    fun call(ctx: Context, name: String, args: JSONObject, confirmed: Boolean): JSONObject {
        if (name != TOOL) return err("unknown $name")
        val v = PermissionEngine.check(Actor.MCP_AI, name, confirmed)
        if (!v.allowed) return err(v.reason)
        return when (args.optString("op", "status")) {
            "status" -> StageNowLauncher.status(ctx)
            "export" -> {
                val profile = args.optString("profile", "unlock_combined")
                // appmgr_disable is HIGH-impact: require confirm
                if (profile.startsWith("appmgr_") && !confirmed)
                    return err("appmgr_disable export needs confirm=true")
                StageNowLauncher.exportNamed(ctx, profile, args.optString("ssid").ifBlank { null }, args.optString("package").ifBlank { null })
            }
            "submit_emdk" -> {
                if (!confirmed) return err("submit_emdk needs confirm=true")
                val xml = args.optString("xml")
                val path = args.optString("path")
                val body = when {
                    xml.isNotBlank() -> xml
                    path.isNotBlank() -> runCatching { java.io.File(path).readText() }.getOrElse { return err("read failed: ${it.message}") }
                    else -> return err("xml or path required")
                }
                EmdkMxBridge.submitXml(ctx, body)
            }
            "launch" -> StageNowLauncher.launchStageNow(ctx)
            else -> err("op must be status|export|submit_emdk|launch")
        }
    }

    private fun tool(n: String, d: String, props: JSONObject, req: List<String> = emptyList()) =
        JSONObject().put("name", n).put("description", d)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", props)
                .also { if (req.isNotEmpty()) it.put("required", JSONArray(req)) })
    private fun p(t: String, d: String = "") = JSONObject().put("type", t).also { if (d.isNotEmpty()) it.put("description", d) }
    private fun e(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))

    fun definitions() = listOf(
        tool(TOOL, "StageNow / MX. op=status|export (profile=accessmgr_full|settingsmgr_enables|unlock_combined|appmgr_disable_osx|wifi_add_open)|submit_emdk (confirm; degrades if no EMDK)|launch. No license bypass. Premium MX omitted.",
            JSONObject().put("op", e("status", "export", "submit_emdk", "launch"))
                .put("profile", e("accessmgr_full", "settingsmgr_enables", "unlock_combined", "appmgr_disable_osx", "wifi_add_open"))
                .put("ssid", p("string")).put("package", p("string")).put("xml", p("string")).put("path", p("string"))
                .put("confirm", p("boolean")),
            listOf("op")),
    )
}
