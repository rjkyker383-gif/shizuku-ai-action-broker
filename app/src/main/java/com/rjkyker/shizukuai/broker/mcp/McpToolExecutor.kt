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
import com.rjkyker.shizukuai.broker.server.ActionRouter
import com.rjkyker.shizukuai.broker.sensing.WifiPresenceEngine
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.shell.ShellExecutor
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
                TOOL_ZEBRA_WIFI -> zebraWifi(args, confirmed)
                TOOL_ZEBRA_DW -> ZebraCapabilityLayer.datawedgeProfile(context, args)
                TOOL_ZEBRA_SCANNER -> ZebraCapabilityLayer.scannerControl(context, args)
                TOOL_STATUS -> systemStatus()
                TOOL_DISCOVERY -> JSONObject().put("ok", true).put("manifest", DiscoveryProvider.manifest(context))
                else -> err("unknown tool: $name")
            }
        } catch (e: Exception) {
            err("$name failed: ${e.javaClass.simpleName}: ${e.message}")
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
                return ShellExecutor.startSession(cmd, args.optLong("timeout_sec", 30L))
            }
        }

        needConfirm(TOOL_EXEC_SHELL, confirmed)?.let { return it }
        val cmd = args.optString("command", args.optString("cmd", ""))
        if (cmd.isBlank()) return err("exec_shell requires command")
        val route = args.optString("route", "local").lowercase()
        val timeout = args.optLong("timeout_sec", 30L).coerceIn(1L, 300L)

        return when (route) {
            "local", "" -> {
                val r = ShellExecutor.execSync(cmd, timeout)
                val shizuku = ShizukuGateway.status()
                JSONObject()
                    .put("ok", r.exitCode == 0)
                    .put("exit_code", r.exitCode)
                    .put("stdout", r.stdout)
                    .put("stderr", r.stderr)
                    .put("via", r.via)
                    .put("timed_out", r.timedOut)
                    .put("route", "local")
                    .put("uid", if (r.via == "shizuku_api") shizuku.backendUid else JSONObject.NULL)
            }
            "upstream" -> execShellUpstream(cmd, timeout, args)
            else -> err("exec_shell route must be local|upstream")
        }
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
        const val TOOL_ZEBRA_WIFI = "zebra_wifi_config"
        const val TOOL_ZEBRA_DW = "zebra_datawedge_profile"
        const val TOOL_ZEBRA_SCANNER = "zebra_scanner_control"
        const val TOOL_DISCOVERY = "discovery_info"
        private const val KEYCODE_APP_SWITCH = 187

        val TOOL_NAMES = setOf(
            TOOL_EXEC_SHELL, TOOL_DUMP_UI, TOOL_GESTURE, TOOL_DHIZUKU,
            TOOL_DATAWEDGE, TOOL_SCAN_HISTORY, TOOL_WEDGE_SYNC, TOOL_UPSTREAM, TOOL_STATUS,
            TOOL_BRIDGE_RELAY, TOOL_AIRTABLE, TOOL_WIFI_PRESENCE,
            TOOL_ZEBRA_WIFI, TOOL_ZEBRA_DW, TOOL_ZEBRA_SCANNER, TOOL_DISCOVERY
        )

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
                    "Run a shell command via Shizuku (local) or relay to upstream exec_shell. " +
                        "route=local|upstream (default local). Optional short-lived sessions: " +
                        "session_start/session_poll/session_kill (NOT a PTY — capped log buffer, max 3). HIGH: confirm=true.",
                    JSONObject()
                        .put("command", prop("string", "Shell command for sh -c"))
                        .put("route", enumProp(listOf("local", "upstream")))
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
