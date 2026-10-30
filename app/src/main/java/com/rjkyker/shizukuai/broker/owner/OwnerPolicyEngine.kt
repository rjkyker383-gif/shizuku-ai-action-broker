package com.rjkyker.shizukuai.broker.owner

import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.net.ProxyInfo
import android.os.Build
import com.rjkyker.shizukuai.broker.dhizuku.DhizukuGateway
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyCatalog.Caller
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyCatalog.Tier
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 1.8.8 OwnDroid-parity device-owner engine. Executes through the Dhizuku device owner
 * (DhizukuGateway.dpm). Every op is gated by [OwnerPolicyCatalog.decide] first.
 * wipeData / wipeDevice / resetPassword* / clearDeviceOwnerApp / transferOwnership are deliberately
 * absent from this file.
 */
object OwnerPolicyEngine {

    fun run(context: Context, opRaw: String, args: JSONObject, caller: Caller, confirmed: Boolean): JSONObject {
        val op = opRaw.trim().lowercase().ifEmpty { "probe" }
        val d = OwnerPolicyCatalog.decide(op, caller, confirmed)
        if (!d.allowed) return err(d.reason).put("op", op).put("tier", d.tier?.name ?: JSONObject.NULL)
        if (op == "features") return OwnDroidParity.toJson().put("ok", true).put("op", op)

        if (DhizukuGateway.isInstalled(context) && !DhizukuGateway.isInitialized) DhizukuGateway.init(context)
        if (!DhizukuGateway.isGranted()) {
            return err("Dhizuku permission not granted; approve the broker in Dhizuku").put("op", op)
        }
        val owner = DhizukuGateway.ownerComponent() ?: return err("Dhizuku owner component unavailable")
        val dpm = DhizukuGateway.dpm(context)
        return try {
            exec(context, dpm, owner, op, args).put("op", op).put("tier", d.tier!!.name)
                .put("via", "dhizuku_device_owner")
        } catch (e: SecurityException) {
            err("SecurityException: ${e.message}").put("op", op)
        } catch (e: UnsupportedOperationException) {
            err("unsupported: ${e.message}").put("op", op)
        } catch (e: Exception) {
            err("${e.javaClass.simpleName}: ${e.message}").put("op", op)
        }
    }

    private fun pkgArg(args: JSONObject, op: String): String {
        val p = args.optString("package", args.optString("pkg", ""))
        OwnerPolicyCatalog.checkPackage(p, op)?.let { throw IllegalArgumentException(it) }
        return p
    }

    /** Target boolean for a toggle op. Must be explicit: no default, so nothing flips by accident. */
    private fun on(args: JSONObject): Boolean {
        for (k in listOf("value", "enabled", "disabled")) if (args.has(k)) return args.getBoolean(k)
        throw IllegalArgumentException("value (boolean) required for this op")
    }

    private fun sdk(min: Int, what: String) {
        if (Build.VERSION.SDK_INT < min) throw UnsupportedOperationException("$what needs API $min (device ${Build.VERSION.SDK_INT})")
    }

