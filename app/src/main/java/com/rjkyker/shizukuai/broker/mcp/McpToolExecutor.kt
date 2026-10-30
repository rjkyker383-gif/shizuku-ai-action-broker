package com.rjkyker.shizukuai.broker.mcp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.action.ActionAllowList
import com.rjkyker.shizukuai.broker.airtable.AirtableSyncWorker
import com.rjkyker.shizukuai.broker.action.RiskLevel
import com.rjkyker.shizukuai.broker.admin.DelegatedAdminActionHandler
import com.rjkyker.shizukuai.broker.bridge.ShizukuGateway
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeQuery
import com.rjkyker.shizukuai.broker.datawedge.ScanHistoryStore
import com.rjkyker.shizukuai.broker.datawedge.WedgeSyncManager
import com.rjkyker.shizukuai.broker.discovery.DiscoveryProvider
import com.rjkyker.shizukuai.broker.dhizuku.DhizukuGateway
import com.rjkyker.shizukuai.broker.delegation.BrokerDelegationService
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyCatalog
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyEngine
import com.rjkyker.shizukuai.broker.priv.PrivServiceClient
import com.rjkyker.shizukuai.broker.server.ActionRouter
import com.rjkyker.shizukuai.broker.sensing.WifiPresenceEngine
import com.rjkyker.shizukuai.broker.sensing.WifiDetection
import com.rjkyker.shizukuai.broker.hotspot.HotspotManager
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.service.BrokerPause
import com.rjkyker.shizukuai.broker.shell.ShellDenylist
import com.rjkyker.shizukuai.broker.shell.ShellExecutor
import com.rjkyker.shizukuai.broker.shell.TermuxShellBridge
import com.rjkyker.shizukuai.broker.zebra.ZebraCapabilityLayer
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified execution controller behind the MCP tools (and the matching POST /action ops).
 * Engines: ShellExecutor/ShizukuBridge, AgentAccessibilityService, DhizukuGateway,
 * DataWedgeQuery / WedgeSyncManager, UpstreamMcpRelayClient.
 * HIGH-risk calls need confirm=true (argument) or the X-Confirm: true header.
 */
class McpToolExecutor(private val context: Context, private val router: ActionRouter) {

    private val admin by lazy { DelegatedAdminActionHandler(context) }
    private val upstream by lazy { UpstreamMcpRelayClient(context) }
    private val mesh by lazy { BridgeMeshRelay(context) }

