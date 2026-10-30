package com.rjkyker.shizukuai.broker.discovery

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Pure (Android-free) builder for the broker's open AI-discovery manifest, served unauthenticated at
 * GET /discover and GET /.well-known/mcp.json and returned by the `discovery_info` MCP tool.
 *
 * The manifest says WHO the broker is, WHAT it can do (tool names + risk + one-line description) and
 * HOW to connect (loopback, Tailscale, public mesh bridges, upstream, mDNS). It NEVER carries a
 * credential: no bearer token, no Airtable key, no bridge/upstream token. [build] runs a final
 * [redactKnownSecrets] pass with the real secret values as defence in depth, and URLs are stripped
 * of user-info / query / fragment so a `?token=` can never ride along.
 */
object DiscoveryManifest {

    const val SCHEMA = "shizuku-broker-discovery/1"
    val MDNS_SERVICE_TYPES = listOf("_mcp._tcp", "_broker._tcp")
    val OPEN_ENDPOINTS = listOf("/health", "/discover", "/.well-known/mcp.json")

    /** Key fragments that must never appear as a JSON key anywhere in the manifest. */
    val FORBIDDEN_KEY_FRAGMENTS = listOf(
        "token", "secret", "password", "passwd", "psk", "api_key", "apikey", "private_key", "authorization"
    )

    data class Tool(val name: String, val risk: String, val description: String)

    data class Inputs(
        val appId: String,
        val appName: String,
        val versionName: String,
        val versionCode: Long,
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdkInt: Int,
        val localHost: String,
        val port: Int,
        val serverRunning: Boolean,
        val serverName: String,
        val serverVersion: String,
        val protocolVersions: List<String>,
        val defaultProtocolVersion: String,
        val tailscaleAddresses: List<String>,
        val meshBaseUrl: String,
        /** bridge name -> MCP URL (android/spark/ether/war/magic). */
        val bridgeUrls: Map<String, String>,
        val upstreamUrl: String,
        val tools: List<Tool>,
        val mdnsServiceName: String,
        val mdnsRegistered: Boolean,
        /** Real secret values (MCP token, Airtable key, bridge tokens…) used ONLY to redact. */
        val knownSecrets: List<String> = emptyList(),
        /** Addresses the server is actually listening on (127.0.0.1 + Tailscale IPs when bound). */
        val boundAddresses: List<String> = emptyList()
    )

