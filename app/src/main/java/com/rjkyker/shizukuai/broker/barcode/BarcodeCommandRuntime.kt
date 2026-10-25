package com.rjkyker.shizukuai.broker.barcode

import android.content.Context
import android.os.Handler
import android.os.Looper

/** In-app BGX dispatcher. It never evaluates barcode contents as code. */
object BarcodeCommandRuntime {
    private const val PREFS = "bgx_runtime"
    private const val TTL_MS = 10 * 60 * 1000L

    @Volatile
    private var listener: ((CustomBarcodeCommand) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(commandListener: (CustomBarcodeCommand) -> Unit) {
        listener = commandListener
    }

    fun detach(commandListener: (CustomBarcodeCommand) -> Unit) {
        if (listener === commandListener) listener = null
    }

    fun dispatch(context: Context, command: CustomBarcodeCommand) {
        val current = listener
        if (current == null) {
            savePending(context, command)
            return
        }
        mainHandler.post {
            if (listener === current) current(command) else savePending(context, command)
        }
    }

    fun consumePending(context: Context): CustomBarcodeCommand? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("id", null) ?: return null
        val type = prefs.getString("type", null) ?: return null
        val version = prefs.getInt("version", 1)
        val timestamp = prefs.getLong("at", 0L)
        prefs.edit().clear().apply()
        if (timestamp <= 0L || System.currentTimeMillis() - timestamp > TTL_MS) return null
        return CustomBarcodeCommand(type, id, version)
    }

    private fun savePending(context: Context, command: CustomBarcodeCommand) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("type", command.type)
            .putString("id", command.id)
            .putInt("version", command.version)
            .putLong("at", System.currentTimeMillis())
            .apply()
    }
}