    private fun exec(ctx: Context, dpm: DevicePolicyManager, a: ComponentName, op: String, args: JSONObject): JSONObject {
        val out = JSONObject().put("ok", true)
        when (op) {
            "probe" -> return probe(ctx, dpm, a)
            "app_info" -> {
                val p = args.optString("package")
                if (p.isBlank()) throw IllegalArgumentException("package required")
                out.put("package", p)
                put(out, "hidden") { dpm.isApplicationHidden(a, p) }
                put(out, "suspended") { dpm.isPackageSuspended(a, p) }
                put(out, "uninstall_blocked") { dpm.isUninstallBlocked(a, p) }
                put(out, "user_control_disabled") { dpm.getUserControlDisabledPackages(a).contains(p) }
                put(out, "metered_data_disabled") { dpm.getMeteredDataDisabledPackages(a).contains(p) }
                put(out, "delegated_scopes") { JSONArray(dpm.getDelegatedScopes(a, p)) }
                val perm = args.optString("permission")
                if (perm.isNotBlank()) put(out, "permission_state") { dpm.getPermissionGrantState(a, p, perm) }
            }
            "restrictions" -> {
                val r = dpm.getUserRestrictions(a)
                out.put("active", JSONArray(r.keySet().filter { r.getBoolean(it) }.sorted()))
                    .put("safe_allowlist", JSONArray(OwnerPolicyCatalog.SAFE_RESTRICTIONS.sorted()))
                    .put("lockout_dashboard_only", JSONArray(OwnerPolicyCatalog.LOCKOUT_RESTRICTIONS.sorted()))
            }
            "lock_task_get" -> {
                out.put("packages", JSONArray(dpm.getLockTaskPackages(a).toList()))
                put(out, "features") { dpm.getLockTaskFeatures(a) }
            }
            "system_update_get" -> {
                put(out, "policy") { policyName(dpm.systemUpdatePolicy) }
                put(out, "pending") { dpm.getPendingSystemUpdate(a)?.let { "received:" + it.receivedTime } ?: "none" }
            }
            "network_get" -> {
                put(out, "network_logging") { dpm.isNetworkLoggingEnabled(a) }
                put(out, "private_dns_mode") { dpm.getGlobalPrivateDnsMode(a) }
                put(out, "private_dns_host") { dpm.getGlobalPrivateDnsHost(a) ?: "" }
                put(out, "always_on_vpn") { dpm.getAlwaysOnVpnPackage(a) ?: "" }
                put(out, "always_on_vpn_lockdown") { dpm.isAlwaysOnVpnLockdownEnabled(a) }
                put(out, "wifi_lockdown") { dpm.hasLockdownAdminConfiguredNetworks(a) }
            }
            "password_info" -> {
                put(out, "quality") { dpm.getPasswordQuality(a) }
                put(out, "active_sufficient") { dpm.isActivePasswordSufficient }
                put(out, "complexity") { dpm.passwordComplexity }
                if (Build.VERSION.SDK_INT >= 31) put(out, "required_complexity") { dpm.requiredPasswordComplexity }
                put(out, "max_failed_for_wipe") { dpm.getMaximumFailedPasswordsForWipe(a) }
                put(out, "expiration_timeout_ms") { dpm.getPasswordExpirationTimeout(a) }
                put(out, "history_length") { dpm.getPasswordHistoryLength(a) }
                put(out, "reset_token_active") { dpm.isResetPasswordTokenActive(a) }
                put(out, "storage_encryption_status") { dpm.storageEncryptionStatus }
                out.put("note", "read-only: set/reset password is not implemented (lockout risk)")
            }
            "users" -> {
                put(out, "secondary_users") { JSONArray(dpm.getSecondaryUsers(a).map { it.toString() }) }
                put(out, "affiliated") { dpm.isAffiliatedUser }
                put(out, "logout_enabled") { dpm.isLogoutEnabled }
                out.put("note", "create/switch/remove user not implemented (Dhizuku DO + no_add_user; data-loss risk)")
            }
            "delegates_get" -> {
                val scopes = OwnerPolicyCatalog.DELEGATABLE_SCOPES + "delegation-network-logging" + "delegation-security-logging"
                val m = JSONObject()
                scopes.sorted().forEach { s -> runCatching { m.put(s, JSONArray(dpm.getDelegatePackages(a, s) ?: emptyList<String>())) } }
                out.put("delegates", m)
            }
            "hide" -> { val p = pkgArg(args, op); val v = on(args)
                out.put("result", dpm.setApplicationHidden(a, p, v)).put("hidden", dpm.isApplicationHidden(a, p)).put("package", p) }
            "suspend" -> { val p = pkgArg(args, op); val v = on(args)
                val failed = dpm.setPackagesSuspended(a, arrayOf(p), v)
                out.put("ok", !failed.contains(p)).put("suspended", v).put("package", p) }
            "permission" -> { val p = pkgArg(args, op); val perm = args.optString("permission")
                if (perm.isBlank()) throw IllegalArgumentException("permission required")
                val st = when (args.optString("state", "granted").lowercase()) {
                    "granted", "grant", "1" -> DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    "denied", "deny", "2" -> DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    "default", "0" -> DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                    else -> throw IllegalArgumentException("state must be granted|denied|default")
                }
                out.put("ok", dpm.setPermissionGrantState(a, p, perm, st)).put("package", p).put("permission", perm)
                    .put("state", dpm.getPermissionGrantState(a, p, perm)) }
            "enable_system_app" -> { val p = pkgArg(args, op); dpm.enableSystemApp(a, p); out.put("package", p) }
            "install_existing" -> { val p = pkgArg(args, op); out.put("ok", dpm.installExistingPackage(a, p)).put("package", p) }
            "metered_data" -> { val p = pkgArg(args, op); val dis = on(args)
                val cur = dpm.getMeteredDataDisabledPackages(a).toMutableSet()
                if (dis) cur.add(p) else cur.remove(p)
                val notSet = dpm.setMeteredDataDisabledPackages(a, cur.toList())
                out.put("ok", !notSet.contains(p)).put("package", p).put("metered_data_disabled", dis) }
            "user_control" -> { sdk(30, op); val p = pkgArg(args, op); val dis = on(args)
                val cur = dpm.getUserControlDisabledPackages(a).toMutableSet()
                if (dis) cur.add(p) else cur.remove(p)
                dpm.setUserControlDisabledPackages(a, cur.toList())
                out.put("package", p).put("user_control_disabled", dpm.getUserControlDisabledPackages(a).contains(p)) }
            "restriction", "lockout_restriction" -> {
                val k = args.optString("restriction", args.optString("key"))
                OwnerPolicyCatalog.checkRestriction(k, op == "lockout_restriction")?.let { throw IllegalArgumentException(it) }
                val v = on(args)
                if (v) dpm.addUserRestriction(a, k) else dpm.clearUserRestriction(a, k)
                val now = dpm.getUserRestrictions(a).getBoolean(k, false)
                out.put("ok", now == v).put("restriction", k).put("enabled", now) }
            "default_dialer" -> { sdk(34, op); val p = pkgArg(args, op); dpm.setDefaultDialerApplication(p); out.put("package", p) }
            "master_volume_mute" -> { dpm.setMasterVolumeMuted(a, on(args)); out.put("muted", dpm.isMasterVolumeMuted(a)) }
            "lock_screen_info" -> { val t = args.optString("text").take(200)
                dpm.setDeviceOwnerLockScreenInfo(a, t.ifEmpty { null }); out.put("text", t) }
            "organization_name" -> { val t = args.optString("text").take(100)
                dpm.setOrganizationName(a, t.ifEmpty { null }); out.put("text", t) }
            "support_message" -> { val t = args.optString("text").take(200)
                if (args.optBoolean("long", false)) dpm.setLongSupportMessage(a, t.ifEmpty { null })
                else dpm.setShortSupportMessage(a, t.ifEmpty { null }); out.put("text", t) }
            "lock_now" -> { dpm.lockNow(); out.put("locked", true) }
            "block_uninstall" -> { val p = pkgArg(args, op); dpm.setUninstallBlocked(a, p, on(args))
                out.put("package", p).put("uninstall_blocked", dpm.isUninstallBlocked(a, p)) }
            "clear_app_storage" -> { val p = pkgArg(args, op)
                val latch = CountDownLatch(1); var success = false
                val ex = Executors.newSingleThreadExecutor()
                dpm.clearApplicationUserData(a, p, ex) { _, s -> success = s; latch.countDown() }
                val done = latch.await(15, TimeUnit.SECONDS); ex.shutdown()
                out.put("ok", done && success).put("package", p).put("timed_out", !done) }
            "permission_policy" -> { val v = when (args.optString("policy", "prompt").lowercase()) {
                    "prompt" -> DevicePolicyManager.PERMISSION_POLICY_PROMPT
                    "auto_grant" -> DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
                    "auto_deny" -> DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY
                    else -> throw IllegalArgumentException("policy must be prompt|auto_grant|auto_deny") }
                dpm.setPermissionPolicy(a, v); out.put("policy", dpm.getPermissionPolicy(a)) }
            "screen_capture" -> { dpm.setScreenCaptureDisabled(a, on(args)); out.put("disabled", dpm.getScreenCaptureDisabled(a)) }
            "status_bar" -> { out.put("ok", dpm.setStatusBarDisabled(a, on(args))).put("disabled", on(args)) }
            "keyguard_features" -> { val f = args.optInt("flags", 0); dpm.setKeyguardDisabledFeatures(a, f)
                out.put("flags", dpm.getKeyguardDisabledFeatures(a)) }
            "max_time_to_lock" -> { val ms = args.optLong("ms", 0L).coerceAtLeast(0L)
                if (ms in 1 until 15_000) throw IllegalArgumentException("ms must be 0 (none) or >= 15000")
                dpm.setMaximumTimeToLock(a, ms); out.put("ms", dpm.getMaximumTimeToLock(a)) }
            "system_update_policy" -> { val pol = when (args.optString("policy", "none").lowercase()) {
                    "none", "clear" -> null
                    "automatic" -> SystemUpdatePolicy.createAutomaticInstallPolicy()
                    "postpone" -> SystemUpdatePolicy.createPostponeInstallPolicy()
                    "windowed" -> SystemUpdatePolicy.createWindowedInstallPolicy(
                        args.optInt("start_min", 120).coerceIn(0, 1439), args.optInt("end_min", 240).coerceIn(0, 1439))
                    else -> throw IllegalArgumentException("policy must be none|automatic|postpone|windowed") }
                dpm.setSystemUpdatePolicy(a, pol); out.put("policy", policyName(dpm.systemUpdatePolicy)) }
            "network_logging" -> { dpm.setNetworkLoggingEnabled(a, on(args)); out.put("enabled", dpm.isNetworkLoggingEnabled(a)) }
            "security_logging" -> { dpm.setSecurityLoggingEnabled(a, on(args)); out.put("enabled", dpm.isSecurityLoggingEnabled(a)) }
            "private_dns" -> { val mode = args.optString("mode", "opportunistic").lowercase()
                val r = when (mode) {
                    "opportunistic" -> dpm.setGlobalPrivateDnsModeOpportunistic(a)
                    "hostname" -> { val h = args.optString("host")
                        if (!Regex("^[A-Za-z0-9.-]{3,253}$").matches(h)) throw IllegalArgumentException("valid host required")
                        dpm.setGlobalPrivateDnsModeSpecifiedHost(a, h) }
                    else -> throw IllegalArgumentException("mode must be opportunistic|hostname") }
                out.put("ok", r == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR).put("result_code", r)
                    .put("mode", dpm.getGlobalPrivateDnsMode(a)) }
            "global_proxy" -> { val host = args.optString("host")
                if (host.isBlank()) { dpm.setRecommendedGlobalProxy(a, null); out.put("cleared", true) }
                else { val port = args.optInt("port", 0); if (port !in 1..65535) throw IllegalArgumentException("port 1-65535")
                    dpm.setRecommendedGlobalProxy(a, ProxyInfo.buildDirectProxy(host, port)); out.put("host", host).put("port", port) } }
            "wifi_lockdown" -> { sdk(30, op); dpm.setConfiguredNetworksLockdownState(a, on(args))
                out.put("lockdown", dpm.hasLockdownAdminConfiguredNetworks(a)) }
            "auto_time" -> { sdk(30, op); val v = on(args)
                if (args.optBoolean("zone", false)) dpm.setAutoTimeZoneEnabled(a, v) else dpm.setAutoTimeEnabled(a, v)
                out.put("auto_time", dpm.getAutoTimeEnabled(a)).put("auto_time_zone", dpm.getAutoTimeZoneEnabled(a)) }
            "backup_service" -> { dpm.setBackupServiceEnabled(a, on(args)); out.put("enabled", dpm.isBackupServiceEnabled(a)) }
            "bluetooth_contact_sharing" -> { dpm.setBluetoothContactSharingDisabled(a, on(args))
                out.put("disabled", dpm.getBluetoothContactSharingDisabled(a)) }
            "delegate_scopes" -> { val p = pkgArg(args, op)
                val list = args.optJSONArray("scopes")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                val bad = list.filter { it !in OwnerPolicyCatalog.DELEGATABLE_SCOPES }
                if (bad.isNotEmpty()) throw IllegalArgumentException("scopes not delegatable: $bad")
                dpm.setDelegatedScopes(a, p, list); out.put("package", p).put("scopes", JSONArray(dpm.getDelegatedScopes(a, p))) }
            "reboot" -> { dpm.reboot(a); out.put("rebooting", true) }
            // ---- LOCKOUT (dashboard only; decide() already refused other callers) ----
            "camera" -> { dpm.setCameraDisabled(a, on(args)); out.put("disabled", dpm.getCameraDisabled(a)) }
            "lock_task_packages" -> {
                val list = args.optJSONArray("packages")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                list.forEach { p -> if (p.startsWith(OwnerPolicyCatalog.BROKER_PREFIX).not()) OwnerPolicyCatalog.checkPackage(p, op)?.let { throw IllegalArgumentException(it) } }
                dpm.setLockTaskPackages(a, list.toTypedArray()); out.put("packages", JSONArray(dpm.getLockTaskPackages(a).toList())) }
            "always_on_vpn" -> { val p = args.optString("package")
                if (p.isBlank()) dpm.setAlwaysOnVpnPackage(a, null, false)
                else dpm.setAlwaysOnVpnPackage(a, p, args.optBoolean("lockdown", false))
                out.put("package", dpm.getAlwaysOnVpnPackage(a) ?: "").put("lockdown", dpm.isAlwaysOnVpnLockdownEnabled(a)) }
            "permitted_accessibility" -> {
                val list = args.optJSONArray("packages")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                val withBroker = list?.let { (it + ctx.packageName).distinct() }
                out.put("ok", dpm.setPermittedAccessibilityServices(a, withBroker))
                    .put("permitted", dpm.getPermittedAccessibilityServices(a)?.let { JSONArray(it) } ?: "all")
                    .put("note", "broker is always kept in the list") }
            "permitted_input_methods" -> {
                val list = args.optJSONArray("packages")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                out.put("ok", dpm.setPermittedInputMethods(a, list))
                    .put("permitted", dpm.getPermittedInputMethods(a)?.let { JSONArray(it) } ?: "all") }
            "keyguard_disabled" -> out.put("ok", dpm.setKeyguardDisabled(a, on(args)))
            else -> throw IllegalArgumentException("op not implemented: $op")
        }
        return out
    }

