package com.rjkyker.shizukuai.broker.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Log
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal MCP JSON-RPC client for an upstream MCP server (Streamable HTTP POST).
 *
 * Primary URL is whatever the user set in SecurePrefs (ngrok / Cloudflare tunnel / Tailscale
 * MagicDNS or 100.x IP / any https endpoint). It must work on ANY wifi — do not rely on
 * 192.168.x.x for the primary. An optional LAN fallback is tried only when the primary fails
 * AND the phone currently has an IPv4 address in that fallback's /24.
 *
 * - initialize + notifications/initialized once per process (session id header when returned)
 * - tools/list and tools/call
 * - Never invents results: network / HTTP / JSON-RPC errors become ok=false payloads
 */
class UpstreamMcpRelayClient(private val context: Context) {

    @Volatile private var sessionId: String? = null
    @Volatile private var initializedForUrl: String? = null
    private val nextId = AtomicLong(1)

    fun configuredUrl(): String = SecurePrefs.getUpstreamMcpUrl(context)

    fun lanFallbackUrl(): String = SecurePrefs.getUpstreamLanFallbackUrl(context)

    fun isConfigured(): Boolean = configuredUrl().isNotBlank()

    /** Host-only string for notifications (never secrets). Empty when not configured. */
    fun displayHost(): String {
        val u = configuredUrl()
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

    fun call(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 15_000L
    ): JSONObject {
        val primary = configuredUrl()
        if (primary.isBlank()) {
            return err(
                "upstream not configured",
                "Set the upstream MCP URL in the broker app (any https tunnel or Tailscale " +
                    "http://100.x.x.x:port/mcp). 192.168.x.x only works on home Wi-Fi — put that " +
                    "in the optional LAN fallback, not as the primary."
            )
        }
        val primaryResult = callUrl(primary, method, params, timeoutMs)
        if (primaryResult.optBoolean("ok", false) || !isTransportFailure(primaryResult)) {
            return primaryResult
        }
        val fallback = lanFallbackUrl()
        if (fallback.isBlank() || fallback == primary) return primaryResult
        if (!isOnSameSubnetAs(fallback)) {
            return primaryResult.put(
                "lan_fallback_skipped",
                "phone not on fallback subnet; primary error kept"
            )
        }
        Log.i(TAG, "primary failed (${primaryResult.optString("error")}); trying LAN fallback")
        val fb = callUrl(fallback, method, params, timeoutMs)
        return fb.put("used_lan_fallback", true)
            .put("primary_error", primaryResult.optString("error", "primary failed"))
    }

    fun toolsList(timeoutMs: Long = 15_000L): JSONObject =
        call("tools/list", JSONObject(), timeoutMs)

    fun toolsCall(name: String, arguments: JSONObject, timeoutMs: Long = 15_000L): JSONObject =
        call(
            "tools/call",
            JSONObject().put("name", name).put("arguments", arguments),
            timeoutMs
        )

    private fun callUrl(
        baseUrl: String,
        method: String,
        params: JSONObject,
        timeoutMs: Long
    ): JSONObject {
        return try {
            ensureInitialized(baseUrl, timeoutMs)
            val id = nextId.getAndIncrement()
            val req = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params)
            val raw = post(baseUrl, req.toString(), timeoutMs)
            parseRpc(raw, id, baseUrl)
        } catch (e: Exception) {
            err("upstream call failed: ${e.javaClass.simpleName}: ${e.message}", url = baseUrl)
        }
    }

