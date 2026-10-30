package com.rjkyker.shizukuai.broker.autonomy

import android.content.Context
import org.json.JSONObject

/**
 * Feature flags for 1.9.0 autonomy (design doc §Rollback). Safe defaults:
 * autonomy OFF, planner OFF, memory OFF, send (kill switch) OFF, hosted OFF; local LLM ON (draft-only).
 */
object AutonomyFlags {
    private const val PREFS = "autonomy_flags"
    const val AUTONOMY = "autonomy_enabled"
    const val LLM = "llm_enabled"
    const val PLANNER = "planner_enabled"
    const val MEMORY = "memory_enabled"
    const val SEND = "send_enabled"
    const val HOSTED = "hosted_enabled"
    const val WATCHDOG_PRIV_RESTART = "watchdog_priv_restart"

    val DEFAULTS = linkedMapOf(
        AUTONOMY to false, LLM to true, PLANNER to false, MEMORY to false,
        SEND to false, HOSTED to false, WATCHDOG_PRIV_RESTART to true,
    )

    /** Flags MCP/AI may turn ON. Send, hosted and memory are device-only to enable. */
    val MCP_ENABLEABLE = setOf(AUTONOMY, PLANNER, LLM)

    fun get(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, DEFAULTS[key] ?: false)

    fun set(ctx: Context, key: String, value: Boolean) {
        require(key in DEFAULTS) { "unknown flag $key" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    /** STOP ALL: everything that can act autonomously goes off in one tap. */
    fun stopAll(ctx: Context) {
        listOf(AUTONOMY, PLANNER, SEND, HOSTED).forEach { set(ctx, it, false) }
    }

    fun toJson(ctx: Context): JSONObject = JSONObject().also { o -> DEFAULTS.keys.forEach { o.put(it, get(ctx, it)) } }
}
