package com.rjkyker.shizukuai.broker.admin

import android.app.admin.DevicePolicyManager
import android.app.admin.NetworkEvent
import android.app.admin.SecurityLog.SecurityEvent
import android.content.Context
import android.os.Bundle
import android.util.Log

class DelegatedAdminActionHandler(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun getActiveScopes(): List<String> {
        return try {
            DpmNullAdmin.getDelegatedScopes(dpm, context.packageName) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve delegated scopes", e)
            emptyList()
        }
    }

    fun hasScope(scope: String): Boolean = getActiveScopes().contains(scope)

    fun setPermissionGrantState(targetPackage: String, permission: String, state: Int): Boolean {
        if (!hasScope(DevicePolicyManager.DELEGATION_PERMISSION_GRANT)) {
            Log.w(TAG, "Scope DELEGATION_PERMISSION_GRANT not held. Fallback required.")
            return false
        }
        return try {
            DpmNullAdmin.setPermissionGrantState(dpm, targetPackage, permission, state)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException granting $permission to $targetPackage", e)
            false
        }
    }

    fun setPackagesSuspended(packages: Array<String>, suspended: Boolean): Array<String> {
        if (!hasScope(DevicePolicyManager.DELEGATION_PACKAGE_ACCESS)) {
            Log.w(TAG, "Scope DELEGATION_PACKAGE_ACCESS not held.")
            return packages
        }
        return try {
            DpmNullAdmin.setPackagesSuspended(dpm, packages, suspended) ?: packages
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling package suspension", e)
            packages
        }
    }

    fun setUninstallBlocked(targetPackage: String, blocked: Boolean): Boolean {
        if (!hasScope(DevicePolicyManager.DELEGATION_BLOCK_UNINSTALL)) {
            Log.w(TAG, "Scope DELEGATION_BLOCK_UNINSTALL not held.")
            return false
        }
        return try {
            DpmNullAdmin.setUninstallBlocked(dpm, targetPackage, blocked)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting uninstall block on $targetPackage", e)
            false
        }
    }

    fun setApplicationRestrictions(targetPackage: String, restrictions: Bundle): Boolean {
        if (!hasScope(DevicePolicyManager.DELEGATION_APP_RESTRICTIONS)) {
            Log.w(TAG, "Scope DELEGATION_APP_RESTRICTIONS not held.")
            return false
        }
        return try {
            DpmNullAdmin.setApplicationRestrictions(dpm, targetPackage, restrictions)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting app restrictions on $targetPackage", e)
            false
        }
    }

    fun enableSystemApp(targetPackage: String): Boolean {
        if (!hasScope(DevicePolicyManager.DELEGATION_ENABLE_SYSTEM_APP)) {
            Log.w(TAG, "Scope DELEGATION_ENABLE_SYSTEM_APP not held.")
            return false
        }
        return try {
            DpmNullAdmin.enableSystemApp(dpm, targetPackage)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling system package $targetPackage", e)
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun retrieveNetworkLogs(batchToken: Long): List<NetworkEvent>? {
        if (!hasScope(DevicePolicyManager.DELEGATION_NETWORK_LOGGING)) return null
        return try {
            DpmNullAdmin.retrieveNetworkLogs(dpm, batchToken) as List<NetworkEvent>?
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve network logs", e)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun retrieveSecurityLogs(): List<SecurityEvent>? {
        if (!hasScope(DevicePolicyManager.DELEGATION_SECURITY_LOGGING)) return null
        return try {
            DpmNullAdmin.retrieveSecurityLogs(dpm) as List<SecurityEvent>?
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve security logs", e)
            null
        }
    }

    companion object {
        private const val TAG = "DelegatedAdminHandler"
    }
}