    @Synchronized
    private fun ensureInitialized(baseUrl: String, timeoutMs: Long) {
        if (initializedForUrl == baseUrl && sessionId != null) return
        // Reset session when URL changes.
        if (initializedForUrl != baseUrl) {
            sessionId = null
            initializedForUrl = null
        }
        val id = nextId.getAndIncrement()
        val init = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", "initialize")
            .put(
                "params",
                JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject())
                    .put(
                        "clientInfo",
                        JSONObject().put("name", "shizuku-ai-action-broker").put("version", "1.6.0-dev")
                    )
            )
        val (headers, body) = postWithHeaders(baseUrl, init.toString(), timeoutMs)
        headers["mcp-session-id"]?.let { sessionId = it }
        headers["Mcp-Session-Id"]?.let { sessionId = it }
        parseRpc(body, id, baseUrl).also {
            if (!it.optBoolean("ok", false) && it.has("error")) {
                throw IllegalStateException("upstream initialize failed: ${it.optString("error")}")
            }
        }
        // notifications/initialized (no response expected)
        try {
            val note = JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
            post(baseUrl, note.toString(), timeoutMs.coerceAtMost(5_000L))
        } catch (_: Exception) {
        }
        initializedForUrl = baseUrl
    }

    private fun parseRpc(raw: String, expectedId: Long, url: String): JSONObject {
        if (raw.isBlank()) return err("empty upstream response", url = url)
        val parsed = try {
            JSONTokener(raw.trim()).nextValue()
        } catch (e: Exception) {
            return err("upstream returned non-JSON: ${raw.take(200)}", url = url)
        }
        val obj = when (parsed) {
            is JSONObject -> parsed
            is JSONArray -> parsed.optJSONObject(0)
                ?: return err("upstream returned empty batch", url = url)
            else -> return err("upstream returned unexpected JSON type", url = url)
        }
        if (obj.has("error")) {
            val e = obj.optJSONObject("error")
            return JSONObject()
                .put("ok", false)
                .put("error", e?.optString("message") ?: obj.optString("error"))
                .put("code", e?.optInt("code") ?: JSONObject.NULL)
                .put("upstream_url", redactUrl(url))
                .put("jsonrpc_error", e ?: obj.opt("error"))
        }
        val result = obj.opt("result")
        return JSONObject()
            .put("ok", true)
            .put("result", result ?: JSONObject.NULL)
            .put("upstream_url", redactUrl(url))
            .put("id", obj.opt("id") ?: expectedId)
    }

    private fun post(url: String, body: String, timeoutMs: Long): String =
        postWithHeaders(url, body, timeoutMs).second

    private fun postWithHeaders(
        url: String,
        body: String,
        timeoutMs: Long
    ): Pair<Map<String, String>, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs.toInt().coerceIn(1_000, 60_000)
            readTimeout = timeoutMs.toInt().coerceIn(1_000, 120_000)
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            val token = SecurePrefs.getUpstreamMcpToken(context)
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() }.orEmpty()
            val hdrs = (conn.headerFields
                ?.filterKeys { it != null }
                ?.mapKeys { it.key!!.lowercase() }
                ?.mapValues { it.value?.firstOrNull().orEmpty() }
                ?: emptyMap()).toMutableMap()
            conn.getHeaderField("Mcp-Session-Id")?.let { hdrs["mcp-session-id"] = it }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from upstream: ${text.take(300)}")
            }
            val outHeaders = hdrs
            // SSE: take the first data: JSON line if present
            val jsonBody = if (text.trimStart().startsWith("event:") || text.contains("\ndata:")) {
                text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("data:") }
                    ?.removePrefix("data:")
                    ?.trim()
                    ?: text
            } else text
            outHeaders to jsonBody
        } finally {
            conn.disconnect()
        }
    }

    private fun isTransportFailure(r: JSONObject): Boolean {
        val e = r.optString("error", "").lowercase()
        return e.contains("failed") || e.contains("http ") || e.contains("timeout") ||
            e.contains("unreachable") || e.contains("connect") || e.contains("unknownhost") ||
            e.contains("empty upstream")
    }

    /** True when the phone has an IPv4 address in the same /24 as the host of [url]. */
    private fun isOnSameSubnetAs(url: String): Boolean {
        val host = try {
            URL(url).host
        } catch (_: Exception) {
            return false
        }
        // Only useful for literal IPv4 LAN hosts.
        val hostOctets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (hostOctets.size != 4) return false
        if (hostOctets[0] !in listOf(10, 172, 192)) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val lp: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return false
        return lp.linkAddresses.any { la ->
            val a = la.address
            a is Inet4Address && run {
                val o = a.hostAddress?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return@run false
                o.size == 4 && o[0] == hostOctets[0] && o[1] == hostOctets[1] && o[2] == hostOctets[2]
            }
        }
    }

    private fun redactUrl(url: String): String = try {
        val u = URL(url)
        "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}${u.path}"
    } catch (_: Exception) {
        url.take(64)
    }

    private fun err(msg: String, hint: String? = null, url: String? = null): JSONObject =
        JSONObject().put("ok", false).put("error", msg).also {
            if (hint != null) it.put("hint", hint)
            if (url != null) it.put("upstream_url", redactUrl(url))
        }

    companion object {
        private const val TAG = "UpstreamMcp"
    }
}
