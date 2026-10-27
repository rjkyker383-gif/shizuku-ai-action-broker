package com.rjkyker.shizukuai.broker.service

import android.content.Context
import android.util.Log
import com.rjkyker.shizukuai.broker.sensing.WifiDetection
import org.json.JSONObject

/**
 * Pause switch: stops background loops (Wi-Fi detection, roam self-check work) while keeping
 * the foreground service + local MCP server alive so Muse/ADB can still reach the phone.
 * Default: not paused.
 */
object BrokerPause {
    private const val TAG = "BrokerPause"
    private const val PREFS = "broker_pause"
    private const val KEY = "paused"

    @Volatile private var pausedMem: Boolean? = null

    fun isPaused(ctx: Context): Boolean {
        pausedMem?.let { return it }
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        pausedMem = v
        return v
    }

    fun setPaused(ctx: Context, paused: Boolean): JSONObject {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, paused).apply()
        pausedMem = paused
        Log.i(TAG, "paused=$paused")
        if (paused) {
            runCatching { WifiDetection.stop() }
        } else {
            runCatching { WifiDetection.start(ctx.applicationContext) }
        }
        return status(ctx)
    }

    fun status(ctx: Context): JSONObject = JSONObject()
        .put("ok", true)
        .put("paused", isPaused(ctx))
        .put("wifi_detection_running", WifiDetection.stateJson(ctx).optBoolean("running", false))
        .put("note", "Pause stops background loops; MCP server and accessibility stay up.")
}
