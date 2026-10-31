package com.rjkyker.shizukuai.broker.assist

import android.accessibilityservice.AccessibilityService
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.rjkyker.shizukuai.broker.autonomy.audit.AuditLog
import com.rjkyker.shizukuai.broker.priv.PrivServiceClient
import com.rjkyker.shizukuai.broker.shell.ShellExecutor
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Typewriter insert: types text chunk-by-chunk with shell `input text` as uid 2000 (broker priv server, Shizuku
 * fallback). Before EVERY chunk it re-checks: same package, same focused editable node, screen on, field not
 * password/OTP/card. Any change -> abort ("typing stopped"). Never sends ENTER or an IME action; newlines become spaces.
 */
object Typewriter {
    private const val TAG = "Typewriter"
    private val cancel = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    fun isRunning() = running.get()
    fun stop() { cancel.set(true) }

    data class Outcome(val ok: Boolean, val typed: Int, val pasted: Int, val total: Int, val ms: Long, val via: String,
                       val stopped: String? = null) {
        fun toJson(): JSONObject = JSONObject().put("ok", ok).put("typed_chars", typed).put("pasted_chars", pasted)
            .put("len", total).put("ms", ms).put("via", via).put("stopped", stopped).put("submitted", false)
            .put("chars_per_sec", if (ms > 0) "%.1f".format(typed * 1000.0 / ms) else null)
    }

    private fun shell(cmd: String): Pair<Boolean, String> {
        if (!TypeCommand.isAllowed(cmd)) return false to "blocked"
        if (PrivServiceClient.isAlive()) {
            val r = PrivServiceClient.exec(cmd, 5)
            if (r.optBoolean("ok")) return true to "priv_server"
        }
        val s = ShellExecutor.execSync(cmd, 5)
        return (s.exitCode == 0 && !s.timedOut) to "shizuku"
    }

    /** Blocking; call from a worker thread. */
    fun type(svc: AccessibilityService, raw: String, speed: Double, onState: (Boolean) -> Unit = {}): Outcome {
        if (!running.compareAndSet(false, true)) return Outcome(false, 0, 0, raw.length, 0, "", "already typing")
        cancel.set(false)
        val t0 = SystemClock.elapsedRealtime()
        var typed = 0; var pasted = 0; var via = ""
        val text = TypeCommand.sanitize(raw)
        val start = FocusedInput.findNode(svc)
        val pkg = start?.packageName?.toString()
        fun finish(ok: Boolean, stopped: String?): Outcome {
            running.set(false); onState(false)
            val o = Outcome(ok, typed, pasted, text.length, SystemClock.elapsedRealtime() - t0, via, stopped)
            AuditLog.record(svc, "USER_DEVICE", "input_focused_insert", "HIGH", if (ok) "ALLOW" else "ERROR",
                "typewriter (never submits)", null,
                outcome = "pkg=$pkg len=${text.length} typed=$typed pasted=$pasted ms=${o.ms} via=$via${stopped?.let { " stopped=$it" } ?: ""}")
            Log.i(TAG, "typewriter pkg=$pkg len=${text.length} typed=$typed ms=${o.ms} stopped=$stopped")
            return o
        }
        if (start == null || pkg == null) return finish(false, "no focused editable field")
        if (FieldGuard.blockReason(FocusedInput.guardField(start)) != null) return finish(false, "sensitive field")
        if (text.isBlank()) return finish(false, "nothing to type")
        onState(true)
        val pm = svc.getSystemService(PowerManager::class.java)
        for (chunk in TypeCommand.chunks(text)) {
            // ---- re-check before EVERY chunk ----
            if (cancel.get()) return finish(false, "stopped by owner")
            if (pm != null && !pm.isInteractive) return finish(false, "screen off")
            val now = FocusedInput.findNode(svc) ?: return finish(false, "focus lost")
            if (now.packageName?.toString() != pkg) return finish(false, "app changed")
            if (now != start) return finish(false, "focus moved to another field")
            if (!now.isEditable || FieldGuard.blockReason(FocusedInput.guardField(now)) != null) return finish(false, "sensitive field focused")
            val cmd = TypeCommand.command(chunk)
            if (cmd != null) {
                val (ok, how) = shell(cmd)
                if (!ok) return finish(false, "input text failed ($how)")
                via = how; typed += chunk.length
            } else {
                // emoji / non-ASCII: accessibility splice at the cursor (still no submit)
                val r = FocusedInput.insert(svc, chunk, "append", allowShell = false)
                if (!r.optBoolean("ok")) { Log.i(TAG, "skipped ${chunk.length} non-ASCII chars"); continue }
                pasted += chunk.length
            }
            SystemClock.sleep(TypeCommand.delayMs(chunk, speed))
        }
        return finish(true, null)
    }

    /** a11y node equality = same window + same source node id. */
    @Suppress("unused") private fun same(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo) = a == b
}
