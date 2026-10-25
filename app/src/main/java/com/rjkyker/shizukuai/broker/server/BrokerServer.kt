package com.rjkyker.shizukuai.broker.server

import android.content.Context
import android.util.Log
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver
import com.rjkyker.shizukuai.broker.discovery.BrokerStatusSnapshot
import com.rjkyker.shizukuai.broker.discovery.DiscoveryProvider
import com.rjkyker.shizukuai.broker.mcp.McpToolExecutor
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Lightweight localhost HTTP(+MCP/WebSocket) server on 127.0.0.1:17999 (ServerSocket, no NanoHTTPD).
 *
 * Routes:
 * - GET  /health              — unauthenticated keepalive (no secrets); flags mcp + auth_required
 * - GET  /discover, /.well-known/mcp.json — unauthenticated read-only AI discovery manifest
 *                               (who/what/how-to-connect; never a credential)
 * - GET  /ui, /uidump         — a11y snapshot (auth required)
 * - GET  /datawedge*          — DataWedge results / live query (auth required)
 * - POST /action              — ActionRouter JSON body (auth required)
 * - POST /mcp                 — MCP JSON-RPC 2.0 (auth required)
 * - GET  /ws                  — MCP over WebSocket (auth required; Sec-WebSocket-Protocol: mcp)
 *
 * Auth: `Authorization: Bearer <token>` or `X-Broker-Token: <token>`. Token lives in SecurePrefs
 * and is generated on first use. HIGH-risk tools also need arguments.confirm=true or X-Confirm: true.
 */
