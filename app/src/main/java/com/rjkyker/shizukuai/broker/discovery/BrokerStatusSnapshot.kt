package com.rjkyker.shizukuai.broker.discovery

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.bridge.ShizukuGateway
import com.rjkyker.shizukuai.broker.dhizuku.DhizukuGateway
import com.rjkyker.shizukuai.broker.sensing.WifiPresenceEngine
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import org.json.JSONObject

/**
 * Cheap, local-only (no network) snapshot of real broker state. Booleans/ids only — never a secret.
 * Shared by the open GET /health response and the command-center dashboard.
 */
object BrokerStatusSnapshot {

    fun uptimeMs(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    fun build(ctx: Context): JSONObject {
        val s = runCatching { ShizukuGateway.status() }.getOrNull()
        val server = BrokerServer.get()
        return JSONObject()
            .put("server", JSONObject()
                .put("running", server?.isRunning == true)
                .put("host", BrokerServer.HOST)
                .put("port", BrokerServer.PORT)
                .put("bearer_required", true))
            .put("shizuku", JSONObject()
                .put("binder_alive", s?.binderAlive == true)
                .put("permission_granted", s?.permissionGranted == true))
            .put("dhizuku", JSONObject()
                .put("installed", runCatching { DhizukuGateway.isInstalled(ctx) }.getOrDefault(false))
                .put("granted", runCatching { DhizukuGateway.isGranted() }.getOrDefault(false)))
            .put("accessibility", AgentAccessibilityService.isEnabled())
            .put("upstream_configured", SecurePrefs.getUpstreamMcpUrl(ctx).isNotBlank())
            .put("airtable_key_set", SecurePrefs.getAirtableApiKey(ctx).isNotBlank())
            .put("wifi_presence", WifiPresenceEngine.stateJson())
            .put("mdns", JSONObject()
                .put("registered", NsdAdvertiser.isRegistered())
                .put("types", org.json.JSONArray(NsdAdvertiser.registeredTypes())))
            .put("pid", Process.myPid())
            .put("uptime_ms", uptimeMs())
    }
}
