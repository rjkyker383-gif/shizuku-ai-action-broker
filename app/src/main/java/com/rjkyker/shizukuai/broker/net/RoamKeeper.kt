package com.rjkyker.shizukuai.broker.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import com.rjkyker.shizukuai.broker.bridge.ShizukuBridge
import com.rjkyker.shizukuai.broker.dhizuku.DhizukuGateway
import com.rjkyker.shizukuai.broker.discovery.NsdAdvertiser
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.util.SecureSettingsKeepalive
import org.json.JSONObject

/**
 * Keeps the broker reachable across reboot + Wi-Fi changes ("roam-proof").
 *
 * On boot / package replace / any network change (Wi-Fi swap, VPN up/down, link change), debounced:
 *  1. re-detect Tailscale 100.x IP(s) and rebind BrokerServer (127.0.0.1 + Tailscale only),
 *  2. re-register mDNS on the new network,
 *  3. best-effort `adb_wifi_enabled=1` (wireless debugging; Android picks a new TLS port — the box
 *     keepalive already scans for it). Plain `adb tcpip 5555` needs root/adbd restart, not possible here,
 *  4. if Tailscale is installed but no 100.x address is up, send Tailscale's own CONNECT_VPN broadcast
 *     (never logs in / never changes accounts),
 *  5. keep broker + Tailscale on the Doze allowlist via Shizuku shell (`dumpsys deviceidle whitelist +pkg`).
 * Accessibility is re-asserted by the BrokerService watchdog.
 *
 * [hardenNow] (button) additionally asks Dhizuku (device owner) to set Tailscale as the always-on VPN
 * (lockdown=false so plain Wi-Fi still works if Tailscale is down).
 */
object RoamKeeper {
    private const val TAG = "RoamKeeper"
    const val TAILSCALE_PKG = "com.tailscale.ipn"
    private const val DEBOUNCE_MS = 2_500L

    private val thread by lazy { HandlerThread("RoamKeeper").also { it.start() } }
    private val bg by lazy { Handler(thread.looper) }
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile var lastEvent: String = "none"; private set
    @Volatile var lastEventAt: Long = 0L; private set
    @Volatile var lastResult: JSONObject = JSONObject(); private set

    fun start(context: Context) {
        val app = context.applicationContext
        if (callback != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = schedule(app, "available")
            override fun onLost(network: Network) = schedule(app, "lost")
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = schedule(app, "link_changed")
        }
        try {
            // Include VPN networks so Tailscale up/down also triggers a rebind.
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(req, cb)
            callback = cb
            Log.i(TAG, "network callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback: ${e.message}")
        }
        schedule(app, "start")
    }

    fun stop(context: Context) {
        val cb = callback ?: return
        runCatching { context.applicationContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        callback = null
    }

    fun schedule(context: Context, reason: String) {
        val app = context.applicationContext
        bg.removeCallbacksAndMessages(null)
        bg.postDelayed({ recover(app, reason) }, DEBOUNCE_MS)
    }

    /** Idempotent recovery pass. Runs on a background thread. Never throws. */
    fun recover(context: Context, reason: String): JSONObject {
        val out = JSONObject().put("reason", reason).put("ts", System.currentTimeMillis())
        lastEvent = reason; lastEventAt = System.currentTimeMillis()
        try {
            val ts = NetInfo.tailscaleIps()
            out.put("tailscale_ips", org.json.JSONArray(ts))
            val server = BrokerServer.get()
            if (server == null || !server.isRunning) BrokerServer.startFrom(context)
            else out.put("rebound", server.rebindIfNeeded())
            out.put("bound", org.json.JSONArray(BrokerServer.get()?.boundAddresses.orEmpty()))
            runCatching { NsdAdvertiser.unregister(context); NsdAdvertiser.register(context) }
            runCatching { SecureSettingsKeepalive.reenableAdbAndDevSettings(context) }
            if (ts.isEmpty() && isInstalled(context, TAILSCALE_PKG)) out.put("tailscale_connect", connectTailscale(context))
            out.put("doze", ensureDozeAllowlist(context))
        } catch (e: Exception) {
            out.put("error", e.message ?: "recover failed")
        }
        lastResult = out
        Log.i(TAG, "recover($reason): bound=${out.opt("bound")} ts=${out.opt("tailscale_ips")}")
        return out
    }

    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Tailscale's documented automation receiver (com.tailscale.ipn.IPNReceiver, action CONNECT_VPN). */
    fun connectTailscale(context: Context): String = try {
        context.sendBroadcast(
            Intent("com.tailscale.ipn.CONNECT_VPN").setComponent(ComponentName(TAILSCALE_PKG, "com.tailscale.ipn.IPNReceiver"))
        )
        "connect_vpn_sent"
    } catch (e: Exception) {
        "failed: ${e.message}"
    }

    /** Keep broker + Tailscale off Doze restrictions (shell uid via Shizuku can edit the deviceidle allowlist). */
    fun ensureDozeAllowlist(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java)
        val pkgs = listOf(context.packageName, TAILSCALE_PKG).filter { isInstalled(context, it) }
        val res = StringBuilder()
        for (p in pkgs) {
            if (p == context.packageName && pm?.isIgnoringBatteryOptimizations(p) == true) { res.append("$p=ok "); continue }
            val r = runCatching { ShizukuBridge.exec("dumpsys deviceidle whitelist +$p") }.getOrNull()
            res.append("$p=${if (r?.first == 0) "added" else "unavailable"} ")
        }
        return res.toString().trim()
    }

    /** One-tap hardening (user-initiated). Honest per-step results. */
    fun hardenNow(context: Context): JSONObject {
        val app = context.applicationContext
        val out = JSONObject()
        out.put("always_on_vpn", setTailscaleAlwaysOn(app))
        out.put("doze", ensureDozeAllowlist(app))
        out.put("recover", recover(app, "harden"))
        return out
    }

    /** Device-owner API via Dhizuku: Tailscale always-on VPN, lockdown off. */
    fun setTailscaleAlwaysOn(context: Context): String {
        if (!isInstalled(context, TAILSCALE_PKG)) return "tailscale_not_installed"
        if (!DhizukuGateway.isGranted()) return "dhizuku_not_granted (open Settings > Network > VPN > Tailscale > Always-on instead)"
        return try {
            val owner = DhizukuGateway.ownerComponent() ?: return "dhizuku_owner_unavailable"
            DhizukuGateway.dpm(context).setAlwaysOnVpnPackage(owner, TAILSCALE_PKG, false)
            "set"
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
        }
    }
}
