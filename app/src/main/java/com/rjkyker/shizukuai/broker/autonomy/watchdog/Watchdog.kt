package com.rjkyker.shizukuai.broker.autonomy.watchdog

import android.content.Context
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.autonomy.AutonomyFlags
import com.rjkyker.shizukuai.broker.autonomy.audit.AuditLog
import com.rjkyker.shizukuai.broker.autonomy.queue.TaskQueue
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.bridge.ShizukuGateway
import com.rjkyker.shizukuai.broker.llm.Preflight
import com.rjkyker.shizukuai.broker.priv.PrivServiceClient
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.service.BrokerPause
import com.rjkyker.shizukuai.broker.service.BrokerService
import org.json.JSONArray
import org.json.JSONObject

/**
 * U8 watchdog: read-only health probes plus a short list of safe self-heals (restart our own FGS/server,
 * re-bootstrap our uid-2000 priv server via Shizuku, recover stuck tasks). Never touches other apps' data,
 * never reboots, never changes policies. Every heal is audited as Actor.WATCHDOG.
 */
object Watchdog {
    @Volatile var last: JSONObject? = null

    fun probe(ctx: Context): JSONObject {
        val (pct, charging) = Preflight.battery(ctx)
        val thermal = runCatching { ctx.getSystemService(android.os.PowerManager::class.java).currentThermalStatus }.getOrDefault(-1)
        return JSONObject()
            .put("server_running", BrokerServer.get() != null)
            .put("accessibility", AgentAccessibilityService.instance != null)
            .put("shizuku_ready", runCatching { ShizukuGateway.isReady() }.getOrDefault(false))
            .put("priv_alive", runCatching { PrivServiceClient.isAlive() }.getOrDefault(false))
            .put("paused", BrokerPause.isPaused(ctx))
            .put("battery_pct", pct).put("charging", charging).put("thermal_status", thermal)
            .put("free_storage_mb", Preflight.freeStorageBytes(ctx) / (1024 * 1024))
    }

    fun summary(p: JSONObject): String =
        "server=${p.optBoolean("server_running")} a11y=${p.optBoolean("accessibility")} shizuku=${p.optBoolean("shizuku_ready")} " +
            "priv=${p.optBoolean("priv_alive")} paused=${p.optBoolean("paused")} battery=${p.optInt("battery_pct")}%" +
            (if (p.optBoolean("charging")) " charging" else "") + " thermal=${p.optInt("thermal_status")} free=${p.optLong("free_storage_mb")}MB"

    fun run(ctx: Context): JSONObject {
        val p = probe(ctx)
        val heals = JSONArray()
        fun heal(tool: String, outcome: String) {
            heals.put(JSONObject().put("action", tool).put("outcome", outcome))
            AuditLog.record(ctx, Actor.WATCHDOG.name, tool, "LOW_WRITE", "ALLOW", "watchdog self-heal", outcome = outcome)
        }
        if (!p.optBoolean("server_running")) {
            runCatching { BrokerService.start(ctx) }.fold({ heal("restart_broker_service", "requested") }, { heal("restart_broker_service", "failed: ${it.message}") })
        }
        if (!p.optBoolean("priv_alive") && p.optBoolean("shizuku_ready") && AutonomyFlags.get(ctx, AutonomyFlags.WATCHDOG_PRIV_RESTART)) {
            val r = runCatching { PrivServiceClient.start(ctx) }.getOrElse { JSONObject().put("ok", false).put("error", it.message) }
            heal("priv_restart", if (r.optBoolean("ok")) "alive" else "failed: ${r.optString("error", r.optString("result"))}")
        }
        val maint = runCatching { TaskQueue(ctx).maintain() }.getOrElse { JSONObject().put("error", it.message) }
        if (maint.optInt("recovered") > 0) heal("recover_tasks", "recovered=${maint.optInt("recovered")}")
        val out = JSONObject().put("ok", true).put("ts", System.currentTimeMillis()).put("probe", p).put("heals", heals).put("queue", maint)
        last = out
        return out
    }
}
