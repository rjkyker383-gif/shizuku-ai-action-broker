package com.rjkyker.shizukuai.broker.datawedge

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/**
 * Synchronous DataWedge queries: send GET_* commands, then wait for the matching
 * RESULT_ACTION replies. DataWedge does not echo COMMAND_IDENTIFIER for GET_* queries,
 * so replies are matched by result kind (status/version/profiles/active_profile).
 * All requested queries are sent at once; [timeoutMs] is the total wait budget.
 * Must be called off the main thread.
 */
object DataWedgeQuery {

    val KINDS = linkedMapOf(
        "status" to DataWedgeApi.EXTRA_GET_DATAWEDGE_STATUS,
        "version" to DataWedgeApi.EXTRA_GET_VERSION_INFO,
        "profiles" to DataWedgeApi.EXTRA_GET_PROFILES_LIST,
        "active_profile" to DataWedgeApi.EXTRA_GET_ACTIVE_PROFILE,
    )

    @Synchronized
    fun run(context: Context, query: String, timeoutMs: Long = 6000L): JSONObject {
        DataWedgeResultReceiver.ensureRegistered(context.applicationContext)
        val q = query.trim().lowercase().ifEmpty { "all" }
        val kinds = if (q == "all") KINDS.keys.toList() else listOf(q)
        if (kinds.any { it !in KINDS }) {
            return JSONObject().put("ok", false)
                .put("error", "query must be one of all|${KINDS.keys.joinToString("|")}")
        }
        val start = SystemClock.elapsedRealtime()
        val entries = kinds.associateWith { DataWedgeResultStore.register("kind:$it") }
        for (kind in kinds) {
            DataWedgeApi.send(context, KINDS.getValue(kind), "", sendResult = false)
        }
        val out = JSONObject().put("ok", true).put("query", q).put("ts", System.currentTimeMillis())
        var replies = 0
        for (kind in kinds) {
            val left = timeoutMs - (SystemClock.elapsedRealtime() - start)
            val reply = DataWedgeResultStore.await("kind:$kind", entries.getValue(kind), left)
            if (reply != null) {
                replies++
                out.put(kind, reply.opt("value") ?: reply)
            } else {
                out.put(kind, JSONObject().put("ok", false).put("error", "no DataWedge reply within ${timeoutMs}ms"))
            }
        }
        out.put("datawedge_replied", replies > 0)
        out.put("replies", replies)
        out.put("elapsed_ms", SystemClock.elapsedRealtime() - start)
        DataWedgeResultStore.externalPath(context)?.let { out.put("files", it) }
        DataWedgeResultStore.writeQuery(context, out)
        return out
    }
}
