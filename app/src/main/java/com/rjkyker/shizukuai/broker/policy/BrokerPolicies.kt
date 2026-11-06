package com.rjkyker.shizukuai.broker.policy

import android.content.Context
import com.rjkyker.shizukuai.broker.assist.AssistSettings
import com.rjkyker.shizukuai.broker.autonomy.AutonomyFlags
import com.rjkyker.shizukuai.broker.shell.ShellDenylist
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.10.0 broker policy store. Owner-tunable toggles may only make the broker stricter, or loosen RJ's
 * own soft toggles. The hard denylist (uninstall / pm clear / wipe / dialer-SMS / dumpsys / …) is locked
 * and cannot be disabled from the UI or MCP.
 */
object BrokerPolicies {
    private const val PREFS = "broker_policies"

    /** Soft (owner-tunable) keys. Defaults are the safe / current behaviour. */
    const val TYPE_ANYWHERE = "type_anywhere_enabled"
    const val SKIP_SENSITIVE = "skip_sensitive_fields"
    const val PREVIEW_FIRST = "preview_first_messaging"
    const val FOREGROUND_RECHECK = "foreground_recheck_before_touch"
    const val MCP_HOST_ALLOWLIST = "mcp_host_allowlist_enforced"
    const val DENYLIST_VIEW_ONLY = "shell_denylist_locked" // always true; exposed read-only

    val TOGGLE_DEFAULTS = linkedMapOf(
        TYPE_ANYWHERE to true,
        SKIP_SENSITIVE to true,          // FieldGuard; may NOT be turned off below "on" for password/OTP/card (hard)
        PREVIEW_FIRST to true,           // SendGate; may not be turned off so AI can auto-send
        FOREGROUND_RECHECK to true,
        MCP_HOST_ALLOWLIST to false,     // opt-in: when on, upstream/relay URLs must be in the allowlist
    )

    /** Keys the UI/MCP may flip. SKIP_SENSITIVE and PREVIEW_FIRST can only stay true (hardening). */
    val OWNER_TOGGLEABLE = TOGGLE_DEFAULTS.keys

    /** Hard denylist entries shown in the Policies screen; never disable-able. */
    val LOCKED_DENYLIST = listOf(
        "pm uninstall / pm clear / disable-user / hide",
        "rm -rf on /data, /sdcard, /storage, /system",
        "wipe / factory reset",
        "dd if=",
        "dialer / SMS / SEND intents (am start … CALL/SEND/sms:/tel:)",
        "service call isms / cmd telecom / settings put secure sms_*",
        "reboot -p / recovery",
        "locksettings set-pin|set-password|set-pattern|clear",
        "dpm remove-active-admin / set-device-owner / set-profile-owner",
        "settings put global adb_enabled 0",
        "bare dumpsys; dumpsys activity|jobscheduler|notification|procstats|cpuinfo; dumpsys meminfo (no pkg)",
        "bugreport",
        "zebra_license / mx_license / Mobility DNA license bypass",
    )

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): Boolean {
        require(key in TOGGLE_DEFAULTS) { "unknown policy $key" }
        // Hardening: these two can never be turned off once the policy store exists; they stay at true.
        if (key == SKIP_SENSITIVE || key == PREVIEW_FIRST) return true
        return p(ctx).getBoolean(key, TOGGLE_DEFAULTS.getValue(key))
    }

    /**
     * Set a soft toggle. Returns an error string if refused.
     * - SKIP_SENSITIVE / PREVIEW_FIRST may only be set to true.
     * - Unknown keys / locked denylist: refused.
     */
    fun set(ctx: Context, key: String, value: Boolean): String? {
        if (key !in OWNER_TOGGLEABLE) return "unknown or locked policy '$key'"
        if ((key == SKIP_SENSITIVE || key == PREVIEW_FIRST) && !value)
            return "$key is a hard safety rule and cannot be turned off"
        p(ctx).edit().putBoolean(key, value).apply()
        // Keep AssistSettings.enabled in sync for type-anywhere.
        if (key == TYPE_ANYWHERE) AssistSettings.setBool(ctx, "enabled", value)
        return null
    }

    fun hostAllowlist(ctx: Context): Set<String> =
        p(ctx).getStringSet("mcp_hosts", emptySet())?.toSet() ?: emptySet()

    fun setHostAllowlist(ctx: Context, hosts: Set<String>): String? {
        val cleaned = hosts.map { it.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/') }
            .filter { it.isNotEmpty() && !it.contains(' ') }.toSet()
        if (cleaned.any { it.contains("..") || it.startsWith(".") }) return "invalid host"
        p(ctx).edit().putStringSet("mcp_hosts", cleaned).apply()
        return null
    }

    /** When MCP_HOST_ALLOWLIST is on, [url] must match an allowlisted host (or be empty/local). */
    fun hostAllowed(ctx: Context, url: String?): Boolean {
        if (!get(ctx, MCP_HOST_ALLOWLIST)) return true
        if (url.isNullOrBlank()) return true
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (host == "localhost" || host == "127.0.0.1" || host.endsWith(".local")) return true
        return host in hostAllowlist(ctx)
    }

    fun typeAnywhereAllowed(ctx: Context, pkg: String?): Boolean {
        if (!get(ctx, TYPE_ANYWHERE)) return false
        return !AssistSettings.isExcluded(ctx, pkg)
    }

    fun foregroundRecheck(ctx: Context) = get(ctx, FOREGROUND_RECHECK)

    fun toJson(ctx: Context): JSONObject = JSONObject().apply {
        put("toggles", JSONObject().also { o -> TOGGLE_DEFAULTS.keys.forEach { o.put(it, get(ctx, it)) } })
        put("locked_denylist", JSONArray(LOCKED_DENYLIST))
        put("locked_note", "These rules cannot be disabled from the UI or MCP.")
        put("mcp_hosts", JSONArray(hostAllowlist(ctx).sorted()))
        put("assist_excluded", JSONArray(AssistSettings.excluded(ctx).sorted()))
        put("autonomy_flags", AutonomyFlags.toJson(ctx))
        put("shell_denylist_alive", true) // ShellDenylist is always enforced in code
        put("denylist_sample", JSONArray(listOf(
            ShellDenylist.check("pm clear com.x").reason,
            ShellDenylist.check("dumpsys").reason,
            ShellDenylist.check("am start -a android.intent.action.SENDTO -d sms:1").reason,
        )))
    }
}
