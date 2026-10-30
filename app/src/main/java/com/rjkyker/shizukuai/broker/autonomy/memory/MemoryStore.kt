package com.rjkyker.shizukuai.broker.autonomy.memory

import android.content.Context
import com.rjkyker.shizukuai.broker.autonomy.AutonomyFlags
import com.rjkyker.shizukuai.broker.autonomy.queue.AutonomyDb
import com.rjkyker.shizukuai.broker.autonomy.queue.MemoryEntity
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import org.json.JSONArray
import org.json.JSONObject

/**
 * U3 memory with owner controls. OFF by default (memory_enabled=false). Owner-added notes are active at once;
 * AI proposals are stored as pending and never reach a prompt until the owner accepts them on the device.
 * View / delete / wipe / export from the dashboard. Secret-looking text is refused.
 */
object MemoryStore {
    const val MAX_ITEMS = 200
    const val MAX_CHARS = 500
    private val SECRET = Regex("""(?i)(password|passcode|api[_ -]?key|secret|token|\b\d{6,8}\b|\b(?:\d[ -]?){13,19}\b)""")

    fun validate(text: String): String? = when {
        text.isBlank() -> "empty"
        text.length > MAX_CHARS -> "longer than $MAX_CHARS chars"
        SECRET.containsMatchIn(text) -> "looks like a secret/code/card number; not stored"
        else -> null
    }

    fun enabled(ctx: Context) = AutonomyFlags.get(ctx, AutonomyFlags.MEMORY)

    fun add(ctx: Context, text: String, actor: Actor): JSONObject {
        validate(text)?.let { return JSONObject().put("ok", false).put("error", it) }
        val dao = AutonomyDb.get(ctx).memory()
        if (dao.count() >= MAX_ITEMS) return JSONObject().put("ok", false).put("error", "memory full ($MAX_ITEMS)")
        val pending = if (actor == Actor.USER_DEVICE) 0 else 1
        val id = dao.insert(MemoryEntity(text = text.trim(), source = actor.name, pending = pending, createdAt = System.currentTimeMillis()))
        return JSONObject().put("ok", true).put("id", id).put("pending", pending == 1)
            .put("note", if (pending == 1) "proposal stored; owner must accept it on the device before it is used" else "saved")
    }

    /** Device UI only (PermissionEngine marks memory_accept device-only). */
    fun acceptFromDevice(ctx: Context, id: Long): Boolean {
        val dao = AutonomyDb.get(ctx).memory()
        val m = dao.get(id) ?: return false
        dao.update(m.copy(pending = 0)); return true
    }

    fun delete(ctx: Context, id: Long) = AutonomyDb.get(ctx).memory().delete(id) > 0
    fun wipe(ctx: Context) = AutonomyDb.get(ctx).memory().wipe()

    /** Notes allowed into prompts: only when enabled and only accepted items. */
    fun forPrompt(ctx: Context, limit: Int = 10): List<String> =
        if (!enabled(ctx)) emptyList() else AutonomyDb.get(ctx).memory().accepted(limit).map { it.text }

    fun all(ctx: Context): List<MemoryEntity> = AutonomyDb.get(ctx).memory().all()

    fun exportJson(ctx: Context): JSONObject = JSONObject().put("ok", true).put("enabled", enabled(ctx))
        .put("items", JSONArray().also { a ->
            all(ctx).forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("source", it.source)
                .put("pending", it.pending == 1).put("created_at", it.createdAt)) }
        })
}
