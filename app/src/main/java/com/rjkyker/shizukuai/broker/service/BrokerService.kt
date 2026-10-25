package com.rjkyker.shizukuai.broker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rjkyker.shizukuai.broker.R
import com.rjkyker.shizukuai.broker.airtable.AirtableSyncWorker
import com.rjkyker.shizukuai.broker.bridge.ShizukuGateway
import com.rjkyker.shizukuai.broker.mcp.UpstreamMcpRelayClient
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.dhizuku.DhizukuGateway
import android.provider.Settings
import com.rjkyker.shizukuai.broker.discovery.NsdAdvertiser
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.net.RoamKeeper
import com.rjkyker.shizukuai.broker.ui.MainActivity
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import rikka.shizuku.Shizuku

/**
 * Sticky foreground service that keeps BrokerServer (127.0.0.1:17999, MCP + /action) and the
 * DataWedge result receiver alive when the UI is closed. Also hosts the Shizuku / Dhizuku binder
 * watchdog so reconnects after Shizuku restarts without needing MainActivity open.
 *
 * Started by MainActivity, BootReceiver, or
 * `am start-foreground-service -n com.rjkyker.shizukuai.broker/.service.BrokerService`.
 */
class BrokerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var bindersRegistered = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received — re-init Dhizuku + ensure server")
        try {
            DhizukuGateway.reconnect(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Dhizuku reconnect: ${e.message}")
        }
        ensureServer()
        updateNotification()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        updateNotification()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                ensureServer()
                // Cheap: rebind if the Tailscale IP appeared/changed without a network callback.
                runCatching { BrokerServer.get()?.rebindIfNeeded() }
                // Re-init Dhizuku if it was installed but the binder went away after a reboot.
                if (DhizukuGateway.isInstalled(applicationContext) && !DhizukuGateway.isGranted()) {
                    runCatching { DhizukuGateway.init(applicationContext) }
                } else if (DhizukuGateway.isInstalled(applicationContext)) {
                    // Granted but cached DPM may be stale after a Dhizuku restart.
                    if (!DhizukuGateway.isInitialized) {
                        runCatching { DhizukuGateway.reconnect(applicationContext) }
                    }
                }
                // Re-assert our accessibility service if it was disabled (Dhizuku/Shizuku settings write).
                reassertAccessibility()
                runCatching { com.rjkyker.shizukuai.broker.sensing.WifiDetection.start(applicationContext) }
            } catch (e: Exception) {
                Log.w(TAG, "watchdog: ${e.message}")
            } finally {
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SecurePrefs.ensureMcpToken(applicationContext)
        SecurePrefs.seedDefaultUrls(applicationContext)
        registerBinders()
        // mDNS/DNS-SD advertisement (_mcp._tcp + _broker._tcp) so LAN AIs can find /discover.
        runCatching { NsdAdvertiser.register(applicationContext) }
        // Periodic Airtable telemetry flush (no-ops until an API key is set in the app).
        runCatching { AirtableSyncWorker.schedulePeriodic(applicationContext) }
        // Roam-proof: rebind on Tailscale IP change, re-register mDNS, wireless-debug + Tailscale nudge.
        runCatching { RoamKeeper.start(applicationContext) }
        DataWedgeResultReceiver.ensureRegistered(applicationContext)
        autoRepairDataWedgeOnce()
        // 1.8.5: Wi-Fi detection is default ON (near-device AP RSSI + presence loop).
        runCatching { com.rjkyker.shizukuai.broker.sensing.WifiDetection.start(applicationContext) }
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
        DataWedgeResultReceiver.ensureRegistered(applicationContext)
        ensureServer()
        registerBinders()
        return START_STICKY
    }


    /**
     * If our accessibility component is missing from enabled_accessibility_services, put it back
     * via Settings.Secure (works when we hold WRITE_SECURE_SETTINGS via Dhizuku/Shizuku). Soft-fail.
     */
    private fun reassertAccessibility() {
        try {
            if (AgentAccessibilityService.isEnabled()) return
            val cr = applicationContext.contentResolver
            val component = "${packageName}/.accessibility.AgentAccessibilityService"
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (cur.split(':').any { it.equals(component, ignoreCase = true) }) {
                // Listed but service not bound yet — just ensure master switch is on
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                return
            }
            val next = if (cur.isBlank()) component else "$cur:$component"
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "watchdog re-asserted accessibility service")
        } catch (e: Exception) {
            Log.w(TAG, "reassertAccessibility: ${e.message}")
        }
    }

    /**
     * Scans weren't landing because DataWedge only used the broker profile while the broker was in the
     * foreground (and the profile was never created unless an AI called set_config). Apply the repair
     * plan once per install/upgrade on Zebra devices; the Repair button re-applies it any time.
     */
    private fun autoRepairDataWedgeOnce() {
        try {
            val installed = runCatching { packageManager.getPackageInfo("com.symbol.datawedge", 0); true }.getOrDefault(false)
            if (!installed) return
            val prefs = getSharedPreferences("broker_dw_repair", MODE_PRIVATE)
            val vc = runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L)
            if (prefs.getLong("applied_version", -1L) == vc) return
            com.rjkyker.shizukuai.broker.datawedge.DataWedgeRepair.apply(applicationContext)
            prefs.edit().putLong("applied_version", vc).apply()
        } catch (e: Exception) {
            Log.w(TAG, "autoRepairDataWedgeOnce: ${e.message}")
        }
    }

    private fun ensureServer() {
        if (BrokerServer.get()?.isRunning != true) {
            BrokerServer.startFrom(applicationContext)
        }
    }

    private fun registerBinders() {
        if (bindersRegistered) return
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            bindersRegistered = true
            Log.i(TAG, "Shizuku binder listeners registered in BrokerService")
        } catch (e: Exception) {
            Log.w(TAG, "register binders: ${e.message}")
        }
        runCatching { DhizukuGateway.init(applicationContext) }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Broker server", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "updateNotification: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val shizuku = runCatching { ShizukuGateway.status() }.getOrNull()
        val upHost = runCatching { UpstreamMcpRelayClient(this).displayHost() }.getOrDefault("")
        val status = buildString {
            append("MCP :${BrokerServer.PORT}")
            append(" · sticky")
            if (upHost.isNotEmpty()) append(" · up→").append(upHost) else append(" · up unset")
            if (shizuku != null) {
                append(if (shizuku.binderAlive && shizuku.permissionGranted) " · Shizuku OK" else " · Shizuku …")
            }
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Shizuku AI Broker")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "MCP :${BrokerServer.PORT} on ${BrokerServer.get()?.boundAddresses?.joinToString(" + ") ?: "127.0.0.1"}\n" +
                    "POST /mcp · GET /ws · POST /action · GET /health · GET /discover\n" +
                    "Upstream: ${if (upHost.isEmpty()) "(not configured — set in app)" else upHost}\n" +
                    "Auth required (Bearer token). Boot-persistent."
            ))
            .setOngoing(true)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        if (bindersRegistered) {
            try {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                Shizuku.removeBinderDeadListener(binderDeadListener)
            } catch (_: Exception) {
            }
            bindersRegistered = false
        }
        runCatching { NsdAdvertiser.unregister(applicationContext) }
        runCatching { RoamKeeper.stop(applicationContext) }
        runCatching { com.rjkyker.shizukuai.broker.sensing.WifiDetection.stop() }
        BrokerServer.get()?.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BrokerService"
        private const val CHANNEL = "broker_server"
        private const val NOTIF_ID = 17999
        private const val WATCHDOG_MS = 30_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, BrokerService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
                BrokerServer.startFrom(context.applicationContext)
            }
        }
    }
}