    fun build(i: Inputs): JSONObject {
        val loopbackBase = "http://${i.localHost}:${i.port}"
        val connections = JSONArray()
        connections.put(
            JSONObject()
                .put("id", "loopback")
                .put("type", "loopback")
                .put("mcp_http", "$loopbackBase/mcp")
                .put("mcp_ws", "ws://${i.localHost}:${i.port}/ws")
                .put("discover", "$loopbackBase/discover")
                .put("health", "$loopbackBase/health")
                .put("bind", i.localHost)
                .put("reachable_from", "on-device clients (Termux, local apps) or a host running `adb forward tcp:${i.port} tcp:${i.port}`")
                .put("auth_required", true)
        )
        for (addr in i.tailscaleAddresses.distinct()) {
            val direct = addr in i.boundAddresses
            val o = JSONObject()
                .put("id", "tailscale_$addr")
                .put("type", "tailscale")
                .put("address", addr)
                .put("direct_mcp", direct)
                .put("auth_required", true)
            if (direct) {
                val base = "http://$addr:${i.port}"
                o.put("mcp_http", "$base/mcp")
                    .put("mcp_ws", "ws://$addr:${i.port}/ws")
                    .put("discover", "$base/discover")
                    .put("health", "$base/health")
                    .put("bind", addr)
                    .put("reachable_from", "any device on the owner's tailnet (survives reboot + Wi-Fi change; no adb needed)")
            } else {
                o.put(
                    "how",
                    "Server not bound on $addr yet. From a tailnet peer: `adb connect $addr:<adb-port>` then " +
                        "`adb forward tcp:${i.port} tcp:${i.port}` and use http://127.0.0.1:${i.port}/mcp"
                )
            }
            connections.put(o)
        }
        for ((name, rawUrl) in i.bridgeUrls) {
            val url = sanitizeUrl(rawUrl)
            if (url.isEmpty()) continue
            connections.put(
                JSONObject()
                    .put("id", "mesh_$name")
                    .put("type", "mesh_bridge")
                    .put("bridge", name)
                    .put("mcp_http", url)
                    .put("health", healthUrlFor(url))
                    .put("note", "Public MCP bridge in the owner's mesh (box-hosted, separate auth). Reach the broker through it or relay to it with bridge_relay.")
            )
        }
        val upstream = sanitizeUrl(i.upstreamUrl)
        if (upstream.isNotEmpty()) {
            connections.put(
                JSONObject().put("id", "upstream").put("type", "upstream").put("mcp_http", upstream)
                    .put("note", "Broker's configured upstream (used by upstream_mcp_relay / exec_shell route=upstream).")
            )
        }

        val tools = JSONArray()
        for (t in i.tools.sortedBy { it.name }) {
            tools.put(JSONObject().put("name", t.name).put("risk", t.risk).put("description", oneLine(t.description)))
        }

        val txt = mdnsTxt(i)
        val manifest = JSONObject()
            .put("schema", SCHEMA)
            .put("app", JSONObject()
                .put("id", i.appId).put("name", i.appName)
                .put("version", i.versionName).put("version_code", i.versionCode))
            .put("device", JSONObject()
                .put("manufacturer", i.manufacturer).put("model", i.model)
                .put("android_release", i.androidRelease).put("sdk_int", i.sdkInt))
            .put("mcp", JSONObject()
                .put("server_info", JSONObject().put("name", i.serverName).put("version", i.serverVersion))
                .put("running", i.serverRunning)
                .put("protocol", "JSON-RPC 2.0 (Model Context Protocol)")
                .put("protocol_versions", JSONArray(i.protocolVersions))
                .put("default_protocol_version", i.defaultProtocolVersion)
                .put("methods", JSONArray(listOf("initialize", "ping", "tools/list", "tools/call", "resources/list", "prompts/list")))
                .put("transports", JSONArray()
                    .put(JSONObject().put("type", "http").put("method", "POST").put("path", "/mcp"))
                    .put(JSONObject().put("type", "websocket").put("path", "/ws").put("subprotocol", "mcp"))))
            .put("auth", JSONObject()
                .put("required", true)
                .put("scheme", "bearer")
                .put("accepted_headers", JSONArray(listOf("Authorization: Bearer <credential>", "X-Broker-Token: <credential>")))
                .put("websocket_query_param", "token")
                .put("high_risk_confirmation", "header X-Confirm: true or arguments.confirm=true")
                .put("credential_source", "Owner reveals it in the broker app (Security panel). Never served by discovery."))
            .put("open_endpoints", JSONArray(OPEN_ENDPOINTS))
            .put("connections", connections)
            .put("mesh_base", sanitizeUrl(i.meshBaseUrl))
            .put("mdns", JSONObject()
                .put("service_types", JSONArray(MDNS_SERVICE_TYPES))
                .put("service_name", i.mdnsServiceName)
                .put("registered", i.mdnsRegistered)
                .put("port", i.port)
                .put("txt", JSONObject(txt as Map<*, *>)))
            .put("tool_count", i.tools.size)
            .put("tools", tools)
            .put("limits", JSONObject()
                .put("wifi_detection", "Default ON: nearby access points with RSSI as heard by this phone + presence score (tool wifi_detection). Near-device only.")
                .put("hotspot_extender", "hotspot_start/stop/status: shell soft AP sharing the phone's internet (repeater if STA+AP concurrency). Cannot boost other phones' radios.")
                .put("wifi_presence", "Coarse Wi-Fi RSSI/link-variance presence & motion near the device only. Cannot see through walls, no CSI, cannot identify, count or locate people.")
                .put("zebra", "Uses only no-license public Zebra/Android APIs (DataWedge intents, WifiManager, DPM). No Mobility DNA license bypass."))
            .put("credentials_included", false)
        return redactKnownSecrets(manifest, i.knownSecrets) as JSONObject
    }

