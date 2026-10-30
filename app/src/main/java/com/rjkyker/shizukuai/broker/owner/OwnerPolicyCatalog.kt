package com.rjkyker.shizukuai.broker.owner

/**
 * 1.8.8 OwnDroid-parity policy catalog. Pure Kotlin (no android.*) so it is JVM-unit-testable.
 *
 * Feature set modelled on OwnDroid (github.com/BinTianqi/OwnDroid, GPL-3.0, (C) BinTianqi).
 * No OwnDroid source is copied: every call below is a fresh reimplementation against the public
 * DevicePolicyManager API, so the broker's licensing is unaffected. Credit: OwnDroid for the
 * feature inventory.
 *
 * Tiers:
 *  READ     - getters only. Always allowed.
 *  MEDIUM   - reversible app/user-restriction toggles. Allowed over MCP.
 *  HIGH     - reversible but device-wide; MCP needs confirm=true.
 *  LOCKOUT  - can lock the owner out (camera, ADB, lock task, always-on VPN, a11y allow-list, ...).
 *             Never callable over MCP / Muse / AI, even with confirm. Dashboard only, with an
 *             explicit on-device confirmation dialog.
 *  FORBIDDEN- intentionally NOT implemented anywhere (wipe, factory reset, reset password,
 *             remove device owner, transfer ownership, max-failed-passwords-for-wipe, ...).
 */
object OwnerPolicyCatalog {

    enum class Tier { READ, MEDIUM, HIGH, LOCKOUT, FORBIDDEN }

    enum class Caller { MCP, DASHBOARD, DELEGATED_CLIENT }

    data class Decision(val allowed: Boolean, val tier: Tier?, val reason: String = "")

    /** Implemented ops -> tier. Keep in sync with OwnerPolicyEngine.run(). */
    val OPS: Map<String, Tier> = linkedMapOf(
        // ---- read-only ----
        "probe" to Tier.READ,
        "features" to Tier.READ,
        "app_info" to Tier.READ,
        "restrictions" to Tier.READ,
        "lock_task_get" to Tier.READ,
        "system_update_get" to Tier.READ,
        "network_get" to Tier.READ,
        "password_info" to Tier.READ,
        "users" to Tier.READ,
        "delegates_get" to Tier.READ,
        // ---- applications (MEDIUM) ----
        "hide" to Tier.MEDIUM,
        "suspend" to Tier.MEDIUM,
        "permission" to Tier.MEDIUM,
        "enable_system_app" to Tier.MEDIUM,
        "install_existing" to Tier.MEDIUM,
        "metered_data" to Tier.MEDIUM,
        "user_control" to Tier.MEDIUM,
        "restriction" to Tier.MEDIUM,
        "default_dialer" to Tier.MEDIUM,
        // ---- system (MEDIUM) ----
        "master_volume_mute" to Tier.MEDIUM,
        "lock_screen_info" to Tier.MEDIUM,
        "organization_name" to Tier.MEDIUM,
        "support_message" to Tier.MEDIUM,
        "lock_now" to Tier.MEDIUM,
        // ---- HIGH: confirm=true ----
        "block_uninstall" to Tier.HIGH,
        "clear_app_storage" to Tier.HIGH,
        "permission_policy" to Tier.HIGH,
        "screen_capture" to Tier.HIGH,
        "status_bar" to Tier.HIGH,
        "keyguard_features" to Tier.HIGH,
        "max_time_to_lock" to Tier.HIGH,
        "system_update_policy" to Tier.HIGH,
        "network_logging" to Tier.HIGH,
        "security_logging" to Tier.HIGH,
        "private_dns" to Tier.HIGH,
        "global_proxy" to Tier.HIGH,
        "wifi_lockdown" to Tier.HIGH,
        "auto_time" to Tier.HIGH,
        "backup_service" to Tier.HIGH,
        "bluetooth_contact_sharing" to Tier.HIGH,
        "delegate_scopes" to Tier.HIGH,
        "reboot" to Tier.HIGH,
        // ---- LOCKOUT: dashboard only ----
        "camera" to Tier.LOCKOUT,
        "lock_task_packages" to Tier.LOCKOUT,
        "always_on_vpn" to Tier.LOCKOUT,
        "permitted_accessibility" to Tier.LOCKOUT,
        "permitted_input_methods" to Tier.LOCKOUT,
        "keyguard_disabled" to Tier.LOCKOUT,
        "lockout_restriction" to Tier.LOCKOUT,
    )

    /** Named and refused everywhere. Not implemented in the engine at all. */
    val FORBIDDEN_OPS: Set<String> = setOf(
        "wipe", "wipe_data", "wipe_device", "factory_reset", "reset_password", "reset_password_token",
        "set_password", "clear_device_owner", "remove_device_owner", "remove_admin",
        "transfer_ownership", "max_failed_passwords_for_wipe", "frp_policy", "install_system_update",
        "request_bugreport", "remove_user", "create_user", "switch_user", "uninstall_all_ca_certs",
        "zebra_license", "mx_license",
    )

