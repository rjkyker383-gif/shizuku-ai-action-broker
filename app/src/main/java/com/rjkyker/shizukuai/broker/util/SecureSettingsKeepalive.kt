package com.rjkyker.shizukuai.broker.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.rjkyker.shizukuai.broker.bridge.ShizukuBridge

/**
 * After reboot / package replace, best-effort re-enable ADB + wireless debugging so
 * Shizuku / rish / Tailscale ADB can come back without a USB re-pair.
 *
 * WRITE_SECURE_SETTINGS is signature/privileged: declared in the manifest, granted via
 * `pm grant` or device-owner. Never throws; every path is try/catch + permission-checked.
 */
object SecureSettingsKeepalive {

    private const val TAG = "SecureSettingsKeepalive"

    /** Keys we try to set to 1 when permitted. Harmless if already set. */
    private val GLOBAL_FLAGS = listOf(
        "adb_enabled",
        "development_settings_enabled",
        "adb_wifi_enabled",
    )

    fun reenableAdbAndDevSettings(context: Context) {
        val granted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            applyViaSettingsApi(context)
        } else {
            Log.i(TAG, "WRITE_SECURE_SETTINGS not granted; trying Shizuku shell fallback")
            applyViaShizukuShell()
        }
    }

    private fun applyViaSettingsApi(context: Context) {
        val cr = context.contentResolver
        for (key in GLOBAL_FLAGS) {
            try {
                val current = Settings.Global.getInt(cr, key, 0)
                if (current != 1) {
                    Settings.Global.putInt(cr, key, 1)
                    Log.i(TAG, "Settings.Global $key: $current -> 1")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "denied writing $key: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "failed writing $key: ${e.message}")
            }
        }
    }

    /**
     * Fallback when the broker itself lacks WRITE_SECURE_SETTINGS but Shizuku is already up
     * (typical on MY_PACKAGE_REPLACED, not cold BOOT_COMPLETED). Soft-fails if Shizuku is down.
     */
    private fun applyViaShizukuShell() {
        for (key in GLOBAL_FLAGS) {
            try {
                val (code, out) = ShizukuBridge.exec("settings put global $key 1")
                if (code != 0) {
                    Log.w(TAG, "shizuku settings put $key exit=$code out=${out.take(120)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "shizuku fallback for $key: ${e.message}")
            }
        }
    }
}
