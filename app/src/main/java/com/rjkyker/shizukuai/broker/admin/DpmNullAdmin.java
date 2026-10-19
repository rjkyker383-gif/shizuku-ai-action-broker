package com.rjkyker.shizukuai.broker.admin;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.Bundle;

import java.util.List;

/**
 * Java shim so Kotlin can invoke delegated (admin=null) DevicePolicyManager APIs
 * whose SDK stubs annotate ComponentName as @NonNull.
 */
public final class DpmNullAdmin {
    private DpmNullAdmin() {}

    public static boolean setPermissionGrantState(
            DevicePolicyManager dpm, String packageName, String permission, int grantState) {
        return dpm.setPermissionGrantState(null, packageName, permission, grantState);
    }

    public static String[] setPackagesSuspended(
            DevicePolicyManager dpm, String[] packageNames, boolean suspended) {
        return dpm.setPackagesSuspended(null, packageNames, suspended);
    }

    public static void setUninstallBlocked(
            DevicePolicyManager dpm, String packageName, boolean uninstallBlocked) {
        dpm.setUninstallBlocked(null, packageName, uninstallBlocked);
    }

    public static void setApplicationRestrictions(
            DevicePolicyManager dpm, String packageName, Bundle restrictions) {
        dpm.setApplicationRestrictions(null, packageName, restrictions);
    }

    public static void enableSystemApp(DevicePolicyManager dpm, String packageName) {
        dpm.enableSystemApp((ComponentName) null, packageName);
    }

    @SuppressWarnings("unchecked")
    public static List<?> retrieveNetworkLogs(DevicePolicyManager dpm, long batchToken) {
        return dpm.retrieveNetworkLogs(null, batchToken);
    }

    @SuppressWarnings("unchecked")
    public static List<?> retrieveSecurityLogs(DevicePolicyManager dpm) {
        return dpm.retrieveSecurityLogs(null);
    }

    @SuppressWarnings("unchecked")
    public static List<String> getDelegatedScopes(DevicePolicyManager dpm, String packageName) {
        return dpm.getDelegatedScopes(null, packageName);
    }
}
