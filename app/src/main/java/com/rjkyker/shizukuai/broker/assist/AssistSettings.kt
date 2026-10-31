package com.rjkyker.shizukuai.broker.assist

import android.content.Context
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject

/** Type-anywhere settings. All apps on by default; exclusions per package. Remote (box) completion is opt-in. */
object AssistSettings {
    private const val PREFS = "assist_cfg"
    private const val TOKEN_KEY = "assist_remote_token"
    const val DEFAULT_URL = "http://127.0.0.1:8776/complete"
    /** Never auto-suggest over system UI / lock screen / settings / password managers. */
    val DEFAULT_EXCLUDED = setOf("com.android.systemui", "com.android.settings", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.vending")

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = p(ctx).getBoolean("enabled", true)
    fun autoSuggest(ctx: Context) = p(ctx).getBoolean("auto_suggest", true)
    fun floatingButton(ctx: Context) = p(ctx).getBoolean("floating_button", true)
    fun remoteEnabled(ctx: Context) = p(ctx).getBoolean("remote_enabled", false)
    fun remoteUrls(ctx: Context): List<String> =
        (p(ctx).getString("remote_urls", DEFAULT_URL) ?: DEFAULT_URL).split(',', ' ', '\n').map { it.trim() }.filter { it.startsWith("http") }
    fun hasToken(ctx: Context) = !token(ctx).isNullOrBlank()
    internal fun token(ctx: Context): String? = runCatching { SecurePrefs.get(ctx).getString(TOKEN_KEY, null) }.getOrNull()

    fun excluded(ctx: Context): Set<String> = p(ctx).getStringSet("excluded", null)?.toSet() ?: DEFAULT_EXCLUDED
    fun isExcluded(ctx: Context, pkg: String?): Boolean = pkg == null || pkg in excluded(ctx)

    fun setBool(ctx: Context, key: String, v: Boolean) {
        require(key in setOf("enabled", "auto_suggest", "floating_button", "remote_enabled")) { "unknown key $key" }
        p(ctx).edit().putBoolean(key, v).apply()
    }
    fun setUrls(ctx: Context, urls: String) { p(ctx).edit().putString("remote_urls", urls.trim()).apply() }
    fun setToken(ctx: Context, token: String?) {
        SecurePrefs.get(ctx).edit().apply { if (token.isNullOrBlank()) remove(TOKEN_KEY) else putString(TOKEN_KEY, token.trim()) }.apply()
    }
    /** "typewriter" (default; shell input text, humanlike rhythm) or "direct" (ACTION_SET_TEXT). */
    fun insertMode(ctx: Context): String = p(ctx).getString("insert_mode", "typewriter") ?: "typewriter"
    fun setInsertMode(ctx: Context, m: String) { p(ctx).edit().putString("insert_mode", if (m == "direct") "direct" else "typewriter").apply() }
    /** 0.25..3.0, 1.0 = normal */
    fun typeSpeed(ctx: Context): Double = p(ctx).getFloat("type_speed", 1.0f).toDouble().coerceIn(0.25, 3.0)
    fun setTypeSpeed(ctx: Context, v: Double) { p(ctx).edit().putFloat("type_speed", v.coerceIn(0.25, 3.0).toFloat()).apply() }
    fun bubblePos(ctx: Context): Pair<Int, Int>? = p(ctx).let { if (it.contains("bubble_x")) it.getInt("bubble_x", 0) to it.getInt("bubble_y", 0) else null }
    fun setBubblePos(ctx: Context, x: Int, y: Int) { p(ctx).edit().putInt("bubble_x", x).putInt("bubble_y", y).apply() }
    fun setExcluded(ctx: Context, s: Set<String>) { p(ctx).edit().putStringSet("excluded", s.toSet()).apply() }

    fun toJson(ctx: Context): JSONObject = JSONObject().put("enabled", enabled(ctx)).put("auto_suggest", autoSuggest(ctx))
        .put("floating_button", floatingButton(ctx)).put("remote_enabled", remoteEnabled(ctx))
        .put("remote_urls", JSONArray(remoteUrls(ctx))).put("remote_token_set", hasToken(ctx))
        .put("excluded", JSONArray(excluded(ctx).sorted())).put("insert_mode", insertMode(ctx)).put("type_speed", typeSpeed(ctx))
}
