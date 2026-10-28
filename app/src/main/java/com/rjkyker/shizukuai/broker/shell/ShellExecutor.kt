package com.rjkyker.shizukuai.broker.shell

import android.content.Context
import android.util.Log
import com.rjkyker.shizukuai.broker.bridge.ShellResult
import com.rjkyker.shizukuai.broker.bridge.ShizukuBridge
import com.rjkyker.shizukuai.broker.bridge.ShizukuGateway
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shell multiplexer over [ShizukuBridge].
 *
 * - [execSync]: one-shot with separate stdout/stderr/exit_code
 * - [startSession]: short-lived background job with a capped log buffer (max [MAX_SESSIONS]
 *   concurrent). Not a true interactive PTY — Android would need native code / Termux for that;
 *   these sessions just run `sh -c` off-thread and collect output until exit or timeout.
 */
object ShellExecutor {

    private const val TAG = "ShellExecutor"
    private const val MAX_SESSIONS = 3
    private const val DEFAULT_TIMEOUT_SEC = 30L
    private const val MAX_LOG_CHARS = 64 * 1024

    private val pool = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, Session>()

    data class Session(
        val id: String,
        val command: String,
        val startedAtMs: Long,
        val log: StringBuilder = StringBuilder(),
        @Volatile var exitCode: Int? = null,
        @Volatile var done: Boolean = false,
        @Volatile var timedOut: Boolean = false,
        @Volatile var via: String = "",
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        var future: Future<*>? = null
    )

    fun execSync(command: String, timeoutSec: Long = DEFAULT_TIMEOUT_SEC): ShellResult =
        ShizukuBridge.execSeparate(command, timeoutSec.coerceIn(1L, 300L))

    fun startSession(
        command: String,
        timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
        route: String = "auto",
        context: Context? = null
    ): JSONObject {
        pruneFinished()
        if (sessions.size >= MAX_SESSIONS) {
            return JSONObject().put("ok", false)
                .put("error", "max concurrent sessions ($MAX_SESSIONS) reached; poll/kill one first")
                .put("active", listSessions())
        }
        if (command.isBlank()) return JSONObject().put("ok", false).put("error", "command required")
        if (command.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
            return JSONObject().put("ok", false).put("error", "command exceeds 64 KiB limit")
        }
        val normalizedRoute = route.lowercase()
        if (normalizedRoute !in setOf("auto", "shizuku", "termux", "local")) {
            return JSONObject().put("ok", false).put("error", "session route must be auto|shizuku|termux|local")
        }
        if (normalizedRoute == "termux" && context == null) {
            return JSONObject().put("ok", false).put("error", "Termux session requires app context")
        }
        if (normalizedRoute == "shizuku" && !ShizukuGateway.isReady()) {
            return JSONObject().put("ok", false).put("error", "Shizuku is not ready; start Shizuku and grant permission")
        }
        val id = UUID.randomUUID().toString().take(8)
        val session = Session(id = id, command = command, startedAtMs = System.currentTimeMillis())
        sessions[id] = session
        val t = timeoutSec.coerceIn(1L, 300L)
        session.future = pool.submit {
            try {
                val selected = when (normalizedRoute) {
                    "termux" -> TermuxShellBridge.exec(context!!, command, t)
                    "shizuku" -> ShizukuBridge.execSeparate(command, t)
                    "local" -> ShizukuBridge.execSeparate(command, t)
                    else -> when {
                        ShizukuGateway.isReady() -> ShizukuBridge.execSeparate(command, t)
                        context != null && TermuxShellBridge.isAvailable(context) -> TermuxShellBridge.exec(context, command, t)
                        else -> ShizukuBridge.execSeparate(command, t)
                    }
                }
                val r = selected
                synchronized(session.log) {
                    appendCapped(session.log, r.stdout)
                    if (r.stderr.isNotEmpty()) appendCapped(session.log, "\n[stderr]\n${r.stderr}")
                }
                session.exitCode = r.exitCode
                session.via = r.via
                session.timedOut = r.timedOut
            } catch (e: Exception) {
                synchronized(session.log) { appendCapped(session.log, "error: ${e.message}") }
                session.exitCode = -1
            } finally {
                session.done = true
            }
        }
        Log.i(TAG, "session $id started route=$normalizedRoute")
        return JSONObject().put("ok", true).put("session_id", id)
            .put("note", "Not a PTY — background sh -c collector. Poll with session_poll, stop with session_kill.")
            .put("timeout_sec", t)
            .put("route", normalizedRoute)
    }

    fun pollSession(id: String, consumeLog: Boolean = false): JSONObject {
        val s = sessions[id] ?: return JSONObject().put("ok", false).put("error", "unknown session: $id")
        val log: String
        synchronized(s.log) {
            log = s.log.toString()
            if (consumeLog) s.log.setLength(0)
        }
        return JSONObject()
            .put("ok", true)
            .put("session_id", id)
            .put("command", s.command)
            .put("done", s.done)
            .put("timed_out", s.timedOut)
            .put("exit_code", s.exitCode ?: JSONObject.NULL)
            .put("via", s.via)
            .put("elapsed_ms", System.currentTimeMillis() - s.startedAtMs)
            .put("log", log)
    }

    fun killSession(id: String): JSONObject {
        val s = sessions.remove(id)
            ?: return JSONObject().put("ok", false).put("error", "unknown session: $id")
        s.cancelled.set(true)
        s.future?.cancel(true)
        return JSONObject().put("ok", true).put("session_id", id).put("was_done", s.done)
            .put("note", "Future cancelled; underlying Shizuku process may still finish its timeout")
    }

    fun listSessions(): JSONArray {
        pruneFinished(keepRecentMs = 60_000L)
        val arr = JSONArray()
        sessions.values.sortedBy { it.startedAtMs }.forEach { s ->
            arr.put(
                JSONObject()
                    .put("session_id", s.id)
                    .put("command", s.command.take(120))
                    .put("done", s.done)
                    .put("exit_code", s.exitCode ?: JSONObject.NULL)
                    .put("elapsed_ms", System.currentTimeMillis() - s.startedAtMs)
            )
        }
        return arr
    }

    private fun pruneFinished(keepRecentMs: Long = 0L) {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, s) ->
            s.done && (keepRecentMs <= 0L || now - s.startedAtMs > keepRecentMs)
        }
    }

    private fun appendCapped(buf: StringBuilder, text: String) {
        val room = MAX_LOG_CHARS - buf.length
        if (room <= 0) return
        if (text.length <= room) buf.append(text) else buf.append(text, 0, room).append("…[truncated]")
    }
}
