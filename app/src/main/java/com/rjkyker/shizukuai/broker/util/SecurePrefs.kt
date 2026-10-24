package com.rjkyker.shizukuai.broker.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * EncryptedSharedPreferences when security-crypto is on the classpath;
 * otherwise plain SharedPreferences fallback for agent-key / model settings.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val PREFS_NAME = "shizukuai_broker_secure"
    private const val KEY_AGENT_KEY = "agent_key"
    private const val KEY_MODEL = "model"
    private const val KEY_MCP_TOKEN = "mcp_token"
    private const val KEY_UPSTREAM_URL = "upstream_mcp_url"
    private const val KEY_UPSTREAM_TOKEN = "upstream_mcp_token"
    private const val KEY_UPSTREAM_LAN_FALLBACK = "upstream_mcp_lan_fallback_url"

    /**
     * Built-in upstream default. Intentionally empty: no any-network (ngrok / Cloudflare tunnel /
     * Tailscale) URL for the 8486 node is documented in the repo, and a 192.168.x.x LAN address
     * only works on the home Wi-Fi. Set the URL in MainActivity or via setUpstreamMcpUrl.
     */
    const val DEFAULT_UPSTREAM_MCP_URL = ""

    /** Example LAN-only address (home Wi-Fi). Suitable only as the optional LAN fallback. */
    const val EXAMPLE_LAN_UPSTREAM_URL = "http://192.168.1.158:8486/mcp"

    @Volatile private var cachedMcpToken: String? = null

    fun get(context: Context): SharedPreferences {
        return tryCreateEncrypted(context) ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAgentKey(context: Context): String =
        get(context).getString(KEY_AGENT_KEY, "").orEmpty()

    fun setAgentKey(context: Context, value: String) {
        get(context).edit().putString(KEY_AGENT_KEY, value).apply()
    }

    fun getModel(context: Context): String =
        get(context).getString(KEY_MODEL, "").orEmpty()

    fun setModel(context: Context, value: String) {
        get(context).edit().putString(KEY_MODEL, value).apply()
    }

    fun getMcpToken(context: Context): String =
        cachedMcpToken ?: get(context).getString(KEY_MCP_TOKEN, "").orEmpty().also {
            if (it.isNotEmpty()) cachedMcpToken = it
        }

    fun setMcpToken(context: Context, value: String) {
        get(context).edit().putString(KEY_MCP_TOKEN, value).apply()
        cachedMcpToken = value.ifEmpty { null }
    }

    /**
     * Returns the bearer token required by POST /mcp, POST /action and /ws.
     * Generates a random 256-bit hex token on first use. Never log the returned value.
     */
    @Synchronized
    fun ensureMcpToken(context: Context): String {
        val existing = getMcpToken(context)
        if (existing.isNotEmpty()) return existing
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        get(context).edit().putString(KEY_MCP_TOKEN, token).commit()
        cachedMcpToken = token
        return token
    }

    /** Rotates the MCP token (old token stops working immediately). */
    @Synchronized
    fun rotateMcpToken(context: Context): String {
        setMcpToken(context, "")
        return ensureMcpToken(context)
    }

    /** Primary upstream MCP URL (any network: https tunnel or Tailscale 100.x). Empty = not configured. */
    fun getUpstreamMcpUrl(context: Context): String =
        get(context).getString(KEY_UPSTREAM_URL, "").orEmpty().trim().ifBlank { DEFAULT_UPSTREAM_MCP_URL }

    /** Blank clears it. Only http/https URLs are accepted. */
    fun setUpstreamMcpUrl(context: Context, value: String) {
        val v = validateUrl(value)
        get(context).edit().putString(KEY_UPSTREAM_URL, v).apply()
    }

    /**
     * Optional LAN fallback (e.g. http://192.168.1.158:8486/mcp). The relay uses it only when the
     * primary fails AND the phone currently has an address in the same /24 subnet.
     */
    fun getUpstreamLanFallbackUrl(context: Context): String =
        get(context).getString(KEY_UPSTREAM_LAN_FALLBACK, "").orEmpty().trim()

    fun setUpstreamLanFallbackUrl(context: Context, value: String) {
        get(context).edit().putString(KEY_UPSTREAM_LAN_FALLBACK, validateUrl(value)).apply()
    }

    private fun validateUrl(value: String): String {
        val v = value.trim()
        require(v.isEmpty() || v.startsWith("http://") || v.startsWith("https://")) { "upstream URL must be http(s)" }
        return v
    }

    /** Optional bearer token sent to the upstream MCP (never the local broker token). */
    fun getUpstreamMcpToken(context: Context): String =
        get(context).getString(KEY_UPSTREAM_TOKEN, "").orEmpty()

    fun setUpstreamMcpToken(context: Context, value: String) {
        get(context).edit().putString(KEY_UPSTREAM_TOKEN, value.trim()).apply()
    }

    private fun tryCreateEncrypted(context: Context): SharedPreferences? {
        return try {
            val masterKeyClass = Class.forName("androidx.security.crypto.MasterKey")
            val builderClass = Class.forName("androidx.security.crypto.MasterKey\$Builder")
            val builderCtor = builderClass.getConstructor(Context::class.java)
            val builder = builderCtor.newInstance(context.applicationContext)
            val keySchemeClass = Class.forName("androidx.security.crypto.MasterKey\$KeyScheme")
            val aes = java.lang.Enum.valueOf(
                keySchemeClass as Class<out Enum<*>>,
                "AES256_GCM"
            ) as Enum<*>
            builderClass.getMethod("setKeyScheme", keySchemeClass).invoke(builder, aes)
            val masterKey = builderClass.getMethod("build").invoke(builder)

            val prefClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences")
            val prefKeyScheme = Class.forName(
                "androidx.security.crypto.EncryptedSharedPreferences\$PrefKeyEncryptionScheme"
            )
            val prefValueScheme = Class.forName(
                "androidx.security.crypto.EncryptedSharedPreferences\$PrefValueEncryptionScheme"
            )
            val keyEnc = java.lang.Enum.valueOf(
                prefKeyScheme as Class<out Enum<*>>,
                "AES256_SIV"
            ) as Enum<*>
            val valEnc = java.lang.Enum.valueOf(
                prefValueScheme as Class<out Enum<*>>,
                "AES256_GCM"
            ) as Enum<*>
            prefClass.getMethod(
                "create",
                Context::class.java,
                String::class.java,
                masterKeyClass,
                prefKeyScheme,
                prefValueScheme
            ).invoke(
                null,
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                keyEnc,
                valEnc
            ) as SharedPreferences
        } catch (e: Throwable) {
            Log.i(TAG, "EncryptedSharedPreferences unavailable, using plain prefs: ${e.javaClass.simpleName}")
            null
        }
    }
}
