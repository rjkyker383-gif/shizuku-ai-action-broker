package com.rjkyker.shizukuai.broker.autonomy.scheduler

import android.content.Context
import android.util.Log
import com.rjkyker.shizukuai.broker.autonomy.AutonomyFlags
import com.rjkyker.shizukuai.broker.autonomy.audit.AuditLog
import com.rjkyker.shizukuai.broker.autonomy.context.ContextManager
import com.rjkyker.shizukuai.broker.autonomy.delivery.SendGate
import com.rjkyker.shizukuai.broker.autonomy.eval.Verifier
import com.rjkyker.shizukuai.broker.autonomy.memory.MemoryStore
import com.rjkyker.shizukuai.broker.autonomy.planner.Planner
import com.rjkyker.shizukuai.broker.autonomy.queue.TaskEntity
import com.rjkyker.shizukuai.broker.autonomy.queue.TaskQueue
import com.rjkyker.shizukuai.broker.autonomy.queue.TaskState
import com.rjkyker.shizukuai.broker.autonomy.queue.TaskStateMachine
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.PermissionEngine
import com.rjkyker.shizukuai.broker.autonomy.router.ModelRouter
import com.rjkyker.shizukuai.broker.autonomy.watchdog.Watchdog
import com.rjkyker.shizukuai.broker.llm.LocalLlmService
import org.json.JSONArray
import org.json.JSONObject

/** Executes queued tasks as Actor.SCHEDULER (plan steps as Actor.PLANNER), each gated, verified and audited. */
object TaskRunner {
    private const val TAG = "TaskRunner"
    private const val MAX_PER_BATCH = 5
    private const val BUDGET_MS = 6 * 60_000L
    private val LLM_KINDS = setOf("llm_draft", "plan")
    private val lock = Any()

    data class StepResult(val ok: Boolean, val text: String = "", val json: JSONObject = JSONObject(),
                          val needsUser: Boolean = false, val retryable: Boolean = false, val error: String? = null)

    fun runBatch(ctx: Context): JSONObject = synchronized(lock) {
        val q = TaskQueue(ctx)
        val maint = q.maintain()
        val ran = JSONArray()
        val start = System.currentTimeMillis()
        var waitReason: String? = null
        while (ran.length() < MAX_PER_BATCH && System.currentTimeMillis() - start < BUDGET_MS) {
            val snap = AutonomyScheduler.snapshot(ctx)
            val gate = Conditions.evaluate(snap, needsLlm = false)
            if (gate is Conditions.Decision.Wait) { waitReason = gate.reason; break }
            val t = q.claim() ?: break
            if (t.kind in LLM_KINDS) {
                val g2 = Conditions.evaluate(snap, needsLlm = true)
                if (g2 is Conditions.Decision.Wait) {
                    q.transition(t.id, TaskState.QUEUED, "waiting: ${g2.reason}", notBefore = System.currentTimeMillis() + g2.retryMs)
                    AutonomyScheduler.kick(ctx, g2.retryMs)
                    waitReason = g2.reason; break
                }
            }
            ran.put(JSONObject().put("id", t.id).put("kind", t.kind).put("state", runOne(ctx, q, t).name))
        }
        JSONObject().put("ok", true).put("maintenance", maint).put("ran", ran).put("waiting", waitReason)
    }

    private fun runOne(ctx: Context, q: TaskQueue, t: TaskEntity): TaskState {
        val v = PermissionEngine.check(Actor.SCHEDULER, t.kind)
        val args = runCatching { JSONObject(t.argsJson) }.getOrDefault(JSONObject())
        AuditLog.record(ctx, Actor.SCHEDULER, t.kind, v, args, t.id)
        if (!v.allowed) { q.transition(t.id, TaskState.FAILED, error = "denied: ${v.reason}"); return TaskState.FAILED }
        val r = try { execute(ctx, t.kind, args, Actor.SCHEDULER, t.id) } catch (e: Exception) {
            Log.w(TAG, "task ${t.id} crashed", e); StepResult(false, error = "${e.javaClass.simpleName}: ${e.message}", retryable = true)
        }
        val result = JSONObject(r.json.toString()).put("text", r.text)
        val to = when {
            r.ok && r.needsUser -> TaskState.NEEDS_USER
            r.ok -> TaskState.SUCCEEDED
            else -> TaskStateMachine.afterFailure(t.attempts, t.maxAttempts, r.retryable)
        }
        val notBefore = if (to == TaskState.QUEUED) System.currentTimeMillis() + TaskStateMachine.backoffMs(t.attempts) else 0
        if (to == TaskState.SUCCEEDED || to == TaskState.NEEDS_USER) q.transition(t.id, TaskState.VERIFYING, "verified")
        q.transition(t.id, to, note = if (r.needsUser) "needs owner" else null, result = result, error = r.error, notBefore = notBefore)
        if (to == TaskState.QUEUED) AutonomyScheduler.kick(ctx, notBefore - System.currentTimeMillis())
        AuditLog.record(ctx, Actor.SCHEDULER.name, t.kind, v.tier.name, "RESULT", r.error, taskId = t.id, outcome = to.name)
        return to
    }

