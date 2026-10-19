package com.rjkyker.shizukuai.broker.cli

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rjkyker.shizukuai.broker.admin.DelegatedAdminActionHandler

/**
 * Handles com.rjkyker.shizukuai.broker.ADMIN_SCOPES broadcasts
 * (worker-admin-scopes via am broadcast).
 */
class AdminCliReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SCOPES) return
        val scopes = DelegatedAdminActionHandler(context).getActiveScopes()
        Log.i(TAG, "Active delegated scopes (${scopes.size}): $scopes")
    }

    companion object {
        private const val TAG = "AdminCliReceiver"
        const val ACTION_SCOPES = "com.rjkyker.shizukuai.broker.ADMIN_SCOPES"
    }
}
