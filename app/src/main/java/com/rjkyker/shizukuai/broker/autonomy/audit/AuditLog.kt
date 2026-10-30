package com.rjkyker.shizukuai.broker.autonomy.audit

import android.content.Context
import android.util.Log
import com.rjkyker.shizukuai.broker.autonomy.queue.AuditEntity
import com.rjkyker.shizukuai.broker.autonomy.queue.AutonomyDb
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.Verdict
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

/** U9 append-only audit trail (Room `audit`, capped at [KEEP] rows). Stores arg hashes, never raw args. */
object AuditLog {
    const val KEEP = 5000
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "audit-log").apply { isDaemon = true } }
    @Volatile private var writes = 0

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Canonical hash: keys sorted, confirm flag excluded. */
    fun argsHash(args: JSONObject?): String? {
        if (args == null) return null
        val keys = args.keys().asSequence().filter { it != "confirm" }.sorted().toList()
        val canon = JSONObject().also { o -> keys.forEach { o.put(it, args.opt(it)) } }
        return sha256(canon.toString())
    }

    fun record(ctx: Context, actor: Actor, tool: String, verdict: Verdict, args: JSONObject? = null,
               taskId: Long? = null, outcome: String? = null) =
        record(ctx, actor.name, tool, verdict.tier.name, verdict.decision, verdict.reason, args, taskId, outcome)

    fun record(ctx: Context, actor: String, tool: String, tier: String, decision: String, reason: String?,
               args: JSONObject? = null, taskId: Long? = null, outcome: String? = null) {
        val app = ctx.applicationContext
        val hash = runCatching { argsHash(args) }.getOrNull()
        val e = AuditEntity(ts = System.currentTimeMillis(), actor = actor, tool = tool, tier = tier,
            decision = decision, reason = reason?.take(300), argsHash = hash, taskId = taskId, outcome = outcome?.take(300))
        io.execute {
            runCatching {
                val dao = AutonomyDb.get(app).audit()
                dao.insert(e)
                if (++writes % 200 == 0) dao.trim(KEEP)
            }.onFailure { Log.w("AuditLog", "write failed: ${it.message}") }
        }
    }

    fun recentJson(ctx: Context, limit: Int): JSONObject {
        val rows = AutonomyDb.get(ctx).audit().recent(limit.coerceIn(1, 500))
        val arr = JSONArray()
        rows.forEach {
            arr.put(JSONObject().put("id", it.id).put("ts", it.ts).put("actor", it.actor).put("tool", it.tool)
                .put("tier", it.tier).put("decision", it.decision).put("reason", it.reason)
                .put("args_sha256", it.argsHash).put("task_id", it.taskId).put("outcome", it.outcome))
        }
        return JSONObject().put("ok", true).put("count", AutonomyDb.get(ctx).audit().count()).put("entries", arr)
    }
}
