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