    fun call(name: String, args: JSONObject, confirmHeader: Boolean = false): JSONObject {
        if (name !in TOOL_NAMES) return err("unknown tool: $name")
        val confirmed = confirmHeader || args.optBoolean("confirm", false)
        return try {
            when (name) {
                TOOL_EXEC_SHELL -> execShell(args, confirmed)
                TOOL_DUMP_UI -> dumpUi(args)
                TOOL_GESTURE -> gesture(args)
                TOOL_DHIZUKU -> dhizuku(args, confirmed)
                TOOL_DATAWEDGE -> datawedge(args)
                TOOL_SCAN_HISTORY -> scanHistory(args)
                TOOL_WEDGE_SYNC -> WedgeSyncManager.control(
                    context,
                    args.optString("op", "status"),
                    args
                )
                TOOL_UPSTREAM -> upstreamRelay(args)
                TOOL_BRIDGE_RELAY -> bridgeRelay(args)
                TOOL_AIRTABLE -> airtableLog(args)
                TOOL_WIFI_PRESENCE -> wifiPresence(args)
                TOOL_WIFI_DETECTION -> wifiDetection(args)
                TOOL_HOTSPOT_START -> HotspotManager.start(context, args)
                TOOL_HOTSPOT_STOP -> HotspotManager.stop(context)
                TOOL_HOTSPOT_STATUS -> HotspotManager.status(context).put("ok", true)
                TOOL_ZEBRA_WIFI -> zebraWifi(args, confirmed)
                TOOL_ZEBRA_DW -> ZebraCapabilityLayer.datawedgeProfile(context, args)
                TOOL_ZEBRA_SCANNER -> ZebraCapabilityLayer.scannerControl(context, args)
                TOOL_STATUS -> systemStatus()
                TOOL_A11Y_SCREENSHOT, TOOL_A11Y_UI_TREE, TOOL_A11Y_TAP, TOOL_A11Y_SWIPE,
                TOOL_A11Y_TEXT, TOOL_A11Y_GLOBAL, TOOL_A11Y_CLICK_TEXT -> a11yTool(name, args)
                TOOL_DISCOVERY -> JSONObject().put("ok", true).put("manifest", DiscoveryProvider.manifest(context))
                TOOL_BROKER_PAUSE -> BrokerPause.setPaused(context, true)
                TOOL_BROKER_RESUME -> BrokerPause.setPaused(context, false)
                TOOL_BROKER_PAUSE_STATUS -> BrokerPause.status(context)
                TOOL_OWNER_POLICY -> OwnerPolicyEngine.run(
                    context, args.optString("op", "probe"), args, OwnerPolicyCatalog.Caller.MCP, confirmed
                )
                TOOL_PRIV -> privService(args, confirmed)
                TOOL_DELEGATION -> clientDelegation(args)
                else -> err("unknown tool: $name")
            }
        } catch (e: Exception) {
            err("$name failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------- 1.8.8 priv_service / client_delegation ----------------

    private fun privService(args: JSONObject, confirmed: Boolean): JSONObject =
        when (args.optString("op", "status").lowercase()) {
            "status" -> PrivServiceClient.status(context)
            "adb_command" -> {
                runCatching { PrivServiceClient.writeScript(context) }
                JSONObject().put("ok", true).put("adb_command", PrivServiceClient.adbCommand(context))
                    .put("note", PrivServiceClient.HONEST_NOTE)
            }
            "start" -> PrivServiceClient.start(context)
            "stop" -> PrivServiceClient.stop()
            "getprop" -> PrivServiceClient.getprop(args.optString("key", "ro.product.model"))
            "exec" -> {
                if (!confirmed) err("priv_service exec is HIGH risk; resend with confirm=true")
                else {
                    val cmd = args.optString("command", args.optString("cmd", ""))
                    if (cmd.isBlank()) err("command required")
                    else PrivServiceClient.exec(cmd, args.optInt("timeout_sec", 30))
                }
            }
            else -> err("priv_service op must be status|start|stop|exec|getprop|adb_command")
        }

    private fun clientDelegation(args: JSONObject): JSONObject {
        val reg = BrokerDelegationService.registry(context)
        val pkg = args.optString("package", "")
        return when (args.optString("op", "list").lowercase()) {
            "list" -> reg.toJson()
            "deny" -> JSONObject().put("ok", reg.deny(pkg, System.currentTimeMillis())).put("package", pkg)
            "revoke" -> JSONObject().put("ok", reg.revoke(pkg)).put("package", pkg)
            "approve" -> err("approve is on-device only: open the broker dashboard > Delegated clients")
            else -> err("client_delegation op must be list|deny|revoke")
        }
    }

    // ---------------- exec_shell ----------------

    private fun execShell(args: JSONObject, confirmed: Boolean): JSONObject {
        // Session helpers (local only) — no confirm needed for poll/list/kill.
        when {
            args.optBoolean("session_list", false) ->
                return JSONObject().put("ok", true).put("sessions", ShellExecutor.listSessions())
            args.has("session_poll") || (args.has("session_id") && args.optString("op") == "poll") -> {
                val id = args.optString("session_poll", args.optString("session_id", ""))
                return ShellExecutor.pollSession(id, args.optBoolean("consume_log", false))
            }
            args.has("session_kill") || (args.has("session_id") && args.optString("op") == "kill") -> {
                val id = args.optString("session_kill", args.optString("session_id", ""))
                return ShellExecutor.killSession(id)
            }
            args.optBoolean("session_start", false) || args.optString("op") == "session_start" -> {
                needConfirm(TOOL_EXEC_SHELL, confirmed)?.let { return it }
                val cmd = args.optString("command", args.optString("cmd", ""))
                val denied = ShellDenylist.check(cmd)
                if (!denied.allowed) {
                    return JSONObject().put("ok", false).put("error", "denied: ${denied.reason}")
                }
                return ShellExecutor.startSession(cmd, args.optLong("timeout_sec", 30L))
            }
        }

        needConfirm(TOOL_EXEC_SHELL, confirmed)?.let { return it }
        val cmd = args.optString("command", args.optString("cmd", ""))
        if (cmd.isBlank()) return err("exec_shell requires command")
        val denied = ShellDenylist.check(cmd)
        if (!denied.allowed) {
            return JSONObject().put("ok", false).put("error", "denied: ${denied.reason}")
                .put("via", "denied").put("route", args.optString("route", "auto"))
        }
        val route = args.optString("route", "auto").lowercase()
        val timeout = args.optLong("timeout_sec", 30L).coerceIn(1L, 300L)

        return when (route) {
            "auto" -> {
                when {
                    ShizukuGateway.isReady() -> execLocalShell(cmd, timeout, "shizuku")
                    TermuxShellBridge.isAvailable(context) -> execTermuxShell(cmd, timeout)
                    else -> execLocalShell(cmd, timeout, "local")
                }
            }
            "shizuku" -> {
                if (!ShizukuGateway.isReady()) {
                    err("Shizuku route unavailable: start Shizuku and grant this app Shizuku permission")
                } else execLocalShell(cmd, timeout, "shizuku")
            }
            "local", "" -> execLocalShell(cmd, timeout, "local")
            "termux" -> execTermuxShell(cmd, timeout)
            "upstream" -> execShellUpstream(cmd, timeout, args)
            else -> err("exec_shell route must be auto|shizuku|termux|local|upstream")
        }
    }

    private fun execLocalShell(cmd: String, timeout: Long, selectedRoute: String): JSONObject {
        val r = ShellExecutor.execSync(cmd, timeout)
        val shizuku = ShizukuGateway.status()
        return JSONObject()
            .put("ok", r.exitCode == 0 && !r.timedOut && r.via != "denied")
            .put("exit_code", r.exitCode)
            .put("stdout", r.stdout)
            .put("stderr", r.stderr)
            .put("via", r.via)
            .put("timed_out", r.timedOut)
            .put("route", selectedRoute)
            .put("uid", if (r.via == "shizuku_api") shizuku.backendUid else JSONObject.NULL)
    }

    private fun execTermuxShell(cmd: String, timeout: Long): JSONObject {
        val r = TermuxShellBridge.exec(context, cmd, timeout)
        return JSONObject()
            .put("ok", r.exitCode == 0 && !r.timedOut && r.via != "denied")
            .put("exit_code", r.exitCode)
            .put("stdout", r.stdout)
            .put("stderr", r.stderr)
            .put("via", r.via)
            .put("timed_out", r.timedOut)
            .put("route", "termux")
            .put("uid", JSONObject.NULL)
    }

    private fun execShellUpstream(cmd: String, timeoutSec: Long, args: JSONObject): JSONObject {
        if (!upstream.isConfigured()) {
            return err(
                "upstream not configured",
                "Set upstream MCP URL in the broker app (Tailscale/tunnel). " +
                    "LAN 192.168.x.x only works on home Wi-Fi — use as LAN fallback, not primary."
            )
        }
        // Discover whether upstream exposes exec_shell (or shell).
        val listed = upstream.toolsList((timeoutSec * 1000).coerceAtMost(15_000L))
        if (!listed.optBoolean("ok", false)) {
            return listed.put("route", "upstream").put("phase", "tools/list")
        }
        val toolNames = mutableListOf<String>()
        val tools = listed.optJSONObject("result")?.optJSONArray("tools")
            ?: listed.optJSONArray("tools")
        if (tools != null) {
            for (i in 0 until tools.length()) {
                tools.optJSONObject(i)?.optString("name")?.let { toolNames.add(it) }
            }
        }
        val target = when {
            "exec_shell" in toolNames -> "exec_shell"
            "shell" in toolNames -> "shell"
            else -> {
                return JSONObject().put("ok", false)
                    .put("error", "upstream has no exec_shell/shell tool")
                    .put("upstream_tools", JSONArray(toolNames))
                    .put("route", "upstream")
                    .put("upstream_host", upstream.displayHost())
            }
        }
        val upArgs = JSONObject()
            .put("command", cmd)
            .put("cmd", cmd)
            .put("confirm", true)
            .put("timeout_sec", timeoutSec)
        if (args.has("timeout_ms")) upArgs.put("timeout_ms", args.optLong("timeout_ms"))
        val r = upstream.toolsCall(target, upArgs, (timeoutSec * 1000).coerceAtMost(60_000L))
        return r.put("route", "upstream")
            .put("upstream_tool", target)
            .put("upstream_host", upstream.displayHost())
    }

    // ---------------- dump_ui_hierarchy ----------------

    private fun dumpUi(args: JSONObject): JSONObject {
        val max = args.optInt("max_nodes", args.optInt("maxNodes", 300)).coerceIn(1, 2000)
        return router.route(JSONObject().put("op", "ui_dump").put("maxNodes", max))
    }

    // ---------------- a11y_* (accessibility only, no ADB / Shizuku) ----------------

    private fun a11yTool(name: String, args: JSONObject): JSONObject {
        val a = AgentAccessibilityService.instance
            ?: return err("accessibility service not connected",
                "enable Shizuku AI Broker in Settings > Accessibility (or accessibility_ensure on the Muse bridge)")
        fun num(k: String): Double = args.optDouble(k, Double.NaN)
        return when (name) {
            TOOL_A11Y_SCREENSHOT -> a.screenshot(
                args.optInt("max_width", 720), args.optString("format", "png"), args.optInt("quality", 80))
            TOOL_A11Y_UI_TREE -> a.uiTree(args.optInt("max_nodes", 400), args.optBoolean("all_windows", false))
            TOOL_A11Y_TAP -> {
                val x = num("x"); val y = num("y")
                if (x.isNaN() || y.isNaN()) return err("a11y_tap requires x,y")
                JSONObject().put("ok", a.tap(x.toFloat(), y.toFloat(), args.optLong("duration_ms", 60L)))
                    .put("via", "a11y.dispatchGesture").put("x", x).put("y", y)
            }
            TOOL_A11Y_SWIPE -> {
                val x1 = num("x1"); val y1 = num("y1"); val x2 = num("x2"); val y2 = num("y2")
                if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) return err("a11y_swipe requires x1,y1,x2,y2")
                JSONObject().put("ok", a.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(),
                    args.optLong("duration_ms", 300L).coerceIn(1L, 10_000L))).put("via", "a11y.dispatchGesture")
            }
            TOOL_A11Y_TEXT -> a.setTextFocused(args.optString("text", "").take(5000))
            TOOL_A11Y_GLOBAL -> a.globalAction(args.optString("action", ""))
            TOOL_A11Y_CLICK_TEXT -> a.clickText(args.optString("text", ""), args.optBoolean("exact", false), args.optInt("index", 0))
            else -> err("unknown a11y tool $name")
        }
    }

    // ---------------- perform_gesture ----------------

    private fun gesture(args: JSONObject): JSONObject {
        val action = args.optString("action", "").lowercase()
        val dur = args.optLong("duration_ms", -1L)
        val a11y = AgentAccessibilityService.instance
        return when (action) {
            "tap" -> {
                val b = JSONObject().put("op", "tap")
                if (args.has("text")) b.put("text", args.optString("text"))
                if (args.has("x")) b.put("x", args.optDouble("x"))
                if (args.has("y")) b.put("y", args.optDouble("y"))
                router.route(b)
            }
            "swipe" -> {
                fun num(k: String): Double = args.optDouble(k, Double.NaN)
                val x1 = num("x1"); val y1 = num("y1"); val x2 = num("x2"); val y2 = num("y2")
                if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) {
                    return err("swipe requires x1,y1,x2,y2")
                }
                router.route(
                    JSONObject().put("op", "swipe")
                        .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
                        .put("durationMs", if (dur > 0) dur else 300L)
                )
            }
            "back" -> router.route(JSONObject().put("op", "key").put("key", "back"))
            "home" -> router.route(JSONObject().put("op", "key").put("key", "home"))
            "recents" -> if (a11y != null) {
                JSONObject().put("ok", a11y.recents()).put("via", "a11y")
            } else {
                router.route(JSONObject().put("op", "key").put("keycode", KEYCODE_APP_SWITCH))
            }
            "scroll" -> scroll(args, if (dur > 0) dur else 400L)
            else -> err("perform_gesture action must be tap|swipe|back|home|recents|scroll")
        }
    }

