package com.rjkyker.shizukuai.broker.dhizuku

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuBinderWrapper
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.rosan.dhizuku.shared.DhizukuVariables
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Dhizuku client: lets the broker borrow Dhizuku's Device Owner privileges after the user
 * approves the broker inside the Dhizuku app. Only non-destructive, reversible DPM calls are
 * exposed. Wipe / reset-password / remove-admin / factory-reset style calls are intentionally absent.
 */
object DhizukuGateway {

    private const val TAG = "DhizukuGateway"

    /**
     * User restrictions the broker may toggle. Deliberately excludes anything that could lock the
     * owner out of their own device: no_debugging_features (kills ADB), no_modify_accounts,
     * no_config_vpn, no_install_apps / no_uninstall_apps, no_factory_reset, no_add_user, etc.
     */
    val ALLOWED_RESTRICTIONS: Set<String> = setOf(
        "no_config_wifi",
        "no_install_unknown_sources",
        "no_config_bluetooth",
        "no_bluetooth_sharing",
        "no_usb_file_transfer",
        "no_config_location",
        "no_share_location",
        "no_adjust_volume",
        "no_config_screen_timeout",
        "no_config_brightness",
        "no_airplane_mode",
        "no_config_tethering",
    )

    /** Packages that must never be hidden or suspended by the broker. */
    private val PROTECTED_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.rosan.dhizuku",
        "moe.shizuku.privileged.api",
        "com.tailscale.ipn",
        "com.termux",
        "com.zebra.mdna.els",
        "com.symbol.datawedge",
    )

    @Volatile private var initialized = false
    @Volatile private var wrappedDpm: DevicePolicyManager? = null
    @Volatile var lastPermissionResult: String = "not requested"
        private set

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(DhizukuVariables.OFFICIAL_PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Dhizuku.init(context); returns true when the Dhizuku server binder is reachable. */
    fun init(context: Context): Boolean {
        return try {
            initialized = Dhizuku.init(context.applicationContext)
            initialized
        } catch (t: Throwable) {
            Log.w(TAG, "Dhizuku.init failed", t)
            initialized = false
            false
        }
    }

    fun isGranted(): Boolean = try {
        initialized && Dhizuku.isPermissionGranted()
    } catch (t: Throwable) {
        false
    }

    /**
     * Ask Dhizuku for API permission. This is what makes the broker appear in Dhizuku's app list;
     * the user approves it there. [onResult] runs on a binder thread.
     */
    fun requestPermission(context: Context, onResult: (Boolean) -> Unit = {}): String {
        if (!isInstalled(context)) return "dhizuku_not_installed".also { lastPermissionResult = it }
        if (!initialized && !init(context)) return "dhizuku_not_running".also { lastPermissionResult = it }
        if (isGranted()) return "already_granted".also { lastPermissionResult = it }
        return try {
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    val ok = grantResult == PackageManager.PERMISSION_GRANTED
                    lastPermissionResult = if (ok) "granted" else "denied"
                    if (ok) wrappedDpm = null
                    onResult(ok)
                }
            })
            "requested".also { lastPermissionResult = it }
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
            "request_failed: ${t.message}".also { lastPermissionResult = it }
        }
    }

    fun ownerComponent(): ComponentName? = try {
        if (initialized) Dhizuku.getOwnerComponent() else null
    } catch (_: Throwable) {
        null
    }

    /**
     * Documented Dhizuku pattern: build a DevicePolicyManager in Dhizuku's package context and swap
     * its IDevicePolicyManager binder for Dhizuku.binderWrapper(...) so calls run as the owner.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun dpm(context: Context): DevicePolicyManager {
        wrappedDpm?.let { return it }
        check(isGranted()) { "Dhizuku permission not granted; approve the broker in the Dhizuku app" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/app/admin/DevicePolicyManager;",
                "Landroid/app/admin/IDevicePolicyManager"
            )
        }
        val pkgCtx = context.createPackageContext(
            DhizukuVariables.OFFICIAL_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY
        )
        val manager = pkgCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val field = DevicePolicyManager::class.java.getDeclaredField("mService").apply { isAccessible = true }
        val oldInterface = field.get(manager) as android.os.IInterface
        if (oldInterface.asBinder() is DhizukuBinderWrapper) {
            wrappedDpm = manager
            return manager
        }
        val newBinder: IBinder = Dhizuku.binderWrapper(oldInterface.asBinder())
        val stub = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
        val newInterface = stub.getMethod("asInterface", IBinder::class.java).invoke(null, newBinder)
        field.set(manager, newInterface)
        wrappedDpm = manager
        return manager
    }

    // ---------------- actions ----------------

    fun status(context: Context): JSONObject {
        val installed = isInstalled(context)
        if (installed && !initialized) init(context)
        val out = JSONObject()
            .put("ok", true)
            .put("installed", installed)
            .put("server_reachable", initialized)
            .put("granted", isGranted())
            .put("last_request", lastPermissionResult)
        if (initialized) {
            runCatching { out.put("dhizuku_version", Dhizuku.getVersionName()) }
            runCatching { out.put("dhizuku_version_code", Dhizuku.getVersionCode()) }
            runCatching { out.put("owner_package", Dhizuku.getOwnerPackageName()) }
            ownerComponent()?.let { out.put("owner_component", it.flattenToShortString()) }
        }
        if (isGranted()) {
            runCatching {
                val d = dpm(context)
                val owner = ownerComponent()
                out.put("is_device_owner", d.isDeviceOwnerApp(DhizukuVariables.OFFICIAL_PACKAGE_NAME))
                if (owner != null) {
                    val r = d.getUserRestrictions(owner)
                    out.put("active_restrictions", JSONArray(r.keySet().filter { r.getBoolean(it) }.sorted()))
                }
            }.onFailure { out.put("dpm_error", it.message) }
        }
        return out
    }

    fun connect(context: Context): JSONObject {
        val r = requestPermission(context)
        return JSONObject().put("ok", r == "requested" || r == "already_granted")
            .put("result", r)
            .put("note", "Approve 'Shizuku AI Broker' in the Dhizuku app when prompted")
    }

    fun setRestriction(context: Context, key: String, enabled: Boolean): JSONObject {
        if (key !in ALLOWED_RESTRICTIONS) {
            return err("restriction not allow-listed: $key; allowed=${ALLOWED_RESTRICTIONS.sorted()}")
        }
        val owner = ownerComponent() ?: return err("Dhizuku owner component unavailable")
        val d = dpm(context)
        if (enabled) d.addUserRestriction(owner, key) else d.clearUserRestriction(owner, key)
        val now = d.getUserRestrictions(owner).getBoolean(key, false)
        return JSONObject().put("ok", now == enabled).put("restriction", key).put("enabled", now)
    }

    fun setPermissionGrantState(context: Context, pkg: String, permission: String, state: Int): JSONObject {
        guardPackage(pkg)?.let { return it }
        val owner = ownerComponent() ?: return err("Dhizuku owner component unavailable")
        val ok = dpm(context).setPermissionGrantState(owner, pkg, permission, state)
        return JSONObject().put("ok", ok).put("package", pkg).put("permission", permission).put("state", state)
    }

    fun setHidden(context: Context, pkg: String, hidden: Boolean): JSONObject {
        guardPackage(pkg)?.let { return it }
        val owner = ownerComponent() ?: return err("Dhizuku owner component unavailable")
        val d = dpm(context)
        val ok = d.setApplicationHidden(owner, pkg, hidden)
        return JSONObject().put("ok", ok).put("package", pkg)
            .put("hidden", d.isApplicationHidden(owner, pkg))
    }

    fun setSuspended(context: Context, pkg: String, suspended: Boolean): JSONObject {
        guardPackage(pkg)?.let { return it }
        val owner = ownerComponent() ?: return err("Dhizuku owner component unavailable")
        val failed = dpm(context).setPackagesSuspended(owner, arrayOf(pkg), suspended)
        return JSONObject().put("ok", !failed.contains(pkg)).put("package", pkg).put("suspended", suspended)
    }

    fun getScopes(): JSONObject {
        val scopes = Dhizuku.getDelegatedScopes() ?: emptyArray()
        return JSONObject().put("ok", true).put("scopes", JSONArray(scopes.toList()))
    }

    /** Dhizuku.setDelegatedScopes applies to the calling client (this broker) only. */
    fun setScopes(scopes: List<String>): JSONObject {
        val allowed = setOf(
            DevicePolicyManager.DELEGATION_PERMISSION_GRANT,
            DevicePolicyManager.DELEGATION_PACKAGE_ACCESS,
            DevicePolicyManager.DELEGATION_BLOCK_UNINSTALL,
            DevicePolicyManager.DELEGATION_APP_RESTRICTIONS,
            DevicePolicyManager.DELEGATION_ENABLE_SYSTEM_APP,
        )
        val bad = scopes.filter { it !in allowed }
        if (bad.isNotEmpty()) return err("scopes not allow-listed: $bad; allowed=${allowed.sorted()}")
        Dhizuku.setDelegatedScopes(scopes.toTypedArray())
        return getScopes()
    }

    private fun guardPackage(pkg: String): JSONObject? = when {
        pkg.isBlank() -> err("package required")
        pkg in PROTECTED_PACKAGES -> err("package is protected: $pkg")
        pkg.startsWith("com.rjkyker.shizukuai") -> err("broker cannot target itself")
        else -> null
    }

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)
}
