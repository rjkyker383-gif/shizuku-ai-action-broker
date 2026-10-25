package com.rjkyker.shizukuai.broker.mcp

import android.content.Context
import android.util.Log
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified mesh relay that forwards one JSON-RPC MCP call to a *named* upstream bridge.
 *
 * Bridges:
 *  - "android", "spark", "ether", "war", "magic": each has its own URL in SecurePrefs, prefilled with
 *    the built-in public mesh default <mesh base>/<name>/mcp (editable in the app).
 *  - "upstream": the app's saved "Upstream MCP URL" setting (SecurePrefs.getUpstreamMcpUrl).
 *
 * Nothing is hardcoded to 192.168.1.158 — every target URL comes from SecurePrefs and is empty
 * until the user sets it. Each bridge is health-checked (GET /health on the same origin) before a
 * relay is attempted; an unreachable bridge returns ok=false rather than a faked result.
 *
 * Exposed as the MCP tool `bridge_relay{bridge, method, params}` (MEDIUM risk) with the same bearer
 * auth as the rest of the broker.
 */
class BridgeMeshRelay(private val context: Context) {

    private val nextId = AtomicLong(1)

    /** The bridges this relay knows about. "upstream" reuses the main Upstream MCP URL. */
    fun bridgeNames(): List<String> = BRIDGES

    /** Resolve a bridge's configured MCP URL (empty string when unset). */
    fun urlFor(bridge: String): String = when (bridge.lowercase()) {
        "upstream" -> SecurePrefs.getUpstreamMcpUrl(context)
        "android", "spark", "magic", "war", "ether" -> SecurePrefs.getBridgeUrl(context, bridge.lowercase())
        else -> ""
    }

    private fun tokenFor(bridge: String): String {
        val b = bridge.lowercase()
        val perBridge = if (b == "upstream") "" else SecurePrefs.getBridgeToken(context, b)
        return perBridge.ifBlank { SecurePrefs.getUpstreamMcpToken(context) }
    }

    /** Host-only (never secrets) for status output. */
    fun displayHost(bridge: String): String {
        val u = urlFor(bridge)
        if (u.isBlank()) return ""
        return try {
            val url = URL(u)
            buildString {
                append(url.host)
                if (url.port > 0 && url.port != url.defaultPort) append(':').append(url.port)
            }
        } catch (_: Exception) {
            u.take(48)
        }
    }

    /** Status of every bridge: configured? + health probe host (no secrets). */
    fun status(timeoutMs: Long = 4_000L): JSONObject {
        val out = JSONObject()
        for (b in BRIDGES) {
            val url = urlFor(b)
            val entry = JSONObject()
                .put("configured", url.isNotBlank())
                .put("host", displayHost(b))
            if (url.isNotBlank()) {
                val h = health(b, timeoutMs)
                entry.put("healthy", h.optBoolean("ok", false))
                if (!h.optBoolean("ok", false)) entry.put("health_error", h.optString("error"))
            }
            out.put(b, entry)
        }
        return JSONObject().put("ok", true).put("bridges", out)
    }