    private fun scroll(args: JSONObject, dur: Long): JSONObject {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.toDouble()
        val h = dm.heightPixels.toDouble()
        val cx = args.optDouble("x", w / 2)
        val cy = args.optDouble("y", h / 2)
        val dx = w * 0.3
        val dy = h * 0.3
        val (x1, y1, x2, y2) = when (args.optString("direction", "down").lowercase()) {
            "down" -> listOf(cx, cy + dy, cx, cy - dy)
            "up" -> listOf(cx, cy - dy, cx, cy + dy)
            "right" -> listOf(cx + dx, cy, cx - dx, cy)
            "left" -> listOf(cx - dx, cy, cx + dx, cy)
            else -> return err("scroll direction must be up|down|left|right")
        }
        return router.route(
            JSONObject().put("op", "swipe")
                .put("x1", x1.coerceIn(1.0, w - 1)).put("y1", y1.coerceIn(1.0, h - 1))
                .put("x2", x2.coerceIn(1.0, w - 1)).put("y2", y2.coerceIn(1.0, h - 1))
                .put("durationMs", dur)
        ).put("approximation", "swipe")
    }

    // ---------------- manage_dhizuku_policy ----------------

    private fun dhizuku(args: JSONObject, confirmed: Boolean): JSONObject {
        val op = args.optString("op", "status").lowercase()
        if (op in REJECTED_DHIZUKU_OPS) {
            return err(
                "manage_dhizuku_policy op '$op' is not supported: the broker only exposes " +
                    "non-destructive, reversible device-owner calls (no install/uninstall/wipe/reset)"
            )
        }
        val mapped = when (op) {
            "status" -> "dhizuku_status"
            "connect" -> "dhizuku_connect"
            "scopes" -> "dhizuku_scopes"
            "set_scopes" -> "dhizuku_set_scopes"
            "perm" -> "dhizuku_perm"
            "restriction" -> if (args.optBoolean("enabled", true)) "dhizuku_add_restriction" else "dhizuku_clear_restriction"
            "hide" -> "dhizuku_hide_app"
            "suspend" -> "dhizuku_suspend_app"
            "delegated_scopes" -> "admin_scopes"
            else -> return err(
                "manage_dhizuku_policy op must be status|connect|scopes|set_scopes|perm|restriction|hide|suspend|delegated_scopes"
            )
        }
        val risk = ActionAllowList.riskLevelFor(mapped) ?: RiskLevel.HIGH
        if (risk == RiskLevel.HIGH && !confirmed) {
            return err("$op ($mapped) is HIGH risk; resend with arguments.confirm=true or header X-Confirm: true")
        }
        if (mapped.startsWith("dhizuku_") && mapped != "dhizuku_status" &&
            DhizukuGateway.isInstalled(context) && !DhizukuGateway.isGranted()
        ) {
            DhizukuGateway.init(context)
        }
        val body = JSONObject(args.toString()).put("op", mapped)
        if (risk == RiskLevel.HIGH) body.put("confirm", true)
        if (op == "hide" && !args.has("hidden")) body.put("hidden", args.optBoolean("enabled", true))
        if (op == "suspend" && !args.has("suspended")) body.put("suspended", args.optBoolean("enabled", true))
        return router.route(body).put("risk", risk.name).put("mapped_op", mapped)
    }


