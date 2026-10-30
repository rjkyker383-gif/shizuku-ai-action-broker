package com.rjkyker.shizukuai.broker.owner

import org.json.JSONArray
import org.json.JSONObject

/**
 * OwnDroid (GPL-3.0, github.com/BinTianqi/OwnDroid, (C) BinTianqi) feature inventory vs broker 1.8.8.
 * Status: ADDED (new in 1.8.8), PRESENT (already in broker <= 1.8.7), SKIPPED (with reason).
 * Reimplemented, not copied.
 */
object OwnDroidParity {

    enum class Status { ADDED, PRESENT, SKIPPED }

    data class Feature(val area: String, val name: String, val status: Status, val how: String)

    val FEATURES: List<Feature> = listOf(
        // Working modes
        Feature("modes", "Dhizuku client mode", Status.PRESENT, "DhizukuGateway (broker is a Dhizuku client of the DO)"),
        Feature("modes", "Dhizuku server (share DPM to other apps)", Status.ADDED, "BrokerDelegationService: allow-list + per-app on-device consent, API-level (not raw binder transact)"),
        Feature("modes", "Device owner directly", Status.SKIPPED, "Dhizuku is already the TC22 device owner; only one DO is allowed and switching needs a wipe or dpm remove"),
        Feature("modes", "Profile owner / work profile creation", Status.SKIPPED, "system adds no_add_managed_profile with a DO; would need root"),
        Feature("modes", "Shizuku activation", Status.PRESENT, "ShizukuGateway/ShizukuBridge"),
        Feature("modes", "Own Shizuku-style privileged server", Status.ADDED, "BrokerPrivServer (app_process uid 2000, Binder/AIDL)"),
        Feature("modes", "Transfer ownership", Status.SKIPPED, "FORBIDDEN: lockout/ownership risk"),
        Feature("modes", "Delegated admins (setDelegatedScopes to other pkgs)", Status.ADDED, "owner_policy op=delegate_scopes (HIGH) + delegates_get"),
        // Applications
        Feature("apps", "Hide / unhide app", Status.PRESENT, "manage_dhizuku_policy hide; also owner_policy hide"),
        Feature("apps", "Suspend / unsuspend", Status.PRESENT, "manage_dhizuku_policy suspend; also owner_policy suspend"),
        Feature("apps", "Grant / deny / default runtime permission", Status.PRESENT, "manage_dhizuku_policy perm; owner_policy permission"),
        Feature("apps", "Block uninstall", Status.ADDED, "owner_policy block_uninstall (HIGH; broker/Dhizuku/Shizuku/Tailscale protected)"),
        Feature("apps", "Clear app storage", Status.ADDED, "owner_policy clear_app_storage (HIGH)"),
        Feature("apps", "Enable system app", Status.ADDED, "owner_policy enable_system_app"),
        Feature("apps", "Install existing package", Status.ADDED, "owner_policy install_existing"),
        Feature("apps", "Metered data disabled packages", Status.ADDED, "owner_policy metered_data"),
        Feature("apps", "User control disabled packages", Status.ADDED, "owner_policy user_control (API 30+)"),
        Feature("apps", "Permission policy (prompt/auto grant/deny)", Status.ADDED, "owner_policy permission_policy (HIGH)"),
        Feature("apps", "Default dialer", Status.ADDED, "owner_policy default_dialer (API 34+, unsupported on TC22 A13)"),
        Feature("apps", "App status read (hidden/suspended/blocked)", Status.ADDED, "owner_policy app_info"),
        Feature("apps", "Install / uninstall APK (app installer)", Status.SKIPPED, "Muse denylist forbids pm uninstall; install is out of scope for AI"),
        Feature("apps", "Managed configurations (app restrictions)", Status.PRESENT, "DelegatedAdminActionHandler.setApplicationRestrictions"),
        Feature("apps", "Keep uninstalled packages", Status.SKIPPED, "low value; scope delegatable via delegate_scopes"),
        Feature("apps", "App groups / app lock UI", Status.SKIPPED, "OwnDroid UI convenience, not a policy"),
        // User restrictions
        Feature("restrictions", "User restrictions (~68 keys)", Status.ADDED, "owner_policy restriction: ~50 safe keys MEDIUM; lockout keys (ADB, installs, VPN, accounts, reset) dashboard-only"),
        // Network
        Feature("network", "Wi-Fi add / modify / remove", Status.PRESENT, "zebra_wifi_config"),
        Feature("network", "Wi-Fi config lockdown", Status.ADDED, "owner_policy wifi_lockdown (HIGH)"),
        Feature("network", "Always-on VPN", Status.ADDED, "owner_policy always_on_vpn (LOCKOUT: would displace Tailscale; dashboard only)"),
        Feature("network", "Private DNS", Status.ADDED, "owner_policy private_dns (HIGH)"),
        Feature("network", "Recommended global HTTP proxy", Status.ADDED, "owner_policy global_proxy (HIGH)"),
        Feature("network", "Network logging toggle", Status.ADDED, "owner_policy network_logging (HIGH)"),
        Feature("network", "Network log retrieval", Status.PRESENT, "DelegatedAdminActionHandler.retrieveNetworkLogs (needs scope)"),
        Feature("network", "Network stats", Status.SKIPPED, "needs PACKAGE_USAGE_STATS + NetworkStatsManager UI; later"),
        Feature("network", "Override APN", Status.SKIPPED, "telephony lockout risk"),
        Feature("network", "Preferential network service", Status.SKIPPED, "work-profile only"),
        Feature("network", "Minimum Wi-Fi security / SSID policy", Status.SKIPPED, "can drop the Tailscale uplink; later as LOCKOUT"),
        // System
        Feature("system", "Disable camera", Status.ADDED, "owner_policy camera (LOCKOUT, dashboard only)"),
        Feature("system", "Disable screen capture", Status.ADDED, "owner_policy screen_capture (HIGH; breaks a11y_screenshot while on)"),
        Feature("system", "Master volume mute", Status.ADDED, "owner_policy master_volume_mute"),
        Feature("system", "Status bar disable", Status.ADDED, "owner_policy status_bar (HIGH)"),
        Feature("system", "Keyguard disable / keyguard features", Status.ADDED, "keyguard_features HIGH; keyguard_disabled LOCKOUT"),
        Feature("system", "Lock now", Status.ADDED, "owner_policy lock_now"),
        Feature("system", "Reboot", Status.ADDED, "owner_policy reboot (HIGH)"),
        Feature("system", "Lock task mode", Status.ADDED, "lock_task_packages LOCKOUT (dashboard), lock_task_get READ"),
        Feature("system", "System update policy", Status.ADDED, "owner_policy system_update_policy (HIGH), system_update_get"),
        Feature("system", "Security logging toggle", Status.ADDED, "owner_policy security_logging (HIGH)"),
        Feature("system", "Security log retrieval", Status.PRESENT, "DelegatedAdminActionHandler.retrieveSecurityLogs"),
        Feature("system", "Auto time / time zone", Status.ADDED, "owner_policy auto_time (HIGH)"),
        Feature("system", "Set time / time zone manually", Status.SKIPPED, "breaks TLS/Tailscale if wrong"),
        Feature("system", "Backup service", Status.ADDED, "owner_policy backup_service (HIGH)"),
        Feature("system", "Bluetooth contact sharing", Status.ADDED, "owner_policy bluetooth_contact_sharing (HIGH)"),
        Feature("system", "Lock screen info / org name / support messages", Status.ADDED, "owner_policy lock_screen_info, organization_name, support_message"),
        Feature("system", "Permitted accessibility services / IMEs", Status.ADDED, "LOCKOUT (dashboard); broker a11y always kept"),
        Feature("system", "CA certificates install/remove", Status.SKIPPED, "MITM risk; later as LOCKOUT"),
        Feature("system", "USB data signaling", Status.SKIPPED, "LOCKOUT (kills ADB); read-only in probe"),
        Feature("system", "Wipe data / factory reset", Status.SKIPPED, "FORBIDDEN: never implemented"),
        Feature("system", "Factory reset protection policy", Status.SKIPPED, "FORBIDDEN"),
        Feature("system", "Install system update file", Status.SKIPPED, "FORBIDDEN (brick risk)"),
        Feature("system", "Bug report", Status.SKIPPED, "FORBIDDEN: same system_server stall as dumpsys"),
        Feature("system", "Hardware monitor", Status.PRESENT, "get_system_status (battery/thermal basics)"),
        Feature("system", "Common Criteria / MTE / content protection", Status.SKIPPED, "A14+ or can break device"),
        // Password
        Feature("password", "Password / keyguard info (read)", Status.ADDED, "owner_policy password_info"),
        Feature("password", "Reset password / reset token", Status.SKIPPED, "FORBIDDEN: lockout"),
        Feature("password", "Password quality / complexity / history / expiry setters", Status.SKIPPED, "forces a password change; lockout risk"),
        Feature("password", "Max failed passwords for wipe", Status.SKIPPED, "FORBIDDEN: wipes device"),
        Feature("password", "Screen timeout (max time to lock)", Status.ADDED, "owner_policy max_time_to_lock (HIGH)"),
        // Users
        Feature("users", "User info", Status.ADDED, "owner_policy users"),
        Feature("users", "Create / start / switch / stop / remove user", Status.SKIPPED, "data loss + DO/Dhizuku instability"),
        Feature("users", "Cross-profile intent filters / widgets", Status.SKIPPED, "no work profile on TC22"),
        // API
        Feature("api", "Intent API (HIDE/SUSPEND/... with key)", Status.PRESENT, "MCP over Tailscale with bearer token + AdminCliService"),
        Feature("api", "Read-only feature probe", Status.ADDED, "owner_policy probe"),
    )

    fun toJson(): JSONObject {
        val arr = JSONArray()
        FEATURES.forEach { arr.put(JSONObject().put("area", it.area).put("feature", it.name).put("status", it.status.name).put("how", it.how)) }
        val counts = JSONObject()
        Status.values().forEach { s -> counts.put(s.name, FEATURES.count { it.status == s }) }
        return JSONObject().put("source", "OwnDroid (GPL-3.0) feature inventory; broker reimplementation, no code copied")
            .put("counts", counts).put("features", arr)
    }
}
