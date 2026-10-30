package com.rjkyker.shizukuai.broker.autonomy

import android.content.Context
import com.rjkyker.shizukuai.broker.autonomy.audit.AuditLog
import com.rjkyker.shizukuai.broker.autonomy.delivery.SendGate
import com.rjkyker.shizukuai.broker.autonomy.memory.MemoryStore
import com.rjkyker.shizukuai.broker.autonomy.planner.Planner
import com.rjkyker.shizukuai.broker.autonomy.queue.TaskQueue
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.PermissionEngine
import com.rjkyker.shizukuai.broker.autonomy.router.ModelRouter
import com.rjkyker.shizukuai.broker.autonomy.scheduler.AutonomyScheduler
import com.rjkyker.shizukuai.broker.autonomy.scheduler.TaskRunner
import com.rjkyker.shizukuai.broker.llm.LlmClient
import com.rjkyker.shizukuai.broker.llm.ModelCatalog
import org.json.JSONArray
import org.json.JSONObject

/** 1.9.0 MCP surface. Every call is gated by PermissionEngine as Actor.MCP_AI. No approval paths exist here. */
object AutonomyMcp {
    const val LLM_STATUS = "llm_status"
    const val LLM_DRAFT = "llm_draft"
    const val LLM_MODEL = "llm_model"
    const val TASK_SUBMIT = "task_submit"
    const val TASK_STATUS = "task_status"
    const val TASK_CANCEL = "task_cancel"
    const val AUDIT = "autonomy_audit"
    const val CONTROL = "autonomy_control"
    const val MEMORY = "memory_control"
    const val SEND_REQUEST = "send_request"
    val TOOLS = setOf(LLM_STATUS, LLM_DRAFT, LLM_MODEL, TASK_SUBMIT, TASK_STATUS, TASK_CANCEL, AUDIT, CONTROL, MEMORY, SEND_REQUEST)
    private val TASK_KINDS = setOf("noop", "status_probe", "llm_draft", "plan", "memory_propose", "send_request")

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    fun call(ctx: Context, name: String, args: JSONObject, confirmed: Boolean): JSONObject {
        val high = (name == CONTROL && args.optString("op") == "enable") || (name == LLM_MODEL && args.optString("op") == "download")
        val v = PermissionEngine.check(Actor.MCP_AI, name, confirmed)
        if (!v.allowed) return err(v.reason)
        if (high && !confirmed) return err("$name op=${args.optString("op")} is HIGH; resend with confirm=true")
        return when (name) {
            LLM_STATUS -> LlmClient.status(ctx).put("route", ModelRouter.routeJson(ctx))
                .put("recent_runs", ModelRouter.recentRunsJson(ctx, 5)).put("flags", AutonomyFlags.toJson(ctx))
            LLM_DRAFT -> {
                if (!AutonomyFlags.get(ctx, AutonomyFlags.LLM)) return err("llm_enabled=false")
                val r = TaskRunner.draft(ctx, args)
                JSONObject(r.json.toString()).put("ok", r.ok).put("text", r.text).put("flagged", r.needsUser)
                    .put("error", r.error).put("note", "draft only; nothing was sent")
            }
            LLM_MODEL -> when (args.optString("op", "preflight")) {
                "preflight" -> LlmClient.preflight(ctx, args.optString("model", "").ifBlank { null })
                "download" -> {
                    val id = args.optString("model", ModelCatalog.ALL.first().id)
                    LlmClient.ensureModel(ctx, id)
                }
                "unload" -> LlmClient.unload(ctx)
                "cancel" -> { LlmClient.cancel(ctx); JSONObject().put("ok", true) }
                else -> err("llm_model op must be preflight|download|unload|cancel (delete is dashboard-only)")
            }
            TASK_SUBMIT -> {
                val kind = args.optString("kind")
                if (kind !in TASK_KINDS) return err("kind must be one of $TASK_KINDS")
                val sv = PermissionEngine.check(Actor.SCHEDULER, kind)
                if (!sv.allowed) return err("scheduler may not run $kind: ${sv.reason}")
                if (kind == "plan" && Planner.plan(args.optJSONObject("args")?.optString("goal") ?: "") == null)
                    return err("goal does not match a planner template (status report | draft: … | draft and send to <pkg>:<recipient>: …)")
                val id = try {
                    TaskQueue(ctx).submit(kind, args.optJSONObject("args") ?: JSONObject(), Actor.MCP_AI,
                        args.optString("idempotency_key", "").ifBlank { null }, args.optInt("priority", 0).coerceIn(-5, 5))
                } catch (e: TaskQueue.QueueFull) { return err(e.message ?: "queue full") }
                AutonomyScheduler.kick(ctx)
                JSONObject().put("ok", true).put("task_id", id).put("autonomy_enabled", AutonomyFlags.get(ctx, AutonomyFlags.AUTONOMY))
                    .put("note", if (AutonomyFlags.get(ctx, AutonomyFlags.AUTONOMY)) "queued; runner kicked" else "queued; will wait until autonomy_enabled=true")
            }
            TASK_STATUS -> {
                val q = TaskQueue(ctx)
                if (args.has("task_id")) q.get(args.optLong("task_id"))?.let { JSONObject().put("ok", true).put("task", q.toJson(it, true)) } ?: err("no such task")
                else q.recentJson(args.optInt("limit", 20))
            }
            TASK_CANCEL -> JSONObject().put("ok", TaskQueue(ctx).cancel(args.optLong("task_id", -1)))
            AUDIT -> AuditLog.recentJson(ctx, args.optInt("limit", 50))
                .put("outbox", SendGate.statusJson(ctx, 10).optJSONArray("outbox"))
            CONTROL -> when (args.optString("op", "status")) {
                "status" -> JSONObject().put("ok", true).put("flags", AutonomyFlags.toJson(ctx)).put("queue", TaskQueue(ctx).countsJson())
                "enable", "disable" -> {
                    val flag = args.optString("flag", AutonomyFlags.AUTONOMY)
                    val on = args.optString("op") == "enable"
                    if (flag !in AutonomyFlags.DEFAULTS) return err("unknown flag")
                    if (on && flag !in AutonomyFlags.MCP_ENABLEABLE) return err("$flag can only be turned on from the device dashboard")
                    AutonomyFlags.set(ctx, flag, on)
                    if (on) AutonomyScheduler.kick(ctx)
                    JSONObject().put("ok", true).put("flags", AutonomyFlags.toJson(ctx))
                }
                "stop_all" -> { AutonomyFlags.stopAll(ctx); LlmClient.cancel(ctx); JSONObject().put("ok", true).put("flags", AutonomyFlags.toJson(ctx)) }
                "run_now" -> { AutonomyScheduler.kick(ctx); JSONObject().put("ok", true).put("note", "runner kicked") }
                else -> err("op must be status|enable|disable|stop_all|run_now")
            }
            MEMORY -> when (args.optString("op", "list")) {
                "list" -> MemoryStore.exportJson(ctx)
                "propose" -> MemoryStore.add(ctx, args.optString("text"), Actor.MCP_AI)
                "delete" -> JSONObject().put("ok", MemoryStore.delete(ctx, args.optLong("id", -1)))
                else -> err("memory_control op must be list|propose|delete (accept/wipe are device-only)")
            }
            SEND_REQUEST -> SendGate.request(ctx, args.optString("package"), args.optString("recipient"), args.optString("body"), Actor.MCP_AI)
            else -> err("unknown")
        }
    }

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", props)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)
    }
    private fun p(t: String, d: String = "") = JSONObject().put("type", t).also { if (d.isNotEmpty()) it.put("description", d) }
    private fun e(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))

    fun definitions(): List<JSONObject> = listOf(
        tool(LLM_STATUS, "On-device LLM (llama.cpp CPU, isolated :llm process): models, download progress, route, recent tok/s + peak RSS.", JSONObject()),
        tool(LLM_DRAFT, "Generate a text draft on-device (Qwen2.5-0.5B → SmolLM2-360M; hosted off). Draft only: never sends. Rule-verified.",
            JSONObject().put("prompt", p("string")).put("system", p("string")).put("max_new_tokens", p("integer", "1-256"))
                .put("temperature", p("number")), listOf("prompt")),
        tool(LLM_MODEL, "op=preflight (storage/RAM/thermal/battery) | download (HIGH: confirm=true; pinned HF revision + SHA-256, app-private, refuses if preflight fails) | unload | cancel.",
            JSONObject().put("op", e("preflight", "download", "unload", "cancel")).put("model", e(*ModelCatalog.ALL.map { it.id }.toTypedArray()))
                .put("confirm", p("boolean")), listOf("op")),
        tool(TASK_SUBMIT, "Queue a durable task (Room). kind=noop|status_probe|llm_draft|plan|memory_propose|send_request. Runs only when autonomy_enabled and battery/thermal allow.",
            JSONObject().put("kind", e(*TASK_KINDS.toTypedArray())).put("args", JSONObject().put("type", "object"))
                .put("idempotency_key", p("string")).put("priority", p("integer")), listOf("kind")),
        tool(TASK_STATUS, "List recent tasks or one task (task_id) with its event history.", JSONObject().put("task_id", p("integer")).put("limit", p("integer"))),
        tool(TASK_CANCEL, "Cancel a queued/waiting/needs-user task.", JSONObject().put("task_id", p("integer")), listOf("task_id")),
        tool(AUDIT, "Audit trail (actor, tool, tier, decision, args SHA-256) + recent outbox states.", JSONObject().put("limit", p("integer"))),
        tool(CONTROL, "Autonomy flags. op=status|enable (HIGH: confirm=true; only autonomy/planner/llm)|disable|stop_all|run_now. Send/memory/hosted are device-only to enable.",
            JSONObject().put("op", e("status", "enable", "disable", "stop_all", "run_now")).put("flag", e(*AutonomyFlags.DEFAULTS.keys.toTypedArray()))
                .put("confirm", p("boolean")), listOf("op")),
        tool(MEMORY, "Owner memory. op=list|propose (stored as pending until accepted on device)|delete.",
            JSONObject().put("op", e("list", "propose", "delete")).put("text", p("string")).put("id", p("integer")), listOf("op")),
        tool(SEND_REQUEST, "Queue a message PREVIEW. Never sends: kill switch (default OFF), exact allowlists (default empty), content + rate rules, then per-message on-device approval. AI cannot approve.",
            JSONObject().put("package", p("string")).put("recipient", p("string")).put("body", p("string")), listOf("package", "recipient", "body")),
    )
}