    private fun scanHistory(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        ScanHistoryStore.ensureLoaded(context)
        return ScanHistoryStore.snapshot(limit)
    }

    // ---------------- datawedge_query ----------------

    private fun datawedge(args: JSONObject): JSONObject {
        val command = args.optString("command", "").lowercase()
        if (command.isNotEmpty()) {
            if (command !in DATAWEDGE_COMMANDS) {
                return err("datawedge command must be one of ${DATAWEDGE_COMMANDS.joinToString("|")}")
            }
            return router.route(JSONObject(args.toString()).put("op", "datawedge_$command"))
        }
        val q = args.optString("q", args.optString("query", "all"))
        val t = args.optLong("timeout_ms", 6000L).coerceIn(500L, 10_000L)
        return DataWedgeQuery.run(context, q, t)
    }

    // ---------------- upstream_mcp_relay ----------------

    private fun upstreamRelay(args: JSONObject): JSONObject {
        val method = args.optString("method", "tools/call").ifBlank { "tools/call" }
        val timeout = args.optLong("timeout_ms", 15_000L).coerceIn(1_000L, 120_000L)
        val payload = args.optJSONObject("payload")
            ?: args.optJSONObject("params")
            ?: JSONObject()
        // Convenience: if method is tools/call and payload has name at top level already OK.
        return upstream.call(method, payload, timeout)
            .put("method", method)
            .put("upstream_host", upstream.displayHost())
    }

    // ---------------- bridge_relay ----------------

    private fun bridgeRelay(args: JSONObject): JSONObject {
        val bridge = args.optString("bridge", "").lowercase()
        if (bridge.isBlank()) {
            return err("bridge_relay requires bridge", "one of ${BridgeMeshRelay.BRIDGES.joinToString("|")}")
        }
        val method = args.optString("method", "tools/call").ifBlank { "tools/call" }
        val timeout = args.optLong("timeout_ms", 15_000L).coerceIn(1_000L, 120_000L)
        val params = args.optJSONObject("params")
            ?: args.optJSONObject("payload")
            ?: JSONObject()
        return mesh.relay(bridge, method, params, timeout)
    }

    // ---------------- airtable_log ----------------

    private fun airtableLog(args: JSONObject): JSONObject {
        val base = args.optString("base", "")
        val table = args.optString("table", "")
        if (base.isBlank()) {
            return err(
                "airtable_log requires base",
                "allow-listed: ${AirtableSyncWorker.ALLOWED_BASES.joinToString("|")} (or primary|secondary)"
            )
        }
        if (table.isBlank()) return err("airtable_log requires table")
        val fields = args.optJSONObject("fields") ?: JSONObject()
        if (args.optBoolean("flush_only", false)) {
            return AirtableSyncWorker.flushNow(context)
        }
        return AirtableSyncWorker.log(context, base, table, fields)
    }

    // ---------------- wifi_detection (1.8.5, default ON) ----------------

    private fun wifiDetection(args: JSONObject): JSONObject = when (args.optString("op", "status")) {
        "on" -> { WifiDetection.setEnabled(context, true); WifiDetection.stateJson(context).put("ok", true) }
        "off" -> { WifiDetection.setEnabled(context, false); WifiDetection.stateJson(context).put("ok", true) }
        "scan" -> WifiDetection.scanAps(context, requestFresh = true).put("state", WifiDetection.stateJson(context))
        else -> WifiDetection.stateJson(context, includeAps = args.optBoolean("include_aps", true)).put("ok", true)
    }

    // ---------------- wifi_presence_scan ----------------

    private fun wifiPresence(args: JSONObject): JSONObject {
        val dur = args.optLong("duration_ms", 4_000L)
        return WifiPresenceEngine.scan(context, dur)
    }

    // ---------------- zebra_wifi_config ----------------

    private fun zebraWifi(args: JSONObject, confirmed: Boolean): JSONObject {
        // Reads (status) are safe; mutating ops are HIGH and need confirm=true.
        val op = args.optString("op", "status").lowercase()
        if (op != "status") {
            needConfirm(TOOL_ZEBRA_WIFI, confirmed)?.let { return it }
        }
        return ZebraCapabilityLayer.wifiConfig(context, args)
    }

    // ---------------- get_system_status ----------------