    /** mDNS TXT attributes (each value well under the 255-byte TXT limit, no secrets). */
    fun mdnsTxt(i: Inputs): Map<String, String> {
        val m = linkedMapOf(
            "path" to "/discover",
            "wk" to "/.well-known/mcp.json",
            "mcp" to "/mcp",
            "ws" to "/ws",
            "auth" to "bearer",
            "bind" to i.localHost,
            "ver" to i.versionName,
            "id" to i.appId
        )
        val androidBridge = sanitizeUrl(i.bridgeUrls["android"].orEmpty())
        if (androidBridge.isNotEmpty() && androidBridge.length <= 200) m["mesh"] = androidBridge
        return m
    }

    /** First sentence / line of a tool description, capped. */
    fun oneLine(desc: String, max: Int = 160): String {
        val firstLine = desc.trim().lineSequence().firstOrNull().orEmpty()
        val dot = firstLine.indexOf(". ")
        val s = if (dot in 1 until max) firstLine.substring(0, dot + 1) else firstLine
        return if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
    }

    /** Drop user-info, query and fragment; empty for blank / non-http(s) input. */
    fun sanitizeUrl(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        return try {
            val u = URI(v)
            val scheme = u.scheme?.lowercase() ?: return ""
            if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return ""
            val host = u.host ?: return ""
            val port = if (u.port > 0) ":${u.port}" else ""
            val path = u.rawPath.orEmpty()
            "$scheme://$host$port$path".trimEnd('/')
        } catch (_: Exception) {
            ""
        }
    }

    /** …/android/mcp → …/android/health; root /mcp → /health. */
    fun healthUrlFor(mcpUrl: String): String {
        val u = sanitizeUrl(mcpUrl)
        if (u.isEmpty()) return ""
        if (u.endsWith("/mcp")) return u.removeSuffix("/mcp") + "/health"
        return try {
            val x = URI(u)
            "${x.scheme}://${x.host}${if (x.port > 0) ":${x.port}" else ""}/health"
        } catch (_: Exception) {
            ""
        }
    }

    /** True for an IPv4 literal in Tailscale's CGNAT range 100.64.0.0/10. */
    fun isTailscaleV4(addr: String): Boolean {
        val p = addr.split('.')
        if (p.size != 4) return false
        val o = p.map { it.toIntOrNull() ?: return false }
        if (o.any { it !in 0..255 }) return false
        return o[0] == 100 && o[1] in 64..127
    }

    /**
     * Recursively replaces any string equal to (or containing) a known secret with "[redacted]" and
     * removes keys that look credential-bearing. Returns the same (mutated) object.
     */
    fun redactKnownSecrets(node: Any?, secrets: List<String>): Any? {
        val real = secrets.map { it.trim() }.filter { it.length >= 8 }
        fun scrub(v: Any?): Any? = when (v) {
            is JSONObject -> {
                val keys = v.keys().asSequence().toList()
                for (k in keys) {
                    if (isForbiddenKey(k)) v.remove(k) else v.put(k, scrub(v.get(k)))
                }
                v
            }
            is JSONArray -> {
                for (idx in 0 until v.length()) v.put(idx, scrub(v.get(idx)))
                v
            }
            is String -> if (real.any { v.contains(it) }) "[redacted]" else v
            else -> v
        }
        return scrub(node)
    }

    fun isForbiddenKey(key: String): Boolean {
        val k = key.lowercase()
        return FORBIDDEN_KEY_FRAGMENTS.any { k.contains(it) }
    }

    /** All keys in the tree (for tests / self-checks). */
    fun allKeys(node: Any?, out: MutableList<String> = mutableListOf()): List<String> {
        when (node) {
            is JSONObject -> node.keys().forEach { k -> out.add(k); allKeys(node.get(k), out) }
            is JSONArray -> for (i in 0 until node.length()) allKeys(node.get(i), out)
        }
        return out
    }

    /** All string values in the tree (for tests / self-checks). */
    fun allStrings(node: Any?, out: MutableList<String> = mutableListOf()): List<String> {
        when (node) {
            is JSONObject -> node.keys().forEach { k -> allStrings(node.get(k), out) }
            is JSONArray -> for (i in 0 until node.length()) allStrings(node.get(i), out)
            is String -> out.add(node)
        }
        return out
    }
}
