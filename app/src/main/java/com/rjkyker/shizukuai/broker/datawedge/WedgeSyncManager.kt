package com.rjkyker.shizukuai.broker.datawedge

import android.content.Context
import android.util.Log
import com.rjkyker.shizukuai.broker.mcp.UpstreamMcpRelayClient
import org.json.JSONObject
import java.io.File

/**
 * Snapshots local DataWedge state into a JSON file and can re-apply the broker's minimal
 * broadcast profile locally. Optional push_upstream forwards the snapshot via
 * [UpstreamMcpRelayClient] when that server exposes a compatible tool; otherwise the snapshot
 * stays on-device and the response says upstream unsupported.
 *
 * Must be called off the main thread (DataWedgeQuery blocks).
 */
object WedgeSyncManager {

    private const val TAG = "WedgeSync"
    private const val STATE_FILE = "wedge_sync_state.json"
    private const val DEFAULT_PROFILE = "ShizukuAiBroker"

    fun stateFile(context: Context): File = File(context.filesDir, STATE_FILE)

    fun status(context: Context): JSONObject {
        val f = stateFile(context)
        val out = JSONObject()
            .put("ok", true)
            .put("state_file", f.absolutePath)
            .put("exists", f.exists())
            .put("size", if (f.exists()) f.length() else 0)
            .put("mtime_ms", if (f.exists()) f.lastModified() else JSONObject.NULL)
        if (f.exists()) {
            runCatching { out.put("snapshot", JSONObject(f.readText())) }
                .onFailure { out.put("read_error", it.message) }
        }
        return out
    }

    /** Live query → write [STATE_FILE]. */
    fun snapshot(context: Context, timeoutMs: Long = 6000L): JSONObject {
        val live = DataWedgeQuery.run(context, "all", timeoutMs)
        val snap = JSONObject()
            .put("ok", live.optBoolean("ok", false))
            .put("ts", System.currentTimeMillis())
            .put("package", context.packageName)
            .put("profile_default", DEFAULT_PROFILE)
            .put("datawedge", live)
        val f = stateFile(context)
        f.writeText(snap.toString(2))
        Log.i(TAG, "wrote snapshot ${f.absolutePath} (${f.length()} bytes)")
        return snap.put("state_file", f.absolutePath)
    }

    /**
     * Apply the broker's known-good minimal broadcast profile locally
     * (createProfile + setMinimalBroadcastProfile + switchToProfile).
     */
    fun applyLocal(context: Context, profile: String = DEFAULT_PROFILE): JSONObject {
        val name = profile.ifBlank { DEFAULT_PROFILE }
        val created = DataWedgeApi.createProfile(context, name)
        val configured = DataWedgeApi.setMinimalBroadcastProfile(context, name)
        val switched = DataWedgeApi.switchToProfile(context, name)
        return JSONObject()
            .put("ok", created.optBoolean("ok", true) && configured.optBoolean("ok", true))
            .put("profile", name)
            .put("create", created)
            .put("set_config", configured)
            .put("switch", switched)
    }

    /**
     * Push the latest (or freshly taken) snapshot upstream as tools/call.
     * Tries tool names: wedge_sync_apply, datawedge_sync, apply_wedge_snapshot.
     */
    fun pushUpstream(context: Context, timeoutMs: Long = 15_000L): JSONObject {
        val f = stateFile(context)
        val snap = if (f.exists()) {
            runCatching { JSONObject(f.readText()) }.getOrElse {
                return JSONObject().put("ok", false).put("error", "corrupt local snapshot: ${it.message}")
            }
        } else {
            snapshot(context)
        }
        val client = UpstreamMcpRelayClient(context)
        if (!client.isConfigured()) {
            return JSONObject().put("ok", false)
                .put("error", "upstream not configured")
                .put("hint", "Set upstream MCP URL (Tailscale/tunnel) in the broker app")
                .put("local_snapshot_kept", true)
                .put("state_file", f.absolutePath)
        }
        val candidates = listOf("wedge_sync_apply", "datawedge_sync", "apply_wedge_snapshot")
        val attempts = org.json.JSONArray()
        for (tool in candidates) {
            val r = client.toolsCall(
                tool,
                JSONObject().put("snapshot", snap).put("source", context.packageName),
                timeoutMs
            )
            attempts.put(JSONObject().put("tool", tool).put("ok", r.optBoolean("ok", false)).put("response", r))
            if (r.optBoolean("ok", false)) {
                return JSONObject().put("ok", true).put("tool", tool).put("upstream", r)
                    .put("state_file", f.absolutePath)
            }
            // Method/tool-not-found → try next; transport failure → stop
            val err = r.optString("error", "").lowercase()
            if (err.contains("http ") || err.contains("connect") || err.contains("timeout") ||
                err.contains("upstream not configured") || err.contains("initialize failed")
            ) {
                return JSONObject().put("ok", false)
                    .put("error", "upstream unreachable: ${r.optString("error")}")
                    .put("local_snapshot_kept", true)
                    .put("state_file", f.absolutePath)
                    .put("attempts", attempts)
            }
        }
        return JSONObject().put("ok", false)
            .put("error", "upstream has no supported wedge-sync tool")
            .put("tried", org.json.JSONArray(candidates))
            .put("local_snapshot_kept", true)
            .put("state_file", f.absolutePath)
            .put("attempts", attempts)
            .put("hint", "Snapshot saved locally. Upstream must expose wedge_sync_apply (or alias).")
    }

    fun control(context: Context, op: String, args: JSONObject = JSONObject()): JSONObject {
        return when (op.lowercase()) {
            "status" -> status(context)
            "snapshot" -> snapshot(context, args.optLong("timeout_ms", 6000L))
            "apply_local" -> applyLocal(context, args.optString("profile", DEFAULT_PROFILE))
            "push_upstream" -> pushUpstream(context, args.optLong("timeout_ms", 15_000L))
            else -> JSONObject().put("ok", false)
                .put("error", "wedge_sync_control op must be status|snapshot|apply_local|push_upstream")
        }
    }
}