    fun systemStatus(): JSONObject {
        val s = ShizukuGateway.status()
        val server = BrokerServer.get()
        val pm = context.getSystemService(PowerManager::class.java)
        val dz = runCatching { DhizukuGateway.status(context) }
            .getOrElse { JSONObject().put("ok", false).put("error", it.message) }
        if (DhizukuGateway.isGranted()) {
            runCatching { dz.put("delegated_scopes", DhizukuGateway.getScopes().optJSONArray("scopes")) }
        }
        return JSONObject()
            .put("ok", true)
            .put("paused", BrokerPause.isPaused(context))
            .put("package", context.packageName)
            .put("version", versionName())
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put(
                "server", JSONObject()
                    .put("running", server?.isRunning == true)
                    .put("host", BrokerServer.HOST)
                    .put("port", BrokerServer.PORT)
                    .put("mcp", "/mcp")
                    .put("ws", "/ws")
            )
            .put(
                "upstream", JSONObject()
                    .put("configured", upstream.isConfigured())
                    .put("host", upstream.displayHost())
                    .put("lan_fallback_set", SecurePrefs.getUpstreamLanFallbackUrl(context).isNotBlank())
            )
            .put(
                "shizuku", JSONObject()
                    .put("binder_alive", s.binderAlive)
                    .put("permission_granted", s.permissionGranted)
                    .put("backend_uid", s.backendUid ?: JSONObject.NULL)
            )
            .put("dhizuku", dz)
            .put("delegated_admin_scopes", JSONArray(runCatching { admin.getActiveScopes() }.getOrDefault(emptyList())))
            .put("accessibility", AgentAccessibilityService.isEnabled())
            .put("battery", battery())
            .put("ignoring_battery_optimizations", pm?.isIgnoringBatteryOptimizations(context.packageName) == true)
            .put("shell_sessions", ShellExecutor.listSessions())
            .put("bridges", JSONObject().also { b ->
                for (name in BridgeMeshRelay.BRIDGES) {
                    val host = mesh.displayHost(name)
                    b.put(name, JSONObject().put("configured", host.isNotEmpty()).put("host", host))
                }
            })
            .put("airtable", JSONObject()
                .put("key_set", SecurePrefs.getAirtableApiKey(context).isNotBlank())
                .put("bases", JSONArray(AirtableSyncWorker.ALLOWED_BASES.toList())))
    }