    /** Ordinary user restrictions (MEDIUM). Superset of DhizukuGateway.ALLOWED_RESTRICTIONS. */
    val SAFE_RESTRICTIONS: Set<String> = setOf(
        "no_config_wifi", "no_install_unknown_sources", "no_install_unknown_sources_globally",
        "no_config_bluetooth", "no_bluetooth_sharing", "no_bluetooth", "no_usb_file_transfer",
        "no_config_location", "no_share_location", "no_adjust_volume", "no_unmute_microphone",
        "no_config_screen_timeout", "no_config_brightness", "no_airplane_mode", "no_config_tethering",
        "no_outgoing_calls", "no_sms", "no_outgoing_beam", "no_config_cell_broadcasts",
        "no_config_mobile_networks", "no_data_roaming", "no_fun", "no_set_wallpaper",
        "no_set_user_icon", "no_create_windows", "no_system_error_dialogs", "no_physical_media",
        "no_mount_physical_media", "no_config_date_time", "no_config_locale", "no_printing",
        "no_autofill", "no_content_capture", "no_content_suggestions", "no_cross_profile_copy_paste",
        "no_sharing_into_profile", "no_ambient_display", "no_config_default_apps",
        "no_change_wifi_state", "no_wifi_tethering", "no_wifi_direct", "no_add_wifi_config",
        "no_cellular_2g", "no_ultra_wideband_radio", "no_camera_toggle", "no_microphone_toggle",
        "no_network_reset", "no_remove_user", "no_user_switch", "no_add_clone_profile",
    )

    /** Restrictions that can cut the owner off (ADB, installs, VPN/Tailscale, accounts, reset...). */
    val LOCKOUT_RESTRICTIONS: Set<String> = setOf(
        "no_debugging_features", "no_install_apps", "no_uninstall_apps", "no_config_vpn",
        "no_modify_accounts", "no_factory_reset", "no_safe_boot", "no_add_user",
        "no_add_managed_profile", "no_config_credentials", "no_control_apps", "no_apps_control",
        "no_config_private_dns", "no_camera", "disallow_config_vpn", "no_usb_data_signaling",
    )

    /** Packages the broker must never hide/suspend/clear/block/lock (keeps the control path alive). */
    val PROTECTED_PACKAGES: Set<String> = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.shell", "com.android.providers.settings", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.rosan.dhizuku", "moe.shizuku.privileged.api", "com.tailscale.ipn", "com.termux",
        "com.zebra.mdna.els", "com.symbol.datawedge", "com.zebra.licensemanager",
        "com.symbol.mxmf", "com.zebra.devicemanager",
    )

    const val BROKER_PREFIX = "com.rjkyker.shizukuai"

    /** Ops a Dhizuku-style delegated client app may call (after allow-list + on-device consent). */
    val CLIENT_OPS: Set<String> = setOf(
        "probe", "app_info", "restrictions", "hide", "suspend", "permission", "restriction",
    )

    fun tierOf(op: String): Tier? = when {
        op.lowercase() in FORBIDDEN_OPS -> Tier.FORBIDDEN
        else -> OPS[op.lowercase()]
    }

    fun decide(op: String, caller: Caller, confirmed: Boolean): Decision {
        val key = op.trim().lowercase()
        val tier = tierOf(key) ?: return Decision(false, null, "unknown owner_policy op: $op")
        return when (tier) {
            Tier.FORBIDDEN -> Decision(false, tier, "$key is intentionally not implemented (destructive / lockout)")
            Tier.LOCKOUT -> when {
                caller != Caller.DASHBOARD ->
                    Decision(false, tier, "$key is LOCKOUT-risk: denied for MCP/Muse/AI and client apps; use the on-device dashboard")
                !confirmed -> Decision(false, tier, "$key needs on-device confirmation")
                else -> Decision(true, tier)
            }
            Tier.HIGH -> when {
                caller == Caller.DELEGATED_CLIENT -> Decision(false, tier, "$key is HIGH: not delegated to client apps")
                !confirmed -> Decision(false, tier, "$key is HIGH risk; resend with confirm=true")
                else -> Decision(true, tier)
            }
            Tier.MEDIUM, Tier.READ -> if (caller == Caller.DELEGATED_CLIENT && key !in CLIENT_OPS) {
                Decision(false, tier, "$key not available to delegated clients")
            } else Decision(true, tier)
        }
    }

    /** null = ok. */
    fun checkPackage(pkg: String?, op: String = ""): String? {
        val p = pkg?.trim().orEmpty()
        return when {
            p.isEmpty() -> "package required"
            !Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(p) -> "invalid package name: $p"
            p in PROTECTED_PACKAGES -> "package is protected: $p"
            p.startsWith(BROKER_PREFIX) -> "broker cannot target itself${if (op.isNotEmpty()) " ($op)" else ""}"
            else -> null
        }
    }

    /** null = ok. LOCKOUT restrictions go through op=lockout_restriction (dashboard only). */
    fun checkRestriction(key: String?, lockoutOp: Boolean): String? {
        val k = key?.trim().orEmpty()
        return when {
            k.isEmpty() -> "restriction required"
            lockoutOp && k in LOCKOUT_RESTRICTIONS -> null
            lockoutOp -> "not a lockout restriction: $k (use op=restriction)"
            k in LOCKOUT_RESTRICTIONS -> "restriction $k is LOCKOUT-risk; dashboard only (op=lockout_restriction)"
            k in SAFE_RESTRICTIONS -> null
            else -> "restriction not allow-listed: $k"
        }
    }

    /** Delegation scopes the broker may hand to another package via the Dhizuku device owner. */
    val DELEGATABLE_SCOPES: Set<String> = setOf(
        "delegation-cert-install", "delegation-app-restrictions", "delegation-block-uninstall",
        "delegation-enable-system-app", "delegation-keep-uninstalled-packages",
        "delegation-package-access", "delegation-permission-grant", "delegation-cert-selection",
    )
}
