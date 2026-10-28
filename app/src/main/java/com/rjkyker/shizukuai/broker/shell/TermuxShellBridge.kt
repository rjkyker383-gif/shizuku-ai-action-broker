package com.rjkyker.shizukuai.broker.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
        if (command.isBlank()) return ShellResult(-1, "", "command is blank", "termux")
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

        // Termux wraps result fields in the "result" Bundle. Keys inside that Bundle are
        // plain names ("stdout", "stderr", "exitCode", "err", "errmsg"), not constant names.
        val resultBundle = intent.getBundleExtra("result")
        if (resultBundle == null) {
            future.complete(
                ShellResult(-1, "", "Termux returned no result bundle", "termux")
            )
            return
        }

        val internalError = resultBundle.getString("errmsg").orEmpty()
        val errCode = resultBundle.getInt("err", -1)
        val stdout = resultBundle.getString("stdout").orEmpty().take(MAX_CAPTURE_CHARS)
        val stderr = resultBundle.getString("stderr").orEmpty().take(MAX_CAPTURE_CHARS)
        val exitCode = resultBundle.getInt("exitCode", -1)

        future.complete(
            ShellResult(
                exitCode = if (errCode == -1) exitCode else -1,
                stdout = stdout,
                stderr = listOf(stderr, internalError.takeIf { it.isNotBlank() })
                    .filterNotNull()
                    .joinToString("\n"),
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
