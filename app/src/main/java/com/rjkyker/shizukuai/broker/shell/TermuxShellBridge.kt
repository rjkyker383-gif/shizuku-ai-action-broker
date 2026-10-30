package com.rjkyker.shizukuai.broker.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.rjkyker.shizukuai.broker.bridge.ShellResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executes a command inside Termux's own app sandbox through its documented RUN_COMMAND API.
 *
 * Setup required on device:
 * 1. Install Termux (package com.termux).
 * 2. In ~/.termux/termux.properties set allow-external-apps=true and restart Termux.
 * 3. Grant this app "Run commands in Termux environment" in Android app permissions.
 *
 * This does not grant Termux access to Android's protected app data or root.
 */
object TermuxShellBridge {
    private const val TAG = "TermuxShellBridge"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val RUN_COMMAND_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val REQUEST_ID = "com.rjkyker.shizukuai.broker.shell.REQUEST_ID"
    private const val RESULT_ACTION = "com.rjkyker.shizukuai.broker.shell.TERMUX_RESULT"
    private const val MAX_CAPTURE_CHARS = 64 * 1024
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<ShellResult>>()

    fun isAvailable(context: Context): Boolean {
        val packagePresent = runCatching {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        }.isSuccess
        val permission = context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED
        return packagePresent && permission
    }

    fun exec(context: Context, command: String, timeoutSec: Long = 30L): ShellResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ShellResult(-1, "", "TermuxShellBridge.exec refused on main thread", "termux")
        }
        if (command.isBlank()) return ShellResult(-1, "", "command is blank", "termux")
        val denied = ShellDenylist.check(command)
        if (!denied.allowed) {
            return ShellResult(-1, "", "denied: ${denied.reason}", "termux")
        }
        if (command.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
            return ShellResult(-1, "", "command exceeds 64 KiB safety limit", "termux")
        }
        if (!isAvailable(context)) {
            return ShellResult(
                -1, "",
                "Termux route unavailable. Install Termux, set allow-external-apps=true in " +
                    "~/.termux/termux.properties, restart Termux, then grant this app " +
                    "'Run commands in Termux environment'.",
                "termux"
            )
        }

        val id = nextId.getAndIncrement()
        val future = CompletableFuture<ShellResult>()
        pending[id] = future
        val callbackIntent = Intent(context, TermuxShellResultReceiver::class.java)
            .setAction(RESULT_ACTION)
            .putExtra(REQUEST_ID, id)
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
        val callback = android.app.PendingIntent.getBroadcast(context, id, callbackIntent, flags)
        val intent = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            putExtra(RUN_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(RUN_COMMAND_ARGUMENTS, arrayOf("-lc", command))
            putExtra(RUN_COMMAND_WORKDIR, "/data/data/com.termux/files/home")
            putExtra(RUN_COMMAND_BACKGROUND, true)
            putExtra(RUN_COMMAND_PENDING_INTENT, callback)
            putExtra("com.termux.RUN_COMMAND_LABEL", "Shizuku AI Broker command")
            putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "Command requested through the broker shell bridge")
        }

        return try {
            context.startService(intent)
            future.get(timeoutSec.coerceIn(1L, 300L) + 5L, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            ShellResult(-1, "", "Termux command timed out waiting for result", "termux", true)
        } catch (e: Exception) {
            Log.w(TAG, "Termux command failed to start/return", e)
            ShellResult(-1, "", e.message ?: "Termux command failed", "termux")
        } finally {
            pending.remove(id)
            callback.cancel()
        }
    }

    internal fun complete(intent: Intent) {
        val id = intent.getIntExtra(REQUEST_ID, -1)
        val future = pending[id] ?: return
        val extras = intent.extras ?: Bundle.EMPTY
        val resultBundle = extras.keySet().asSequence()
            .mapNotNull { extras.get(it) as? Bundle }
            .firstOrNull { b ->
                b.keySet().any {
                    it.contains("stdout", true) || it.contains("exit", true) ||
                        it.contains("STDOUT") || it.contains("EXIT_CODE")
                }
            }
            ?: extras
        fun findString(vararg names: String): String {
            val keys = resultBundle.keySet()
            for (n in names) {
                val key = keys.firstOrNull { it == n || it.equals(n, true) || it.endsWith(n) || it.endsWith(".$n") }
                    ?: keys.firstOrNull { it.contains(n, ignoreCase = true) }
                if (key != null) {
                    val v = resultBundle.get(key)?.toString().orEmpty()
                    if (v.isNotEmpty() || keys.contains(key)) return v
                }
            }
            return ""
        }
        fun findInt(fallback: Int, vararg names: String): Int {
            val keys = resultBundle.keySet()
            for (n in names) {
                val key = keys.firstOrNull { it == n || it.equals(n, true) || it.endsWith(n) || it.endsWith(".$n") }
                    ?: keys.firstOrNull { it.contains(n, ignoreCase = true) }
                    ?: continue
                return when (val v = resultBundle.get(key)) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull() ?: fallback
                    else -> fallback
                }
            }
            return fallback
        }
        val internalError = findString("errmsg", "ERRMSG", "EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG")
        val errCode = findInt(-1, "err", "ERR", "EXTRA_PLUGIN_RESULT_BUNDLE_ERR")
        val stdout = findString("stdout", "STDOUT", "EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT").take(MAX_CAPTURE_CHARS)
        val stderr = findString("stderr", "STDERR", "EXTRA_PLUGIN_RESULT_BUNDLE_STDERR").take(MAX_CAPTURE_CHARS)
        val exitCode = findInt(-1, "exitCode", "exit_code", "EXIT_CODE", "EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE")
        // Termux PluginResult: ERR absent (-1) or 0 means no plugin error; only non-zero ERR is fatal.
        val pluginFailed = errCode != -1 && errCode != 0
        val errBits = mutableListOf<String>()
        if (stderr.isNotBlank()) errBits.add(stderr)
        if (internalError.isNotBlank()) errBits.add(internalError)
        if (pluginFailed) errBits.add("termux plugin err=" + errCode)
        if (!pluginFailed && exitCode == -1 && stdout.isBlank() && stderr.isBlank() && internalError.isBlank()) {
            errBits.add("Termux returned empty output with exit=-1")
        }
        Log.i(TAG, "termux result id=$id errCode=$errCode exit=$exitCode pluginFailed=$pluginFailed keys=${resultBundle.keySet()}")
        future.complete(
            ShellResult(
                exitCode = if (pluginFailed) -1 else exitCode,
                stdout = stdout,
                stderr = errBits.joinToString("\n"),
                via = "termux"
            )
        )
    }
}

/** Explicit receiver target for Termux's PendingIntent result callback. */
class TermuxShellResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TermuxShellBridge.complete(intent)
    }
}
