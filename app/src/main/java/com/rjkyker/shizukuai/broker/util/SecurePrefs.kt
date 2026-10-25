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
    private const val KEY_AIRTABLE_API_KEY = "airtable_api_key"
    private const val KEY_BRIDGE_URL_PREFIX = "bridge_url_"
    private const val KEY_BRIDGE_TOKEN_PREFIX = "bridge_token_"
    private const val KEY_MESH_BASE_URL = "mesh_base_url"
    val BRIDGE_NAMES = listOf("android", "spark", "ether", "war", "magic")

    /**
     * Built-in, non-secret public mesh host (RJ's permanent ngrok domain). Every bridge is a path on
     * it: /android/mcp /spark/mcp /ether/mcp /war/mcp /magic/mcp. These are connection URLs only —
     * NO token or key is ever baked in; bridge/upstream tokens stay blank until set in the app.
     * All defaults are editable/overridable in the UI; saving a blank value restores the default.
     */
    const val DEFAULT_MESH_BASE_URL = "https://presocial-endocrinopathic-fumiko.ngrok-free.dev"

    /** Built-in upstream default: the android bridge on the public mesh (any network). */
    const val DEFAULT_UPSTREAM_MCP_URL = "$DEFAULT_MESH_BASE_URL/android/mcp"

    /** Built-in default MCP URL for a bridge on the mesh base (empty for unknown names). */
    fun defaultBridgeUrl(name: String, base: String = DEFAULT_MESH_BASE_URL): String {
        val n = name.trim().lowercase()
        return if (n in BRIDGE_NAMES) "${base.trimEnd('/')}/$n/mcp" else ""
    }

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

    /**
     * Primary upstream MCP URL (any network). Prefilled: falls back to `<mesh base>/android/mcp`
     * (built-in default [DEFAULT_UPSTREAM_MCP_URL]) when the user has not overridden it.
     */
    fun getUpstreamMcpUrl(context: Context): String =
        get(context).getString(KEY_UPSTREAM_URL, "").orEmpty().trim()
            .ifBlank { defaultBridgeUrl("android", getMeshBaseUrl(context)) }

    /** Blank restores the built-in default. Only http/https URLs are accepted. */
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

    // ---- Airtable ----

    /** Airtable personal access token / API key. Empty = not configured (worker no-ops). Never logged. */
    fun getAirtableApiKey(context: Context): String =
        get(context).getString(KEY_AIRTABLE_API_KEY, "").orEmpty().trim()

    fun setAirtableApiKey(context: Context, value: String) {
        get(context).edit().putString(KEY_AIRTABLE_API_KEY, value.trim()).apply()
    }

    // ---- Bridge mesh (magic / war / ether) ----

    private fun normalizeBridge(name: String): String = name.trim().lowercase()

    /**
     * Per-bridge MCP URL (android|spark|ether|war|magic). Falls back to the built-in mesh default
     * (`<mesh base>/<name>/mcp`) when the user has not overridden it.
     */
    fun getBridgeUrl(context: Context, name: String): String =
        get(context).getString(KEY_BRIDGE_URL_PREFIX + normalizeBridge(name), "").orEmpty().trim()
            .ifBlank { defaultBridgeUrl(name, getMeshBaseUrl(context)) }

    /** Public mesh base URL (scheme+host, no path). Defaults to [DEFAULT_MESH_BASE_URL]. */
    fun getMeshBaseUrl(context: Context): String =
        get(context).getString(KEY_MESH_BASE_URL, "").orEmpty().trim().trimEnd('/')
            .ifBlank { DEFAULT_MESH_BASE_URL }

    /** Blank restores the built-in default. Only http/https accepted. */
    fun setMeshBaseUrl(context: Context, value: String) {
        get(context).edit().putString(KEY_MESH_BASE_URL, validateUrl(value).trimEnd('/')).apply()
    }

    /**
     * First-run seeding of the built-in, non-secret mesh base URL. Upstream and per-bridge URLs are
     * derived from it at read time (so every settings field is prefilled on first launch, and
     * editing the mesh base moves all un-overridden bridges with it). Never writes tokens/keys and
     * never overwrites a user value.
     */
    fun seedDefaultUrls(context: Context) {
        val p = get(context)
        if (p.getString(KEY_MESH_BASE_URL, "").isNullOrBlank()) {
            p.edit().putString(KEY_MESH_BASE_URL, DEFAULT_MESH_BASE_URL).apply()
        }
    }

    /** True when the bridge URL is the user's own override (not the built-in mesh default). */
    fun isBridgeOverridden(context: Context, name: String): Boolean =
        get(context).getString(KEY_BRIDGE_URL_PREFIX + normalizeBridge(name), "").orEmpty().isNotBlank()

    /** Blank restores the built-in mesh default for that bridge. Only http/https accepted. */
    fun setBridgeUrl(context: Context, name: String, value: String) {
        get(context).edit().putString(KEY_BRIDGE_URL_PREFIX + normalizeBridge(name), validateUrl(value)).apply()
    }

    /** Optional per-bridge bearer token (falls back to the upstream token when blank). Never logged. */
    fun getBridgeToken(context: Context, name: String): String =
        get(context).getString(KEY_BRIDGE_TOKEN_PREFIX + normalizeBridge(name), "").orEmpty().trim()

    fun setBridgeToken(context: Context, name: String, value: String) {
        get(context).edit().putString(KEY_BRIDGE_TOKEN_PREFIX + normalizeBridge(name), value.trim()).apply()
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