    private fun battery(): JSONObject {
        val bm = context.getSystemService(BatteryManager::class.java)
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return JSONObject()
            .put("level", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("charging", bm?.isCharging == true)
            .put("plugged", plugged != 0)
            .put("temperature_c", (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0)
    }

    private fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun needConfirm(tool: String, confirmed: Boolean): JSONObject? {
        val risk = ActionAllowList.riskLevelFor(tool) ?: RiskLevel.HIGH
        return if (risk == RiskLevel.HIGH && !confirmed) {
            err("$tool is HIGH risk; resend with arguments.confirm=true or header X-Confirm: true")
        } else null
    }

    private fun err(msg: String, hint: String? = null): JSONObject =
        JSONObject().put("ok", false).put("error", msg).also { if (hint != null) it.put("hint", hint) }

    companion object {
        const val TOOL_EXEC_SHELL = "exec_shell"
        const val TOOL_DUMP_UI = "dump_ui_hierarchy"
        const val TOOL_GESTURE = "perform_gesture"
        const val TOOL_DHIZUKU = "manage_dhizuku_policy"
        const val TOOL_DATAWEDGE = "datawedge_query"
        const val TOOL_SCAN_HISTORY = "scan_history"
        const val TOOL_WEDGE_SYNC = "wedge_sync_control"
        const val TOOL_UPSTREAM = "upstream_mcp_relay"
        const val TOOL_STATUS = "get_system_status"
        const val TOOL_BRIDGE_RELAY = "bridge_relay"
        const val TOOL_AIRTABLE = "airtable_log"
        const val TOOL_WIFI_PRESENCE = "wifi_presence_scan"
        const val TOOL_WIFI_DETECTION = "wifi_detection"
        const val TOOL_HOTSPOT_START = "hotspot_start"
        const val TOOL_HOTSPOT_STOP = "hotspot_stop"
        const val TOOL_HOTSPOT_STATUS = "hotspot_status"
        const val TOOL_ZEBRA_WIFI = "zebra_wifi_config"
        const val TOOL_ZEBRA_DW = "zebra_datawedge_profile"
        const val TOOL_ZEBRA_SCANNER = "zebra_scanner_control"
        const val TOOL_DISCOVERY = "discovery_info"
        const val TOOL_BROKER_PAUSE = "broker_pause"
        const val TOOL_BROKER_RESUME = "broker_resume"
        const val TOOL_BROKER_PAUSE_STATUS = "broker_pause_status"
        const val TOOL_OWNER_POLICY = "owner_policy"
        const val TOOL_PRIV = "priv_service"
        const val TOOL_DELEGATION = "client_delegation"
        const val TOOL_A11Y_SCREENSHOT = "a11y_screenshot"
        const val TOOL_A11Y_UI_TREE = "a11y_ui_tree"
        const val TOOL_A11Y_TAP = "a11y_tap"
        const val TOOL_A11Y_SWIPE = "a11y_swipe"
        const val TOOL_A11Y_TEXT = "a11y_text"
        const val TOOL_A11Y_GLOBAL = "a11y_global"
        const val TOOL_A11Y_CLICK_TEXT = "a11y_click_text"
        val A11Y_TOOLS = setOf(TOOL_A11Y_SCREENSHOT, TOOL_A11Y_UI_TREE, TOOL_A11Y_TAP, TOOL_A11Y_SWIPE,
            TOOL_A11Y_TEXT, TOOL_A11Y_GLOBAL, TOOL_A11Y_CLICK_TEXT)
        private const val KEYCODE_APP_SWITCH = 187

        val TOOL_NAMES = setOf(
            TOOL_EXEC_SHELL, TOOL_DUMP_UI, TOOL_GESTURE, TOOL_DHIZUKU,
            TOOL_DATAWEDGE, TOOL_SCAN_HISTORY, TOOL_WEDGE_SYNC, TOOL_UPSTREAM, TOOL_STATUS,
            TOOL_BRIDGE_RELAY, TOOL_AIRTABLE, TOOL_WIFI_PRESENCE, TOOL_WIFI_DETECTION,
            TOOL_HOTSPOT_START, TOOL_HOTSPOT_STOP, TOOL_HOTSPOT_STATUS,
            TOOL_ZEBRA_WIFI, TOOL_ZEBRA_DW, TOOL_ZEBRA_SCANNER, TOOL_DISCOVERY,
            TOOL_BROKER_PAUSE, TOOL_BROKER_RESUME, TOOL_BROKER_PAUSE_STATUS,
            TOOL_OWNER_POLICY, TOOL_PRIV, TOOL_DELEGATION
        ) + A11Y_TOOLS

        val DATAWEDGE_COMMANDS = listOf("soft_scan", "switch_profile", "create_profile", "set_config")

        val REJECTED_DHIZUKU_OPS = setOf(
            "install", "uninstall", "force_install", "force_uninstall", "install_package",
            "uninstall_package", "wipe", "wipe_data", "factory_reset", "reset_password",
            "remove_admin", "clear_device_owner", "lock_now", "reboot"
        )

        fun toolDefinitions(): JSONArray = JSONArray()
            .put(
                tool(
                    TOOL_EXEC_SHELL,
                    "Run commands through Shizuku, Termux, or an upstream MCP shell. " +
                        "route=auto|shizuku|termux|local|upstream (default auto; reports actual backend). Optional short-lived sessions: " +
                        "session_start/session_poll/session_kill (NOT a PTY — capped log buffer, max 3). HIGH: confirm=true. " +
                        "Muse-aligned denylist enforced on every route (uninstall/wipe/SMS/dumpsys AMS/etc.).",
                    JSONObject()
                        .put("command", prop("string", "Shell command for sh -c"))
                        .put("route", enumProp(listOf("auto", "shizuku", "termux", "local", "upstream")))
                        .put("timeout_sec", prop("integer", "1-300, default 30"))
                        .put("confirm", prop("boolean", "Must be true for sync/session_start"))
                        .put("session_start", prop("boolean", "Start background session"))
                        .put("session_poll", prop("string", "Session id to poll"))
                        .put("session_kill", prop("string", "Session id to cancel"))
                        .put("session_list", prop("boolean", "List active sessions")),
                    listOf("confirm")
                )
            )
            .put(
                tool(
                    TOOL_DUMP_UI,
                    "Capture the current screen node tree via AccessibilityService.",
                    JSONObject().put("max_nodes", prop("integer", "1-2000, default 300")),
                    emptyList()
                )
            )
            .put(
                tool(
                    TOOL_GESTURE,
                    "UI action: tap|swipe|back|home|recents|scroll.",
                    JSONObject()
                        .put("action", enumProp(listOf("tap", "swipe", "back", "home", "recents", "scroll")))
                        .put("x", prop("number", "tap/scroll x")).put("y", prop("number", "tap/scroll y"))
                        .put("text", prop("string", "tap by text"))
                        .put("x1", prop("number")).put("y1", prop("number"))
                        .put("x2", prop("number")).put("y2", prop("number"))
                        .put("duration_ms", prop("integer"))
                        .put("direction", enumProp(listOf("up", "down", "left", "right"))),
                    listOf("action")
                )
            )
            .put(
                tool(
                    TOOL_DHIZUKU,
                    "Dhizuku device-owner policy (non-destructive only). Install/uninstall/wipe refused.",
                    JSONObject()
                        .put(
                            "op", enumProp(
                                listOf(
                                    "status", "connect", "scopes", "delegated_scopes", "set_scopes",
                                    "perm", "restriction", "hide", "suspend"
                                )
                            )
                        )
                        .put("package", prop("string")).put("permission", prop("string"))
                        .put("state", enumProp(listOf("granted", "denied", "default")))
                        .put("restriction", prop("string")).put("enabled", prop("boolean"))
                        .put("hidden", prop("boolean")).put("suspended", prop("boolean"))
                        .put(
                            "scopes", JSONObject().put("type", "array")
                                .put("items", JSONObject().put("type", "string"))
                        )
                        .put("confirm", prop("boolean", "required for HIGH ops")),
                    listOf("op")
                )
            )
            .put(
                tool(
                    TOOL_SCAN_HISTORY,
                    "Last DataWedge scans with full decode detail (symbology, AIM, hex, parsers). LOW-risk, auth required. Newest first.",
                    JSONObject()
                        .put("limit", prop("integer", "Max scans to return (1-200, default 50)")),
                    emptyList()
                )
            )
            .put(
                tool(
                    TOOL_DATAWEDGE,
                    "Zebra DataWedge live query or soft_scan/switch_profile/create_profile/set_config. Alias of wedge reads.",
                    JSONObject()
                        .put("q", enumProp(listOf("all", "status", "version", "profiles", "active_profile")))
                        .put("timeout_ms", prop("integer"))
                        .put("command", enumProp(DATAWEDGE_COMMANDS))
                        .put("profile", prop("string")).put("mode", prop("string")),
                    emptyList()
                )
            )
            .put(
                tool(
                    TOOL_WEDGE_SYNC,
                    "DataWedge sync: snapshot (save JSON under filesDir), apply_local (broker profile), " +
                        "push_upstream (tools/call if upstream supports it), status.",
                    JSONObject()
                        .put("op", enumProp(listOf("status", "snapshot", "apply_local", "push_upstream")))
                        .put("profile", prop("string", "for apply_local, default ShizukuAiBroker"))
                        .put("timeout_ms", prop("integer")),
                    listOf("op")
                )
            )
            .put(
                tool(
                    TOOL_UPSTREAM,
                    "Forward a JSON-RPC method to the configured upstream MCP (any-wifi URL: Tailscale/tunnel). " +
                        "Default method tools/call. Does not invent results on failure.",
                    JSONObject()
                        .put("method", prop("string", "default tools/call; also tools/list, ping, …"))
                        .put("payload", JSONObject().put("type", "object").put("description", "JSON-RPC params"))
                        .put("timeout_ms", prop("integer", "default 15000")),
                    emptyList()
                )
            )
            .put(
                tool(
                    TOOL_STATUS,
                    "Broker + upstream host (no secrets) + Shizuku/Dhizuku/a11y/battery/shell sessions.",
                    JSONObject(),
                    emptyList()
                )
            )
            .put(
                tool(
                    TOOL_BRIDGE_RELAY,
                    "Relay a JSON-RPC MCP call to a named bridge (android|spark|ether|war|magic|upstream). Each bridge URL " +
                        "comes from the app settings (prefilled with the public mesh default, editable; no token baked in); the bridge is health-checked first and " +
                        "an unreachable bridge returns ok=false instead of a faked result.",
                    JSONObject()
                        .put("bridge", enumProp(listOf("android", "spark", "ether", "war", "magic", "upstream")))
                        .put("method", prop("string", "JSON-RPC method, default tools/call"))
                        .put("params", JSONObject().put("type", "object").put("description", "JSON-RPC params"))
                        .put("timeout_ms", prop("integer", "default 15000")),
                    listOf("bridge")
                )
            )
            .put(
                tool(
                    TOOL_AIRTABLE,
                    "Log one record to Airtable. base must be an owner base " +
                        "(appQkHjQwQuyzVT99 / appQDYTaD6gi64g7M, or primary|secondary). The API key is read from " +
                        "the app's SecurePrefs at runtime (never hardcoded); if unset the record is queued and the " +
                        "call returns ok=false 'Airtable key not set'. flush_only=true flushes the queue.",
                    JSONObject()
                        .put("base", prop("string", "appQkHjQwQuyzVT99 | appQDYTaD6gi64g7M | primary | secondary"))
                        .put("table", prop("string", "Airtable table name"))
                        .put("fields", JSONObject().put("type", "object").put("description", "Airtable field map"))
                        .put("flush_only", prop("boolean", "Flush the queued records instead of posting")),
                    listOf("base", "table")
                )
            )
            .put(
                tool(
                    TOOL_WIFI_PRESENCE,
                    "Honest Wi-Fi RSSI/link-variance presence & motion sensor: samples RSSI, link speed, noise " +
                        "floor and frame delivery, runs variance + a small FFT, and returns a motion/presence score " +
                        "with confidence. Detects movement/occupancy NEAR the device only. CANNOT see through walls " +
                        "or identify people — Android does not expose Wi-Fi CSI to apps.",
                    JSONObject()
                        .put("duration_ms", prop("integer", "sample window, 1000-30000, default 4000")),
                    emptyList()
                )
            )
            .put(
                tool(
                    TOOL_WIFI_DETECTION,
                    "Wi-Fi detection (default ON): background loop that lists nearby access points with RSSI as heard by " +
                        "this phone, plus the near-device presence score. op=status (default, include_aps) | scan (fresh) | on | off. " +
                        "Near-device RSSI only: no CSI, not through-wall, cannot identify/count/locate people.",
                    JSONObject()
                        .put("op", JSONObject().put("type", "string").put("enum", JSONArray(listOf("status", "scan", "on", "off"))))
                        .put("include_aps", prop("boolean", "include the AP list in status (default true)")),
                    emptyList()
                )
            )
            .put(
                tool(
                    TOOL_HOTSPOT_START,
                    "Start the Hotspot / Extender: privileged soft AP (cmd wifi start-softap via Shizuku) that shares the " +
                        "phone's internet (repeats Wi-Fi when STA+AP concurrency is supported, else mobile data). Falls back to a " +
                        "LocalOnlyHotspot with NO internet. Default SSID TC22-Extender, WPA2, phone-generated passphrase (masked here). " +
                        HotspotManager.HONEST_NOTE,
                    JSONObject()
                        .put("ssid", prop("string", "1-32 chars [A-Za-z0-9 _.-], default TC22-Extender"))
                        .put("band", JSONObject().put("type", "string").put("enum", JSONArray(listOf("2", "5", "any"))))
                        .put("security", JSONObject().put("type", "string").put("enum", JSONArray(listOf("wpa2", "wpa3"))))
                        .put("allow_local_only", prop("boolean", "fall back to a no-internet local-only hotspot (default true)")),
                    emptyList()
                )
            )
            .put(tool(TOOL_HOTSPOT_STOP, "Stop the Hotspot / Extender (soft AP and any local-only hotspot).", JSONObject(), emptyList()))
            .put(
                tool(
                    TOOL_HOTSPOT_STATUS,
                    "Hotspot / Extender status: on/off, mode, connected clients, upstream (wifi/cellular), AP band+channel, " +
                        "upstream RSSI, STA+AP concurrency (repeater) support. Passphrase masked. " + HotspotManager.HONEST_NOTE,
                    JSONObject(), emptyList()
                )
            )
            .put(
                tool(
                    TOOL_ZEBRA_WIFI,
                    "No-license Zebra/Android Wi-Fi control via WifiManager/DPM/shell (no Mobility DNA license, no " +
                        "license bypass). op=status|enable|disable|add|remove|roaming. Mutating ops are HIGH: confirm=true.",
                    JSONObject()
                        .put("op", enumProp(listOf("status", "enable", "disable", "add", "remove", "roaming")))
                        .put("ssid", prop("string", "for add/remove"))
                        .put("psk", prop("string", "WPA2 passphrase for add"))
                        .put("setting", enumProp(listOf("scan_always", "verbose", "status")))
                        .put("value", prop("string", "true|false for roaming setting"))
                        .put("confirm", prop("boolean", "required for mutating ops")),
                    listOf("op")
                )
            )
            .put(
                tool(
                    TOOL_ZEBRA_DW,
                    "No-license DataWedge profile CRUD. op=list|active|create|set_config|switch.",
                    JSONObject()
                        .put("op", enumProp(listOf("list", "active", "create", "set_config", "switch")))
                        .put("profile", prop("string", "profile name, default ShizukuAiBroker")),
                    listOf("op")
                )
            )
            .put(
                tool(
                    TOOL_ZEBRA_SCANNER,
                    "No-license scanner control via DataWedge. op=status|enable|disable|soft_scan_start|soft_scan_stop.",
                    JSONObject()
                        .put("op", enumProp(listOf("status", "enable", "disable", "soft_scan_start", "soft_scan_stop")))
                        .put("profile", prop("string", "profile for enable/disable, default ShizukuAiBroker")),
                    listOf("op")
                )
            )
            .put(
                tool(
                    TOOL_DISCOVERY,
                    "Return the broker's discovery manifest: app/device/version, every connection method " +
                        "(loopback, Tailscale, public mesh bridges, upstream, mDNS) and tool names + risk. No secrets.",
                    JSONObject(),
                    emptyList()
                )
            )

            .put(tool(TOOL_A11Y_SCREENSHOT,
                "Screenshot via AccessibilityService.takeScreenshot (Android 11+, no ADB). Returns image_base64 + an MCP image. LOW-risk, auth required.",
                JSONObject().put("max_width", prop("integer", "160-1440, default 720 (downscaled)"))
                    .put("format", enumProp(listOf("png", "jpeg")))
                    .put("quality", prop("integer", "10-100 (jpeg), default 80")), emptyList()))
            .put(tool(TOOL_A11Y_UI_TREE,
                "Window node tree (nested JSON: text, desc, id, class, bounds [l,t,r,b], clickable, focused). No ADB. LOW-risk.",
                JSONObject().put("max_nodes", prop("integer", "1-3000, default 400"))
                    .put("all_windows", prop("boolean", "include every interactive window (status bar, IME…)")), emptyList()))
            .put(tool(TOOL_A11Y_TAP, "Tap at screen x,y via dispatchGesture (no ADB). MEDIUM.",
                JSONObject().put("x", prop("number")).put("y", prop("number")).put("duration_ms", prop("integer", "default 60")),
                listOf("x", "y")))
            .put(tool(TOOL_A11Y_SWIPE, "Swipe x1,y1 -> x2,y2 via dispatchGesture (no ADB). MEDIUM.",
                JSONObject().put("x1", prop("number")).put("y1", prop("number")).put("x2", prop("number")).put("y2", prop("number"))
                    .put("duration_ms", prop("integer", "default 300")), listOf("x1", "y1", "x2", "y2")))
            .put(tool(TOOL_A11Y_TEXT, "ACTION_SET_TEXT on the focused input (replaces its text). No ADB. MEDIUM.",
                JSONObject().put("text", prop("string")), listOf("text")))
            .put(tool(TOOL_A11Y_GLOBAL, "Global action BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS (no ADB). MEDIUM.",
                JSONObject().put("action", enumProp(listOf("BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS"))),
                listOf("action")))
            .put(tool(TOOL_A11Y_CLICK_TEXT,
                "Find a node by text/content-description (contains, or exact=true) and click it (clickable ancestor, else tap its centre). MEDIUM.",
                JSONObject().put("text", prop("string")).put("exact", prop("boolean")).put("index", prop("integer", "which match, default 0")),
                listOf("text")))


            .put(tool(TOOL_OWNER_POLICY,
                "OwnDroid-parity device-owner policy through Dhizuku. op=probe (read-only feature probe) | features " +
                    "(OwnDroid inventory) | app_info | restrictions | ... Tiers: READ/MEDIUM run; HIGH needs confirm=true; " +
                    "LOCKOUT ops (camera, lock_task_packages, always_on_vpn, permitted_accessibility/input_methods, " +
                    "keyguard_disabled, lockout_restriction e.g. no_debugging_features) are DENIED over MCP even with confirm " +
                    "(dashboard only). wipe/factory reset/reset password/remove owner are not implemented. " +
                    "Toggles need an explicit value=true|false.",
                JSONObject()
                    .put("op", enumProp(OwnerPolicyCatalog.OPS.filterValues {
                        it != OwnerPolicyCatalog.Tier.LOCKOUT }.keys.toList()))
                    .put("package", prop("string")).put("permission", prop("string"))
                    .put("state", enumProp(listOf("granted", "denied", "default")))
                    .put("restriction", prop("string", "UserManager key, e.g. no_config_bluetooth"))
                    .put("value", prop("boolean", "target state for toggles (hidden/suspended/disabled/enabled)"))
                    .put("text", prop("string")).put("policy", prop("string")).put("mode", prop("string"))
                    .put("host", prop("string")).put("port", prop("integer")).put("ms", prop("integer"))
                    .put("flags", prop("integer")).put("scopes", JSONObject().put("type", "array"))
                    .put("confirm", prop("boolean", "required for HIGH ops")),
                listOf("op")))
            .put(tool(TOOL_PRIV,
                "Broker privileged server (own Shizuku-style app_process service, uid 2000, Binder). " +
                    "op=status|start (bootstrap via Shizuku)|stop|getprop key|exec command (HIGH: confirm=true, denylist)|adb_command. " +
                    PrivServiceClient.HONEST_NOTE,
                JSONObject().put("op", enumProp(listOf("status", "start", "stop", "exec", "getprop", "adb_command")))
                    .put("command", prop("string")).put("key", prop("string")).put("timeout_sec", prop("integer"))
                    .put("confirm", prop("boolean")),
                listOf("op")))
            .put(tool(TOOL_DELEGATION,
                "Dhizuku-style delegated client apps (bind action com.rjkyker.shizukuai.broker.DELEGATION). " +
                    "op=list|deny|revoke. Approval is on-device only.",
                JSONObject().put("op", enumProp(listOf("list", "deny", "revoke"))).put("package", prop("string")),
                listOf("op")))
            .put(tool(TOOL_BROKER_PAUSE,
                "Pause background loops (Wi-Fi detection etc.) but keep the MCP server + accessibility alive. Soft.",
                JSONObject(), emptyList()))
            .put(tool(TOOL_BROKER_RESUME,
                "Resume background loops after broker_pause.",
                JSONObject(), emptyList()))
            .put(tool(TOOL_BROKER_PAUSE_STATUS,
                "Whether the broker is paused and whether Wi-Fi detection is running.",
                JSONObject(), emptyList()))

        private fun tool(name: String, description: String, props: JSONObject, required: List<String>): JSONObject {
            val schema = JSONObject().put("type", "object").put("properties", props)
            if (required.isNotEmpty()) schema.put("required", JSONArray(required))
            return JSONObject().put("name", name).put("description", description).put("inputSchema", schema)
        }

        private fun prop(type: String, description: String = ""): JSONObject =
            JSONObject().put("type", type).also { if (description.isNotEmpty()) it.put("description", description) }

        private fun enumProp(values: List<String>): JSONObject =
            JSONObject().put("type", "string").put("enum", JSONArray(values))
    }
}