    /** GET {origin}/health on the bridge. ok=true only on HTTP 2xx. */
    fun health(bridge: String, timeoutMs: Long = 4_000L): JSONObject {
        val url = urlFor(bridge)
        if (url.isBlank()) {
            return err("bridge '$bridge' has no URL set", "Set it in the broker app (bridge URLs).")
        }
        val healthUrl = healthUrlFor(url)
            ?: return err("could not derive health URL from $bridge URL")
        return try {
            val conn = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs.toInt().coerceIn(500, 20_000)
                readTimeout = timeoutMs.toInt().coerceIn(500, 20_000)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("ngrok-skip-browser-warning", "1")
                val token = tokenFor(bridge)
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use {
                    BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
                }.orEmpty()
                if (code in 200..299) {
                    JSONObject().put("ok", true).put("bridge", bridge)
                        .put("health_host", displayHost(bridge))
                        .put("http", code)
                } else {
                    err("health HTTP $code from $bridge: ${text.take(160)}")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            err("health check failed for $bridge: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Relay [method] (default tools/call) with [params] to [bridge]. Health-checks first; on an
     * unhealthy bridge it returns ok=false and does NOT attempt the call.
     */
    fun relay(
        bridge: String,
        method: String = "tools/call",
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 15_000L
    ): JSONObject {
        val b = bridge.lowercase()
        if (b !in BRIDGES) {
            return err("unknown bridge '$bridge'; must be ${BRIDGES.joinToString("|")}")
        }
        val url = urlFor(b)
        if (url.isBlank()) {
            return err(
                "bridge '$b' not configured",
                "Set the $b bridge URL in the broker app. No URL is hardcoded."
            )
        }
        val h = health(b, timeoutMs.coerceAtMost(5_000L))
        if (!h.optBoolean("ok", false)) {
            return JSONObject().put("ok", false)
                .put("error", "bridge '$b' failed health check; not relaying")
                .put("health", h)
                .put("bridge", b)
                .put("bridge_host", displayHost(b))
        }
        val r = callRpc(url, method.ifBlank { "tools/call" }, params, tokenFor(b), timeoutMs)
        return r.put("bridge", b).put("method", method).put("bridge_host", displayHost(b))
    }

    // ---- HTTP / JSON-RPC plumbing (self-contained; never invents a result) ----

    private fun callRpc(
        url: String,
        method: String,
        params: JSONObject,
        token: String,
        timeoutMs: Long
    ): JSONObject {
        return try {
            val id = nextId.getAndIncrement()
            val body = JSONObject()
                .put("jsonrpc", "2.0").put("id", id)
                .put("method", method).put("params", params)
            val raw = post(url, body.toString(), token, timeoutMs)
            parseRpc(raw, url)
        } catch (e: Exception) {
            err("relay call failed: ${e.javaClass.simpleName}: ${e.message}", url = url)
        }
    }

    private fun post(url: String, body: String, token: String, timeoutMs: Long): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs.toInt().coerceIn(1_000, 60_000)
            readTimeout = timeoutMs.toInt().coerceIn(1_000, 120_000)
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("ngrok-skip-browser-warning", "1")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(300)}")
            // Unwrap SSE "data:" if present.
            if (text.trimStart().startsWith("event:") || text.contains("\ndata:")) {
                text.lineSequence().map { it.trim() }
                    .firstOrNull { it.startsWith("data:") }
                    ?.removePrefix("data:")?.trim() ?: text
            } else text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRpc(raw: String, url: String): JSONObject {
        if (raw.isBlank()) return err("empty response", url = url)
        val parsed = try {
            JSONTokener(raw.trim()).nextValue()
        } catch (_: Exception) {
            return err("non-JSON response: ${raw.take(200)}", url = url)
        }
        val obj = when (parsed) {
            is JSONObject -> parsed
            is JSONArray -> parsed.optJSONObject(0) ?: return err("empty batch", url = url)
            else -> return err("unexpected JSON type", url = url)
        }
        if (obj.has("error")) {
            val e = obj.optJSONObject("error")
            return JSONObject().put("ok", false)
                .put("error", e?.optString("message") ?: obj.optString("error"))
                .put("code", e?.optInt("code") ?: JSONObject.NULL)
                .put("bridge_url", redact(url))
                .put("jsonrpc_error", e ?: obj.opt("error"))
        }
        return JSONObject().put("ok", true)
            .put("result", obj.opt("result") ?: JSONObject.NULL)
            .put("bridge_url", redact(url))
    }

    private fun healthUrlFor(mcpUrl: String): String? = try {
        val u = URL(mcpUrl)
        val portPart = if (u.port > 0 && u.port != u.defaultPort) ":${u.port}" else ""
        // Path-mounted mesh bridges (…/android/mcp) expose …/android/health; root MCPs use /health.
        val prefix = u.path.orEmpty().trimEnd('/').removeSuffix("/mcp")
        "${u.protocol}://${u.host}$portPart$prefix/health"
    } catch (_: Exception) {
        null
    }

    private fun redact(url: String): String = try {
        val u = URL(url)
        "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}${u.path}"
    } catch (_: Exception) {
        url.take(64)
    }

    private fun err(msg: String, hint: String? = null, url: String? = null): JSONObject =
        JSONObject().put("ok", false).put("error", msg).also {
            if (hint != null) it.put("hint", hint)
            if (url != null) it.put("bridge_url", redact(url))
        }

    companion object {
        private const val TAG = "BridgeMeshRelay"
        val BRIDGES = listOf("android", "spark", "ether", "war", "magic", "upstream")
    }
}
