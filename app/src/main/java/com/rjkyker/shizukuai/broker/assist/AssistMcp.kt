package com.rjkyker.shizukuai.broker.assist

import android.content.Context
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.PermissionEngine
import org.json.JSONArray
import org.json.JSONObject

/** 1.9.1 type-anywhere MCP surface. Insert needs confirm=true, refuses password/OTP/card fields and never submits. */
object AssistMcp {
    const val READ = "input_focused_read"
    const val INSERT = "input_focused_insert"
    const val SUGGEST = "input_suggest"
    const val CONFIG = "input_assist_config"
    val TOOLS = setOf(READ, INSERT, SUGGEST, CONFIG)

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    fun call(ctx: Context, name: String, args: JSONObject, confirmed: Boolean): JSONObject {
        val v = PermissionEngine.check(Actor.MCP_AI, name, confirmed)
        if (!v.allowed) return err(v.reason)
        if (name == CONFIG) return config(ctx, args, confirmed)
        val svc = AgentAccessibilityService.instance ?: return err("accessibility service is off (Settings → Accessibility → broker)")
        val info = FocusedInput.read(svc) ?: return err("no focused editable field on screen")
        val meta = info.meta()
        return when (name) {
            READ -> {
                meta.put("ok", true).put("excluded", AssistSettings.isExcluded(ctx, info.pkg))
                if (confirmed && info.blockReason == null) meta.put("text", info.text).put("before", info.before)
                else meta.put("note", if (info.blockReason != null) "text withheld: ${info.blockReason}" else "text withheld; resend with confirm=true")
            }
            INSERT -> {
                if (!confirmed) return err("input_focused_insert is HIGH; resend with confirm=true")
                if (info.blockReason != null) return err("refused: ${info.blockReason}").put("package", info.pkg)
                if (AssistSettings.isExcluded(ctx, info.pkg)) return err("refused: ${info.pkg} is on the assist exclusion list").put("package", info.pkg)
                val text = args.optString("text", "")
                val mode = args.optString("mode", "append").let { if (it == "replace") "replace" else "append" }
                FocusedInput.insert(svc, text, mode).put("submitted", false).put("note", "inserted only; the owner presses send")
            }
            SUGGEST -> {
                if (info.blockReason != null) return err("refused: ${info.blockReason}").put("package", info.pkg)
                val force = args.optString("source", "auto")
                val r = CompletionRouter.complete(ctx, info.before, force)
                if (r.ok && args.optBoolean("show_chip", false)) AssistController.showChipFor(r)
                r.toJson().put("field", meta).put("note", "suggestion only; nothing inserted or sent")
            }
            else -> err("unknown $name")
        }
    }

    private fun config(ctx: Context, a: JSONObject, confirmed: Boolean): JSONObject {
        when (a.optString("op", "status")) {
            "status" -> {}
            "set" -> {
                val touchesRemote = a.has("remote_enabled") || a.has("remote_urls") || a.has("remote_token")
                if (touchesRemote && !confirmed) return err("remote settings need confirm=true (field text would leave the phone)")
                listOf("enabled", "auto_suggest", "floating_button", "remote_enabled").forEach { k -> if (a.has(k)) AssistSettings.setBool(ctx, k, a.getBoolean(k)) }
                if (a.has("remote_urls")) AssistSettings.setUrls(ctx, a.getString("remote_urls"))
                if (a.has("remote_token")) AssistSettings.setToken(ctx, a.getString("remote_token"))
                val ex = AssistSettings.excluded(ctx).toMutableSet()
                a.optJSONArray("exclude_add")?.let { arr -> for (i in 0 until arr.length()) ex += arr.getString(i) }
                a.optJSONArray("exclude_remove")?.let { arr -> for (i in 0 until arr.length()) ex -= arr.getString(i) }
                if (a.has("exclude_add") || a.has("exclude_remove")) AssistSettings.setExcluded(ctx, ex)
                AssistController.refreshBubbleAsync()
            }
            else -> return err("op must be status|set")
        }
        return JSONObject().put("ok", true).put("settings", AssistSettings.toJson(ctx)).put("route", CompletionRouter.statusJson(ctx))
            .put("a11y", AgentAccessibilityService.instance != null).put("bubble_shown", AssistController.bubbleShown()).put("last", AssistController.last?.toJson(includeText = false))
    }

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", props)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)
    }
    private fun p(t: String, d: String = "") = JSONObject().put("type", t).also { if (d.isNotEmpty()) it.put("description", d) }
    private fun e(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))

    fun definitions(): List<JSONObject> = listOf(
        tool(READ, "Focused text field in the front app: package, field hint, length, cursor, blocked (password/OTP/card). Text only with confirm=true and never for blocked fields.",
            JSONObject().put("confirm", p("boolean"))),
        tool(INSERT, "Insert text at the cursor of the focused field (mode=append) or replace it (mode=replace). HIGH: confirm=true. Refuses password/OTP/PIN/card fields and excluded apps. Never presses Enter/Send.",
            JSONObject().put("text", p("string", "max 2000 chars")).put("mode", e("append", "replace")).put("confirm", p("boolean")), listOf("text")),
        tool(SUGGEST, "Suggest the next words for the focused field. Router: on-device LLM → box server (opt-in, remote) → offline word predictor (not AI). Returns suggestion, source, latency. Inserts nothing; show_chip=true shows the on-phone chip.",
            JSONObject().put("source", e("auto", "local_llm", "box", "predictor")).put("show_chip", p("boolean"))),
        tool(CONFIG, "Type-anywhere settings. op=status|set. Remote settings (remote_enabled, remote_urls, remote_token) need confirm=true. Token is stored encrypted and never returned.",
            JSONObject().put("op", e("status", "set")).put("enabled", p("boolean")).put("auto_suggest", p("boolean"))
                .put("floating_button", p("boolean")).put("remote_enabled", p("boolean")).put("remote_urls", p("string", "comma-separated"))
                .put("remote_token", p("string")).put("exclude_add", JSONObject().put("type", "array").put("items", p("string")))
                .put("exclude_remove", JSONObject().put("type", "array").put("items", p("string"))).put("confirm", p("boolean")), listOf("op")),
    )
}
