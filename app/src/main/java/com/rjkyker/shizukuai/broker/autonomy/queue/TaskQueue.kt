package com.rjkyker.shizukuai.broker.autonomy.queue

import android.content.Context
import androidx.room.withTransaction
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * U2 durable task queue over Room. Survives process death and reboot; RUNNING tasks whose lease expired
 * (process killed mid-run) go back to QUEUED. Every state change is validated by [TaskStateMachine] and
 * written to task_events. Idempotency key de-duplicates resubmits.
 */
class TaskQueue(ctx: Context) {
    private val db = AutonomyDb.get(ctx)
    private val dao = db.tasks()

    class QueueFull(msg: String) : Exception(msg)

    fun submit(kind: String, args: JSONObject, actor: Actor, idemKey: String? = null, priority: Int = 0,
               ttlMs: Long = DEFAULT_TTL_MS, maxAttempts: Int = 3, parentId: Long? = null): Long = tx {
        if (idemKey != null) dao.byIdem(idemKey)?.let { return@tx it.id }
        if (dao.activeCount() >= MAX_ACTIVE) throw QueueFull("queue full ($MAX_ACTIVE active tasks)")
        val now = System.currentTimeMillis()
        val id = dao.insert(TaskEntity(kind = kind, argsJson = args.toString().take(16_000), actor = actor.name,
            state = TaskState.QUEUED.name, priority = priority, maxAttempts = maxAttempts.coerceIn(1, 5), idemKey = idemKey,
            parentId = parentId, expiresAt = if (ttlMs > 0) now + ttlMs else 0, createdAt = now, updatedAt = now))
        dao.event(TaskEventEntity(taskId = id, fromState = null, toState = TaskState.QUEUED.name, note = "submitted by ${actor.name}", ts = now))
        id
    }

    /** Atomically lease the next runnable task and mark it RUNNING. */
    fun claim(leaseMs: Long = DEFAULT_LEASE_MS): TaskEntity? = tx {
        val now = System.currentTimeMillis()
        val t = dao.nextRunnable(now) ?: return@tx null
        val from = TaskState.valueOf(t.state)
        if (!TaskStateMachine.canTransition(from, TaskState.RUNNING)) return@tx null
        val n = t.copy(state = TaskState.RUNNING.name, attempts = t.attempts + 1, leaseUntil = now + leaseMs, updatedAt = now)
        dao.update(n)
        dao.event(TaskEventEntity(taskId = t.id, fromState = from.name, toState = TaskState.RUNNING.name, note = "attempt ${n.attempts}", ts = now))
        n
    }

    fun transition(id: Long, to: TaskState, note: String? = null, result: JSONObject? = null, error: String? = null,
                   notBefore: Long = 0): Boolean = tx {
        val t = dao.get(id) ?: return@tx false
        val from = TaskState.valueOf(t.state)
        if (!TaskStateMachine.canTransition(from, to)) return@tx false
        val now = System.currentTimeMillis()
        dao.update(t.copy(state = to.name, updatedAt = now, leaseUntil = 0, notBefore = notBefore,
            resultJson = result?.toString()?.take(16_000) ?: t.resultJson, error = error?.take(500) ?: t.error))
        dao.event(TaskEventEntity(taskId = id, fromState = from.name, toState = to.name, note = (note ?: error)?.take(300), ts = now))
        true
    }

    fun cancel(id: Long): Boolean = transition(id, TaskState.CANCELLED, "cancelled")

    /** Crash/reboot recovery + TTL expiry + pruning. Returns counts. */
    fun maintain(): JSONObject = tx {
        val now = System.currentTimeMillis()
        var recovered = 0; var expired = 0
        for (t in dao.expiredLeases(now)) {
            val to = TaskStateMachine.afterFailure(t.attempts, t.maxAttempts, retryable = true)
            dao.update(t.copy(state = to.name, leaseUntil = 0, updatedAt = now, error = "lease expired (process died?)"))
            dao.event(TaskEventEntity(taskId = t.id, fromState = t.state, toState = to.name, note = "lease expired", ts = now))
            recovered++
        }
        for (t in dao.expired(now)) {
            dao.update(t.copy(state = TaskState.EXPIRED.name, updatedAt = now))
            dao.event(TaskEventEntity(taskId = t.id, fromState = t.state, toState = TaskState.EXPIRED.name, note = "ttl", ts = now))
            expired++
        }
        val pruned = dao.pruneDone(now - 7L * 24 * 3600_000)
        if (pruned > 0) dao.pruneOrphanEvents()
        JSONObject().put("recovered", recovered).put("expired", expired).put("pruned", pruned)
    }

    fun get(id: Long): TaskEntity? = dao.get(id)

    fun countsJson(): JSONObject = JSONObject().also { o -> dao.counts().forEach { o.put(it.state, it.n) } }

    fun toJson(t: TaskEntity, withEvents: Boolean = false): JSONObject = JSONObject().put("id", t.id).put("kind", t.kind)
        .put("state", t.state).put("actor", t.actor).put("attempts", t.attempts).put("max_attempts", t.maxAttempts)
        .put("created_at", t.createdAt).put("updated_at", t.updatedAt).put("not_before", t.notBefore)
        .put("error", t.error).put("result", t.resultJson?.let { runCatching { JSONObject(it) }.getOrNull() })
        .also { o ->
            if (withEvents) o.put("events", JSONArray().also { a ->
                dao.events(t.id).forEach { e -> a.put(JSONObject().put("from", e.fromState).put("to", e.toState).put("note", e.note).put("ts", e.ts)) }
            })
        }

    fun recentJson(limit: Int): JSONObject = JSONObject().put("ok", true).put("counts", countsJson())
        .put("tasks", JSONArray().also { a -> dao.recent(limit.coerceIn(1, 200)).forEach { a.put(toJson(it)) } })

    private fun <T> tx(block: suspend () -> T): T = runBlocking { db.withTransaction { block() } }

    companion object {
        const val MAX_ACTIVE = 50
        const val DEFAULT_TTL_MS = 24 * 3600_000L
        const val DEFAULT_LEASE_MS = 3 * 60_000L
    }
}