    /** Read-only feature probe. Never mutates anything. */
    fun probe(ctx: Context, dpm: DevicePolicyManager, a: ComponentName): JSONObject {
        val o = JSONObject().put("ok", true).put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL)
            .put("owner_component", a.flattenToShortString()).put("read_only", true)
        put(o, "is_device_owner") { dpm.isDeviceOwnerApp(a.packageName) }
        put(o, "affiliated") { dpm.isAffiliatedUser }
        put(o, "camera_disabled") { dpm.getCameraDisabled(a) }
        put(o, "screen_capture_disabled") { dpm.getScreenCaptureDisabled(a) }
        put(o, "master_volume_muted") { dpm.isMasterVolumeMuted(a) }
        put(o, "keyguard_disabled_features") { dpm.getKeyguardDisabledFeatures(a) }
        put(o, "max_time_to_lock_ms") { dpm.getMaximumTimeToLock(a) }
        put(o, "permission_policy") { dpm.getPermissionPolicy(a) }
        put(o, "network_logging") { dpm.isNetworkLoggingEnabled(a) }
        put(o, "security_logging") { dpm.isSecurityLoggingEnabled(a) }
        put(o, "private_dns_mode") { dpm.getGlobalPrivateDnsMode(a) }
        put(o, "always_on_vpn") { dpm.getAlwaysOnVpnPackage(a) ?: "" }
        put(o, "system_update_policy") { policyName(dpm.systemUpdatePolicy) }
        put(o, "lock_task_packages") { JSONArray(dpm.getLockTaskPackages(a).toList()) }
        put(o, "permitted_accessibility") { dpm.getPermittedAccessibilityServices(a)?.let { JSONArray(it) } ?: "all" }
        put(o, "permitted_input_methods") { dpm.getPermittedInputMethods(a)?.let { JSONArray(it) } ?: "all" }
        put(o, "organization_name") { dpm.getOrganizationName(a)?.toString() ?: "" }
        put(o, "backup_service") { dpm.isBackupServiceEnabled(a) }
        put(o, "bluetooth_contact_sharing_disabled") { dpm.getBluetoothContactSharingDisabled(a) }
        if (Build.VERSION.SDK_INT >= 30) {
            put(o, "auto_time") { dpm.getAutoTimeEnabled(a) }
            put(o, "wifi_lockdown") { dpm.hasLockdownAdminConfiguredNetworks(a) }
        }
        if (Build.VERSION.SDK_INT >= 31) put(o, "usb_data_signaling") { dpm.isUsbDataSignalingEnabled }
        put(o, "secondary_users") { dpm.getSecondaryUsers(a).size }
        put(o, "active_restrictions") {
            val r = dpm.getUserRestrictions(a); JSONArray(r.keySet().filter { r.getBoolean(it) }.sorted())
        }
        val support = JSONObject()
        OwnerPolicyCatalog.OPS.forEach { (op, tier) ->
            val min = when (op) { "default_dialer" -> 34; "user_control", "wifi_lockdown", "auto_time" -> 30; else -> 28 }
            support.put(op, JSONObject().put("tier", tier.name).put("supported", Build.VERSION.SDK_INT >= min))
        }
        o.put("ops", support)
        return o
    }

    private fun policyName(p: SystemUpdatePolicy?): String = when (p?.policyType) {
        null -> "none"
        SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC -> "automatic"
        SystemUpdatePolicy.TYPE_INSTALL_WINDOWED -> "windowed:${p.installWindowStart}-${p.installWindowEnd}"
        SystemUpdatePolicy.TYPE_POSTPONE -> "postpone"
        else -> "type_${p.policyType}"
    }

    private inline fun put(o: JSONObject, key: String, block: () -> Any?) {
        try { o.put(key, block() ?: JSONObject.NULL) } catch (t: Throwable) { o.put(key, "error: ${t.javaClass.simpleName}") }
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    @Suppress("unused") private val tiers = Tier.values()
}
