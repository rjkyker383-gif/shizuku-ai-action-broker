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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rjkyker.shizukuai.broker.R
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.ui.MainActivity

/**
 * Foreground service that keeps BrokerServer (127.0.0.1:17999) and the DataWedge
 * result receiver alive when the UI is closed. Started by MainActivity, BootReceiver,
 * or `am start-foreground-service -n com.rjkyker.shizukuai.broker/.service.BrokerService`.
 */
class BrokerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
        DataWedgeResultReceiver.ensureRegistered(applicationContext)
        if (BrokerServer.get()?.isRunning != true) {
            BrokerServer.startFrom(applicationContext)
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Broker server", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Shizuku AI Broker")
            .setContentText("Local server on 127.0.0.1:${BrokerServer.PORT}")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        BrokerServer.get()?.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BrokerService"
        private const val CHANNEL = "broker_server"
        private const val NOTIF_ID = 17999

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
