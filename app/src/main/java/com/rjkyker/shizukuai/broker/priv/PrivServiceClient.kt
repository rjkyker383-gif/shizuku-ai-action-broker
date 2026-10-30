package com.rjkyker.shizukuai.broker.priv

import android.content.Context
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.rjkyker.shizukuai.broker.bridge.ShizukuBridge
import com.rjkyker.shizukuai.broker.bridge.ShizukuGateway
import com.rjkyker.shizukuai.broker.shell.ShellDenylist
import org.json.JSONObject
import java.io.File

/**
 * Broker-side handle on the privileged server. Honest limits: the server is uid 2000 (shell), not
 * root. Without root it needs one bootstrap per reboot, from ADB or Shizuku — the same as Shizuku.
 * Once running it does not depend on Shizuku.
 */
object PrivServiceClient {
    private const val TAG = "PrivServiceClient"

    @Volatile private var service: IBrokerPrivService? = null
    @Volatile private var serverUid = -1
    @Volatile private var serverPid = -1
    @Volatile private var receivedAt = 0L
    @Volatile var lastStartResult: String = "never started"; private set

    const val HONEST_NOTE = "Runs as uid 2000 (shell) via app_process, not root. Needs one ADB or Shizuku " +
        "bootstrap after every reboot (like Shizuku). Survives Shizuku stopping. Exits when the broker APK is updated."

    fun onBinderReceived(b: IBinder, uid: Int, pid: Int) {
        val cur = service
        if (cur != null && cur.asBinder() === b) return
        try {
            b.linkToDeath({ Log.w(TAG, "priv server died"); if (service?.asBinder() === b) service = null }, 0)
        } catch (e: Exception) { Log.w(TAG, "linkToDeath failed", e); return }
        service = IBrokerPrivService.Stub.asInterface(b)
        serverUid = uid; serverPid = pid; receivedAt = System.currentTimeMillis()
        Log.i(TAG, "priv server binder received uid=$uid pid=$pid")
    }

    fun isAlive(): Boolean = service?.asBinder()?.let { it.isBinderAlive && it.pingBinder() } == true

    private fun apkPath(ctx: Context) = ctx.applicationInfo.sourceDir
    private fun versionCode(ctx: Context): Int =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()

    fun scriptFile(ctx: Context): File = File(ctx.getExternalFilesDir(null), "priv_start.sh")

    fun writeScript(ctx: Context): File {
        val f = scriptFile(ctx)
        f.parentFile?.mkdirs()
        f.writeText(PrivProtocol.startScript(apkPath(ctx), ctx.packageName, Process.myUid(), versionCode(ctx)))
        return f
    }

    fun adbCommand(ctx: Context): String =
        PrivProtocol.adbCommand("/sdcard/Android/data/${ctx.packageName}/files/priv_start.sh")

    fun status(ctx: Context): JSONObject {
        val alive = isAlive()
        val o = JSONObject().put("ok", true).put("running", alive).put("note", HONEST_NOTE)
            .put("adb_command", adbCommand(ctx)).put("last_start", lastStartResult)
            .put("shizuku_ready", ShizukuGateway.isReady())
        if (alive) {
            runCatching {
                val s = service!!
                val vc = s.versionCode
                o.put("uid", s.uid).put("pid", s.pid).put("server_version_code", vc)
                    .put("version_match", vc == versionCode(ctx))
                    .put("binder_received_ms_ago", System.currentTimeMillis() - receivedAt)
            }.onFailure { o.put("error", it.message) }
        }
        return o
    }

    /** Bootstrap through the Shizuku shell. Off the main thread only. */
    fun start(ctx: Context, waitMs: Long = 8000L): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "PrivServiceClient.start on main thread" }
        val script = runCatching { writeScript(ctx) }.getOrNull()
        if (isAlive() && runCatching { service!!.versionCode == versionCode(ctx) }.getOrDefault(false)) {
            return status(ctx).put("result", "already_running")
        }
        if (!ShizukuGateway.isReady()) {
            lastStartResult = "shizuku_not_ready"
            return JSONObject().put("ok", false).put("error", "Shizuku not ready; bootstrap once from ADB instead")
                .put("adb_command", adbCommand(ctx)).put("script_written", script != null).put("note", HONEST_NOTE)
        }
        val cmd = PrivProtocol.startCommand(apkPath(ctx), ctx.packageName, Process.myUid(), versionCode(ctx))
        val v = ShellDenylist.check(cmd)
        if (!v.allowed) return JSONObject().put("ok", false).put("error", "bootstrap denied: ${v.reason}")
        runCatching { service?.destroy() }
        service = null
        val r = ShizukuBridge.execSeparate(cmd, 15)
        val deadline = System.currentTimeMillis() + waitMs
        while (!isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        lastStartResult = if (isAlive()) "started_via_shizuku" else "no_binder (exit=${r.exitCode} via=${r.via})"
        return status(ctx).put("ok", isAlive()).put("result", lastStartResult)
            .put("bootstrap_via", r.via).put("bootstrap_exit", r.exitCode)
            .also { if (!isAlive()) it.put("hint", "see ${PrivProtocol.LOG_PATH} (adb shell cat)") }
    }

    fun stop(): JSONObject {
        val s = service ?: return JSONObject().put("ok", true).put("result", "not_running")
        runCatching { s.destroy() }
        service = null
        lastStartResult = "stopped"
        return JSONObject().put("ok", true).put("result", "stopped")
    }

    fun exec(cmd: String, timeoutSec: Int): JSONObject {
        val v = ShellDenylist.check(cmd)
        if (!v.allowed) return JSONObject().put("ok", false).put("error", "denied: ${v.reason}").put("via", "denied")
        val s = service ?: return JSONObject().put("ok", false).put("error", "priv service not running")
        val o = JSONObject(s.exec(cmd, timeoutSec.coerceIn(1, 120)))
        return o.put("ok", o.optInt("exit_code", -1) == 0 && !o.optBoolean("timed_out")).put("via", "broker_priv_server")
    }

    fun getprop(key: String): JSONObject {
        if (!PrivProtocol.isValidPropKey(key)) return JSONObject().put("ok", false).put("error", "bad prop key")
        val s = service ?: return JSONObject().put("ok", false).put("error", "priv service not running")
        return JSONObject().put("ok", true).put("key", key).put("value", s.getprop(key)).put("via", "broker_priv_server")
    }
}
