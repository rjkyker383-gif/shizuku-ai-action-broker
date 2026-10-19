package com.rjkyker.shizukuai.broker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts BrokerService after reboot / app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> BrokerService.start(context)
        }
    }
}