class BrokerServer(private val context: Context) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private val pool = Executors.newCachedThreadPool()
    private val router = ActionRouter(context.applicationContext)
    private val tools = McpToolExecutor(context.applicationContext, router)
    private val mcp = McpJsonRpcHandler(tools)

    val isRunning: Boolean get() = running.get()
    val port: Int get() = PORT

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // Ensure a token exists before any client can hit protected routes.
        SecurePrefs.ensureMcpToken(context)
        thread = Thread({
            try {
                val ss = ServerSocket(PORT, 50, InetAddress.getByName(HOST))
                serverSocket = ss
                Log.i(TAG, "BrokerServer listening on $HOST:$PORT (mcp=/mcp ws=/ws)")
                while (running.get()) {
                    try {
                        val socket = ss.accept()
                        pool.execute { handleClient(socket) }
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "BrokerServer failed to bind $HOST:$PORT", e)
                running.set(false)
            }
        }, "BrokerServer").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        thread = null
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 30_000
        try {
            // Read the HTTP request line + headers as raw bytes so a WebSocket upgrade can keep
            // using the same InputStream for frames (BufferedReader would steal the first frame).
            val input = socket.getInputStream()
            val headerBlock = readHttpHeaders(input) ?: return writeResponse(socket, 400, "text/plain", "Bad Request")
            val lines = headerBlock.split("\r\n")
            if (lines.isEmpty()) return writeResponse(socket, 400, "text/plain", "Bad Request")
            val parts = lines[0].split(" ")
            if (parts.size < 2) return writeResponse(socket, 400, "text/plain", "Bad Request")
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val path = rawTarget.substringBefore('?')
            val queryParams = rawTarget.substringAfter('?', "").split('&')
                .filter { it.contains('=') }
                .associate {
                    java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                        java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }

            var contentLength = 0
            var upgrade = ""
            var connection = ""
            var wsKey = ""
            var wsProtocol = ""
            var authorization = ""
            var brokerToken = ""
            var confirmHeader = false
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "upgrade" -> upgrade = value.lowercase()
                    "connection" -> connection = value.lowercase()
                    "sec-websocket-key" -> wsKey = value
                    "sec-websocket-protocol" -> wsProtocol = value
                    "authorization" -> authorization = value
                    "x-broker-token" -> brokerToken = value
                    "x-confirm" -> confirmHeader = value.equals("true", ignoreCase = true) || value == "1"
                }
            }

            // WebSocket upgrade must be handled before we drain a body (there is none).
            if (method == "GET" && path == "/ws" &&
                upgrade == "websocket" && connection.contains("upgrade") && wsKey.isNotEmpty()
            ) {
                if (!isAuthorized(authorization, brokerToken, queryParams["token"])) {
                    writeResponse(
                        socket, 401, "application/json",
                        JSONObject().put("ok", false).put("error", "unauthorized")
                            .put("hint", "Authorization: Bearer <token> or X-Broker-Token (or ?token= for WS)").toString()
                    )
                    return
                }
                handleWebSocket(socket, input, wsKey, wsProtocol, confirmHeader)
                return
            }

            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                if (!readFully(input, bytes)) {
                    writeResponse(socket, 400, "application/json",
                        JSONObject().put("ok", false).put("error", "truncated body").toString())
                    return
                }
                String(bytes, StandardCharsets.UTF_8)
            } else ""

            when {
                method == "OPTIONS" -> writeResponse(socket, 204, "text/plain", "", cors = false)
                method == "GET" && path == "/health" -> {
                    // Open endpoint: booleans / ids only, never a secret.
                    val json = JSONObject()
                        .put("ok", true)
                        .put("port", PORT)
                        .put("accessibility", AgentAccessibilityService.isEnabled())
                        .put("package", context.packageName)
                        .put("version", versionName())
                        .put("mcp", true)
                        .put("auth_required", true)
                        .put("endpoints", JSONObject()
                            .put("mcp", "/mcp")
                            .put("ws", "/ws")
                            .put("action", "/action")
                            .put("health", "/health")
                            .put("discover", "/discover")
                            .put("well_known", "/.well-known/mcp.json"))
                    val snap = runCatching { BrokerStatusSnapshot.build(context) }.getOrNull()
                    if (snap != null) {
                        for (k in snap.keys()) json.put(k, snap.get(k))
                    }
                    writeResponse(socket, 200, "application/json", json.toString(), cors = false)
                }
                method == "GET" && (path == "/discover" || path == "/.well-known/mcp.json" ||
                    path == "/.well-known/mcp") -> {
                    // Open, read-only: advertises how to connect. Tools still need the bearer token.
                    val manifest = DiscoveryProvider.manifest(context)
                    writeResponse(socket, 200, "application/json", manifest.toString(), cors = true)
                }
                method == "GET" && path == "/ws" -> {
                    // Non-upgrade GET: tell clients how to connect.
                    writeResponse(
                        socket, 426, "application/json",
                        JSONObject().put("ok", false)
                            .put("error", "WebSocket upgrade required")
                            .put("hint", "Send Upgrade: websocket with Sec-WebSocket-Key, Authorization Bearer, and Sec-WebSocket-Protocol: mcp. Or use POST /mcp.")
                            .toString(),
                        cors = false
                    )
                }
                method == "POST" && path == "/mcp" -> {
                    if (!requireAuth(socket, authorization, brokerToken)) return
                    if (body.isBlank()) {
                        writeResponse(
                            socket, 400, "application/json",
                            JSONObject().put("ok", false).put("error", "empty body").toString()
                        )
                        return
                    }
                    val response = mcp.handle(body, confirmHeader)
                    if (response == null) {
                        writeResponse(socket, 202, "application/json", "")
                    } else {
                        writeResponse(socket, 200, "application/json", response)
                    }
                }
                method == "GET" && (path == "/ui" || path == "/uidump") -> {
                    if (!requireAuth(socket, authorization, brokerToken)) return
                    val snap = router.route(JSONObject().put("op", "ui_dump"))
                    writeResponse(socket, 200, "application/json", snap.toString())
                }
                method == "GET" && path == "/datawedge" -> {
                    if (!requireAuth(socket, authorization, brokerToken)) return
                    val r = router.route(JSONObject().put("op", "datawedge_results"))
                        .put("live", DataWedgeResultReceiver.snapshot())
                    writeResponse(socket, 200, "application/json", r.toString())
                }
                method == "GET" && path == "/datawedge/query" -> {
                    if (!requireAuth(socket, authorization, brokerToken)) return
                    val r = router.route(
                        JSONObject().put("op", "datawedge_query")
                            .put("query", queryParams["q"] ?: queryParams["query"] ?: "all")
                    )
                    writeResponse(socket, 200, "application/json", r.toString())
                }
                method == "POST" && path == "/action" -> {
                    if (!requireAuth(socket, authorization, brokerToken)) return
                    val jsonBody = try {
                        if (body.isBlank()) JSONObject() else JSONObject(body)
                    } catch (e: Exception) {
                        writeResponse(
                            socket, 400, "application/json",
                            JSONObject().put("ok", false).put("error", "invalid JSON").toString()
                        )
                        return
                    }
                    if (confirmHeader) jsonBody.put("confirm", true)
                    val result = router.route(jsonBody)
                    writeResponse(socket, 200, "application/json", result.toString())
                }
                else -> writeResponse(
                    socket, 404, "application/json",
                    JSONObject().put("ok", false).put("error", "not found")
                        .put("hint", "GET /health | GET /discover | GET /.well-known/mcp.json | POST /mcp | GET /ws | POST /action").toString()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "client error: ${e.message}")
            try {
                writeResponse(
                    socket, 500, "application/json",
                    JSONObject().put("ok", false).put("error", e.message ?: "error").toString()
                )
            } catch (_: Exception) {
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun requireAuth(socket: Socket, authorization: String, brokerToken: String): Boolean {
        if (isAuthorized(authorization, brokerToken, null)) return true
        writeResponse(
            socket, 401, "application/json",
            JSONObject().put("ok", false).put("error", "unauthorized")
                .put("hint", "Authorization: Bearer <token> or X-Broker-Token: <token>").toString()
        )
        return false
    }

    private fun isAuthorized(authorization: String, brokerToken: String, queryToken: String?): Boolean {
        val expected = SecurePrefs.ensureMcpToken(context)
        if (expected.isEmpty()) return false
        val bearer = when {
            authorization.startsWith("Bearer ", ignoreCase = true) ->
                authorization.substring(7).trim()
            authorization.startsWith("Token ", ignoreCase = true) ->
                authorization.substring(6).trim()
            else -> ""
        }
        val candidates = listOf(bearer, brokerToken, queryToken.orEmpty()).filter { it.isNotEmpty() }
        return candidates.any { constantTimeEquals(it, expected) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        // Prefer HMAC-style compare so length and early mismatches don't leak timing.
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(b.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            val ha = mac.doFinal(a.toByteArray(StandardCharsets.UTF_8))
            mac.reset()
            val hb = mac.doFinal(b.toByteArray(StandardCharsets.UTF_8))
            MessageDigest.isEqual(ha, hb)
        } catch (_: Exception) {
            a.length == b.length && a.toByteArray().zip(b.toByteArray()).fold(0) { acc, (x, y) ->
                acc or (x.toInt() xor y.toInt())
            } == 0
        }
    }

    /** Reads until CRLF CRLF. Caps at 64 KiB of headers. */
    private fun readHttpHeaders(input: InputStream): String? {
        val buf = ByteArrayOutputStream(1024)
        var state = 0 // 0 none, 1 CR, 2 CRLF, 3 CRLF CR
        while (buf.size() < 64 * 1024) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), StandardCharsets.US_ASCII)
            buf.write(b)
            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> return String(buf.toByteArray(), StandardCharsets.US_ASCII)
                b == 13 -> 1
                else -> 0
            }
        }
        return null
    }

    private fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun writeResponse(
        socket: Socket,
        code: Int,
        contentType: String,
        body: String,
        cors: Boolean = false
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            426 -> "Upgrade Required"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            if (contentType.isNotEmpty()) append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            if (cors) {
                append("Access-Control-Allow-Origin: *\r\n")
                append("Cache-Control: no-store\r\n")
            }
            append("\r\n")
        }
        val out = socket.getOutputStream()
        OutputStreamWriter(out, StandardCharsets.UTF_8).apply {
            write(header)
            flush()
        }
        if (bytes.isNotEmpty()) {
            out.write(bytes)
            out.flush()
        }
    }

    // ---------------- Minimal WebSocket (RFC 6455) for MCP JSON-RPC ----------------

    private fun handleWebSocket(
        socket: Socket,
        input: InputStream,
        wsKey: String,
        requestedProtocols: String,
        confirmHeader: Boolean
    ) {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((wsKey + WS_GUID).toByteArray(StandardCharsets.US_ASCII))
        )
        val protocol = requestedProtocols.split(',').map { it.trim() }
            .firstOrNull { it.equals("mcp", ignoreCase = true) }
        val handshake = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n")
            if (protocol != null) append("Sec-WebSocket-Protocol: $protocol\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(handshake.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        // Keep the socket open for MCP JSON-RPC frames until the client closes.
        socket.soTimeout = 0
        try {
            while (running.get() && !socket.isClosed) {
                val frame = readWsFrame(input) ?: break
                when (frame.opcode) {
                    0x8 -> { // close
                        writeWsFrame(out, 0x8, frame.payload)
                        break
                    }
                    0x9 -> writeWsFrame(out, 0xA, frame.payload) // ping → pong
                    0xA -> { /* pong ignored */ }
                    0x1 -> { // text
                        val text = String(frame.payload, StandardCharsets.UTF_8)
                        val response = mcp.handle(text, confirmHeader)
                        if (response != null) {
                            writeWsFrame(out, 0x1, response.toByteArray(StandardCharsets.UTF_8))
                        }
                    }
                    0x2 -> { // binary: treat as UTF-8 JSON
                        val text = String(frame.payload, StandardCharsets.UTF_8)
                        val response = mcp.handle(text, confirmHeader)
                        if (response != null) {
                            writeWsFrame(out, 0x1, response.toByteArray(StandardCharsets.UTF_8))
                        }
                    }
                    else -> { /* ignore reserved */ }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ws closed: ${e.message}")
        }
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun readWsFrame(input: InputStream): WsFrame? {
        val b1 = input.read()
        if (b1 < 0) return null
        val b2 = input.read()
        if (b2 < 0) return null
        val opcode = b1 and 0x0F
        val masked = (b2 and 0x80) != 0
        var len = (b2 and 0x7F).toLong()
        when (len) {
            126L -> {
                val b = ByteArray(2)
                if (!readFully(input, b)) return null
                len = ((b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)).toLong()
            }
            127L -> {
                val b = ByteArray(8)
                if (!readFully(input, b)) return null
                len = ByteBuffer.wrap(b).long
            }
        }
        if (len < 0 || len > MAX_WS_PAYLOAD) {
            Log.w(TAG, "ws payload too large: $len")
            return null
        }
        val mask = if (masked) {
            val m = ByteArray(4)
            if (!readFully(input, m)) return null
            m
        } else null
        val payload = ByteArray(len.toInt())
        if (!readFully(input, payload)) return null
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return WsFrame(opcode, payload)
    }

    private fun writeWsFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        // Server→client frames are unmasked.
        val header = ByteArrayOutputStream(10)
        header.write(0x80 or (opcode and 0x0F))
        val len = payload.size
        when {
            len < 126 -> header.write(len)
            len <= 0xFFFF -> {
                header.write(126)
                header.write((len shr 8) and 0xFF)
                header.write(len and 0xFF)
            }
            else -> {
                header.write(127)
                val buf = ByteBuffer.allocate(8).putLong(len.toLong()).array()
                header.write(buf)
            }
        }
        out.write(header.toByteArray())
        out.write(payload)
        out.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    companion object {
        private const val TAG = "BrokerServer"
        const val HOST = "127.0.0.1"
        const val PORT = 17999
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_WS_PAYLOAD = 2 * 1024 * 1024

        @Volatile
        private var instance: BrokerServer? = null

        fun get(): BrokerServer? = instance

        fun startFrom(context: Context): BrokerServer? {
            return try {
                synchronized(this) {
                    instance?.stop()
                    val server = BrokerServer(context.applicationContext)
                    server.start()
                    instance = server
                    server
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BrokerServer", e)
                null
            }
        }
    }
}
