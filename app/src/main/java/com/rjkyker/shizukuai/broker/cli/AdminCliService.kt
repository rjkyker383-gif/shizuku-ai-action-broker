package com.rjkyker.shizukuai.broker.cli

import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.rjkyker.shizukuai.broker.admin.DelegatedAdminActionHandler

/**
 * Shell-facing entry for worker-admin-cli.sh:
 *   am startservice -n …/cli.AdminCliService --es cmd perm|suspend|block-uninstall|enable-sys …
 *
 * Maps grant|deny|default → DevicePolicyManager.PERMISSION_GRANT_STATE_*.
 */
class AdminCliService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val admin = DelegatedAdminActionHandler(applicationContext)
        val cmd = intent.getStringExtra(EXTRA_CMD)?.lowercase().orEmpty()
        Log.i(TAG, "cmd=$cmd extras=${intent.extras}")

        val ok = when (cmd) {
            "perm", "permission" -> handlePerm(admin, intent)
            "suspend" -> handleSuspend(admin, intent)
            "block-uninstall", "block_uninstall" -> handleBlockUninstall(admin, intent)
            "enable-sys", "enable_sys", "enable-system-app" -> handleEnableSys(admin, intent)
            "scopes" -> {
                Log.i(TAG, "scopes=${admin.getActiveScopes()}")
                true
            }
            else -> {
                Log.w(TAG, "Unknown cmd: $cmd")
                false
            }
        }
        Log.i(TAG, "cmd=$cmd result=$ok")
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun handlePerm(admin: DelegatedAdminActionHandler, intent: Intent): Boolean {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return false
        val permission = intent.getStringExtra(EXTRA_PERMISSION) ?: return false
        val stateRaw = intent.getStringExtra(EXTRA_STATE) ?: "default"
        val state = mapGrantState(stateRaw)
        return admin.setPermissionGrantState(pkg, permission, state)
    }

    private fun handleSuspend(admin: DelegatedAdminActionHandler, intent: Intent): Boolean {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return false
        val suspended = intent.getBooleanExtra(EXTRA_SUSPENDED, true)
        val failed = admin.setPackagesSuspended(arrayOf(pkg), suspended)
        return !failed.contains(pkg)
    }

    private fun handleBlockUninstall(admin: DelegatedAdminActionHandler, intent: Intent): Boolean {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return false
        val blocked = intent.getBooleanExtra(EXTRA_BLOCKED, true)
        return admin.setUninstallBlocked(pkg, blocked)
    }

    private fun handleEnableSys(admin: DelegatedAdminActionHandler, intent: Intent): Boolean {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return false
        return admin.enableSystemApp(pkg)
    }

    companion object {
        private const val TAG = "AdminCliService"

        const val EXTRA_CMD = "cmd"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_PERMISSION = "permission"
        const val EXTRA_STATE = "state"
        const val EXTRA_SUSPENDED = "suspended"
        const val EXTRA_BLOCKED = "blocked"

        /**
         * grant|deny|default (and numeric / allow aliases)
         * → PERMISSION_GRANT_STATE_GRANTED|DENIED|DEFAULT
         */
        fun mapGrantState(raw: String): Int {
            return when (raw.trim().lowercase()) {
                "grant", "granted", "allow", "1" ->
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                "deny", "denied", "0" ->
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                "default", "2" ->
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                else -> {
                    Log.w(TAG, "Unrecognized grant state '$raw', using DEFAULT")
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                }
            }
        }
    }
}
