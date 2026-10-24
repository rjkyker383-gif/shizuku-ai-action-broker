package com.rjkyker.shizukuai.broker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rjkyker.shizukuai.broker.util.SecureSettingsKeepalive

/**
 * Restarts BrokerService (sticky MCP on :17999) after reboot / app update, then best-effort re-enables
 * ADB / wireless debugging (WRITE_SECURE_SETTINGS or Shizuku shell) so Shizuku
 * and Tailscale ADB can recover without a USB re-pair.
 *
 * Identity kept as broker BootReceiver (not PHX Dominator); Dominator only contributed
 * the WRITE_SECURE_SETTINGS + boot-keepalive intent.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "boot/replace action=$action → start BrokerService + keepalive")
                try {
                    BrokerService.start(context)
                } catch (e: Exception) {
                    Log.w(TAG, "BrokerService.start failed: ${e.message}")
                }
                try {
                    SecureSettingsKeepalive.reenableAdbAndDevSettings(context.applicationContext)
                } catch (e: Exception) {
                    Log.w(TAG, "SecureSettingsKeepalive failed: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "BrokerBootReceiver"
    }
}
