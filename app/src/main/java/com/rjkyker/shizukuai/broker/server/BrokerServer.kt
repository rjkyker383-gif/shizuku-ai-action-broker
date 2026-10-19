package com.rjkyker.shizukuai.broker.server

import android.content.Context
import android.util.Log
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight localhost HTTP server on 127.0.0.1:17999 (ServerSocket, no NanoHTTPD).
 * POST /action — JSON body via ActionRouter
 * GET /health
 * GET /ui — accessibility snapshot
 * GET /datawedge — persisted DataWedge replies (status/version/profiles/active_profile/scan)
 * GET /datawedge/query?q=all|status|version|profiles|active_profile — live query, waits for reply
 */
class BrokerServer(private val context: Context) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private val pool = Executors.newCachedThreadPool()
    private val router = ActionRouter(context.applicationContext)

    val isRunning: Boolean get() = running.get()
    val port: Int get() = PORT

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            try {
                val ss = ServerSocket(PORT, 50, InetAddress.getByName(HOST))
                serverSocket = ss
                Log.i(TAG, "BrokerServer listening on $HOST:$PORT")
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
        socket.soTimeout = 15_000
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = input.readLine() ?: return writeResponse(socket, 400, "text/plain", "Bad Request")
            val parts = requestLine.split(" ")
            if (parts.size < 2) return writeResponse(socket, 400, "text/plain", "Bad Request")
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val queryParams = parts[1].substringAfter('?', "").split('&')
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

            var contentLength = 0
            var line: String?
            while (true) {
                line = input.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            when {
                method == "GET" && path == "/health" -> {
                    val json = JSONObject()
                        .put("ok", true)
                        .put("port", PORT)
                        .put("accessibility", AgentAccessibilityService.isEnabled())
                        .put("package", context.packageName)
                        .put("version", versionName())
                    writeResponse(socket, 200, "application/json", json.toString())
                }
                method == "GET" && (path == "/ui" || path == "/uidump") -> {
                    val snap = router.route(JSONObject().put("op", "ui_dump"))
                    writeResponse(socket, 200, "application/json", snap.toString())
                }
                method == "GET" && path == "/datawedge" -> {
                    val r = router.route(JSONObject().put("op", "datawedge_results"))
                        .put("live", DataWedgeResultReceiver.snapshot())
                    writeResponse(socket, 200, "application/json", r.toString())
                }
                method == "GET" && path == "/datawedge/query" -> {
                    val r = router.route(
                        JSONObject().put("op", "datawedge_query")
                            .put("query", queryParams["q"] ?: queryParams["query"] ?: "all")
                    )
                    writeResponse(socket, 200, "application/json", r.toString())
                }
                method == "POST" && path == "/action" -> {
                    val jsonBody = try {
                        if (body.isBlank()) JSONObject() else JSONObject(body)
                    } catch (e: Exception) {
                        writeResponse(
                            socket, 400, "application/json",
                            JSONObject().put("ok", false).put("error", "invalid JSON").toString()
                        )
                        return
                    }
                    val result = router.route(jsonBody)
                    writeResponse(socket, 200, "application/json", result.toString())
                }
                else -> writeResponse(
                    socket, 404, "application/json",
                    JSONObject().put("ok", false).put("error", "not found").toString()
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

    private fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun writeResponse(socket: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        OutputStreamWriter(out, StandardCharsets.UTF_8).apply {
            write(header)
            flush()
        }
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val TAG = "BrokerServer"
        const val HOST = "127.0.0.1"
        const val PORT = 17999

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