    fun execute(ctx: Context, kind: String, args: JSONObject, actor: Actor, taskId: Long?): StepResult = when (kind) {
        "noop" -> StepResult(true, "noop ok")
        "status_probe" -> Watchdog.probe(ctx).let { StepResult(true, Watchdog.summary(it), it) }
        "llm_draft" -> draft(ctx, args)
        "memory_propose" -> MemoryStore.add(ctx, args.optString("text"), actor).let {
            StepResult(it.optBoolean("ok"), it.optString("note"), it, error = it.optString("error", null))
        }
        "send_request" -> SendGate.request(ctx, args.optString("package"), args.optString("recipient"), args.optString("body"), actor).let {
            // pending -> owner must approve; rejected -> fail, never retry
            StepResult(it.optBoolean("ok"), it.optString("reason"), it, needsUser = it.optBoolean("ok"),
                error = if (it.optBoolean("ok")) null else it.optString("reason", it.optString("error")))
        }
        "plan" -> runPlan(ctx, args, taskId)
        else -> StepResult(false, error = "unknown task kind $kind")
    }

    fun draft(ctx: Context, args: JSONObject): StepResult {
        val prompt = args.optString("prompt")
        if (prompt.isBlank()) return StepResult(false, error = "prompt required")
        val maxNew = args.optInt("max_new_tokens", 128).coerceIn(1, LocalLlmService.MAX_NEW)
        val built = ContextManager.build(args.optString("system", LocalLlmService.DEFAULT_SYSTEM), prompt,
            MemoryStore.forPrompt(ctx), emptyList(), maxNew)
        val res = ModelRouter.generate(ctx, built.system, built.prompt, maxNew, args.optDouble("temperature", 0.7))
        if (!res.optBoolean("ok")) {
            val code = res.optString("code")
            return StepResult(false, json = res, error = res.optString("error"), retryable = code == "busy" || code == "thermal")
        }
        val text = res.optString("text").trim()
        val ver = Verifier.verifyDraft(text, prompt, args.optInt("max_chars", 1000))
        res.put("verifier", JSONObject().put("ok", ver.ok).put("issues", JSONArray(ver.issues)))
            .put("context", JSONObject().put("est_tokens", built.estTokens).put("dropped_memories", built.droppedMemories))
        res.remove("text")
        // A draft that fails rule checks is kept for the owner to review instead of flowing onward.
        return StepResult(true, text, res, needsUser = !ver.ok)
    }

    private fun runPlan(ctx: Context, args: JSONObject, taskId: Long?): StepResult {
        if (!AutonomyFlags.get(ctx, AutonomyFlags.PLANNER)) return StepResult(false, error = "planner_enabled=false")
        val goal = args.optString("goal")
        val plan = Planner.plan(goal) ?: return StepResult(false, error = "no template matches goal; nothing done")
        val steps = JSONArray()
        var prev: String? = null
        for ((i, s) in plan.steps.take(Planner.MAX_STEPS).withIndex()) {
            val v = PermissionEngine.check(Actor.PLANNER, s.kind)
            val a = Planner.substitute(s.args, prev)
            AuditLog.record(ctx, Actor.PLANNER, s.kind, v, a, taskId)
            if (!v.allowed) return StepResult(false, json = JSONObject().put("steps", steps), error = "step $i denied: ${v.reason}")
            val r = execute(ctx, s.kind, a, Actor.PLANNER, taskId)
            steps.put(JSONObject().put("kind", s.kind).put("ok", r.ok).put("needs_user", r.needsUser).put("text", r.text.take(1000)).put("error", r.error))
            if (!r.ok) return StepResult(false, json = JSONObject().put("template", plan.template).put("steps", steps), error = "step $i: ${r.error}", retryable = r.retryable)
            if (r.needsUser) return StepResult(true, r.text, JSONObject().put("template", plan.template).put("steps", steps), needsUser = true)
            prev = r.text
        }
        return StepResult(true, prev ?: "", JSONObject().put("template", plan.template).put("steps", steps))
    }
}
