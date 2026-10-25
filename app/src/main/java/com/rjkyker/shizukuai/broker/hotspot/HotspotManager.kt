package com.rjkyker.shizukuai.broker.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rjkyker.shizukuai.broker.sensing.WifiDetection
import com.rjkyker.shizukuai.broker.shell.ShellExecutor
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Hotspot / Extender (1.8.5).
 *
 * Primary path: privileged shell (Shizuku/ADB shell uid holds NETWORK_SETTINGS / TETHER_PRIVILEGED):
 *   `cmd wifi start-softap <ssid> wpa2|wpa3 <pass> [-b 2|5]` / `cmd wifi stop-softap`.
 * When the soft AP comes up, Android's Tethering service starts IP serving (DHCP + NAT) on it and shares the
 * current upstream (Wi-Fi if STA+AP concurrency keeps the client up = repeater; otherwise cellular).
 * Fallback: WifiManager.startLocalOnlyHotspot — NO internet (clearly labelled), random SSID/pass from Android.
 *
 * Secrets: the passphrase is generated on the phone, stored in app-private prefs, shown only in the app UI,
 * and everywhere else (logs, MCP, /health, Airtable) appears masked.
 *
 * Honest limit: the TC22 cannot boost other phones' radios. It helps only when the TC22 sits where the
 * signal is stronger than where those phones are.
 */
object HotspotManager {
    private const val TAG = "Hotspot"
    private const val PREFS = "broker_hotspot"
    const val DEFAULT_SSID = "TC22-Extender"
    const val HONEST_NOTE = "It can't boost other phones' radios. It helps when the TC22 is placed where the signal is stronger."
    private val SSID_RE = Regex("^[A-Za-z0-9 _.-]{1,32}$")
    private const val PASS_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    @Volatile private var lohsReservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var lastMode = "off"   // off | softap | local_only
    @Volatile private var lastError = ""
    @Volatile private var startedAtMs = 0L

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ssid(ctx: Context): String = p(ctx).getString("ssid", null)?.takeIf { SSID_RE.matches(it) } ?: DEFAULT_SSID

    /** Phone-generated passphrase (≥ 14 chars). Only call from UI code that shows it to RJ. */
    fun passphraseForUi(ctx: Context): String {
        val cur = p(ctx).getString("pass", null)
        if (cur != null && cur.length >= 12) return cur
        val fresh = generatePassphrase()
        p(ctx).edit().putString("pass", fresh).apply()
        return fresh
    }

    fun regeneratePassphrase(ctx: Context): String {
        val fresh = generatePassphrase(); p(ctx).edit().putString("pass", fresh).apply(); return fresh
    }

    fun generatePassphrase(len: Int = 14, rnd: SecureRandom = SecureRandom()): String =
        (1..len.coerceAtLeast(12)).map { PASS_CHARS[rnd.nextInt(PASS_CHARS.length)] }.joinToString("")

    fun mask(pass: String): String = if (pass.length <= 4) "****" else pass.take(2) + "*".repeat(pass.length - 4) + pass.takeLast(2)

    fun band(ctx: Context): String = p(ctx).getString("band", "2") ?: "2"
    fun security(ctx: Context): String = p(ctx).getString("security", "wpa2") ?: "wpa2"

    /** Pure: build the shell command (passphrase inline — never log the result). */
    fun softApCommand(ssid: String, security: String, pass: String, band: String?): String {
        require(SSID_RE.matches(ssid)) { "bad ssid" }
        require(pass.length in 8..63 && pass.all { it in PASS_CHARS || it.isLetterOrDigit() }) { "bad passphrase" }
        val sec = if (security == "wpa3") "wpa3" else "wpa2"
        val b = when (band) { "5" -> " -b 5"; "2" -> " -b 2"; else -> "" }
        return "cmd wifi start-softap '${ssid}' $sec '$pass'$b"
    }

    fun staApConcurrency(ctx: Context): Boolean? = runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT >= 30) wm.isStaApConcurrencySupported else null
    }.getOrNull()

    /** Blocking (shell); call off the main thread. */
    fun start(ctx: Context, args: JSONObject = JSONObject()): JSONObject {
        val app = ctx.applicationContext
        args.optString("ssid").trim().takeIf { it.isNotEmpty() }?.let {
            if (!SSID_RE.matches(it)) return err("ssid must be 1-32 chars [A-Za-z0-9 _.-]")
            p(app).edit().putString("ssid", it).apply()
        }
        args.optString("band").takeIf { it in setOf("2", "5", "any") }?.let { p(app).edit().putString("band", it).apply() }
        args.optString("security").takeIf { it in setOf("wpa2", "wpa3") }?.let { p(app).edit().putString("security", it).apply() }
        val ssid = ssid(app)
        val pass = passphraseForUi(app)
        val conc = staApConcurrency(app)
        val wifiWasConnected = wifiConnected(app)
        val r = runCatching { ShellExecutor.execSync(softApCommand(ssid, security(app), pass, band(app)), 20L) }.getOrNull()
        val out = scrub(((r?.stdout ?: "") + " " + (r?.stderr ?: "")).trim(), pass)
        Thread.sleep(3_000)
        var st = status(app)
        if (st.optBoolean("softap_enabled")) {
            lastMode = "softap"; startedAtMs = System.currentTimeMillis(); lastError = ""
            return st.put("ok", true).put("started", "softap").put("via", "shell:${r?.via}")
                .put("shell_output", out.take(300))
                .put("wifi_client_was_connected", wifiWasConnected)
        }
        // Shell path failed → LocalOnlyHotspot (no internet), only if asked or by default.
        lastError = "softap did not come up: ${out.take(200)}"
        if (args.optBoolean("allow_local_only", true)) {
            val lo = startLocalOnly(app)
            st = status(app)
            return st.put("ok", lo.optBoolean("ok")).put("started", if (lo.optBoolean("ok")) "local_only" else "none")
                .put("softap_error", lastError).put("local_only", lo)
        }
        return st.put("ok", false).put("error", lastError)
    }

    fun stop(ctx: Context): JSONObject {
        val app = ctx.applicationContext
        runCatching { lohsReservation?.close() }; lohsReservation = null
        val r = runCatching { ShellExecutor.execSync("cmd wifi stop-softap", 15L) }.getOrNull()
        Thread.sleep(2_000)
        lastMode = "off"; startedAtMs = 0L
        return status(app).put("ok", true).put("stopped", true).put("exit_code", r?.exitCode ?: -1)
    }

    @SuppressLint("MissingPermission")
    private fun startLocalOnly(ctx: Context): JSONObject {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = Object()
        var res: JSONObject? = null
        val cb = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                lohsReservation = reservation
                lastMode = "local_only"; startedAtMs = System.currentTimeMillis()
                synchronized(lock) { res = JSONObject().put("ok", true).put("internet", false)
                    .put("note", "Local-only hotspot: devices can reach this phone, but there is NO internet sharing."); lock.notifyAll() }
            }
            override fun onFailed(reason: Int) {
                synchronized(lock) { res = JSONObject().put("ok", false).put("reason", reason); lock.notifyAll() }
            }
        }
        return try {
            Handler(Looper.getMainLooper()).post { runCatching { wm.startLocalOnlyHotspot(cb, null) }
                .onFailure { synchronized(lock) { res = JSONObject().put("ok", false).put("error", it.message); lock.notifyAll() } } }
            synchronized(lock) { if (res == null) lock.wait(8_000) }
            res ?: JSONObject().put("ok", false).put("error", "local-only hotspot timeout")
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message) }
    }

    /** Status: AP state, clients, upstream, band/channel, upstream RSSI. Blocking (shell); off main thread. */
    @Suppress("DEPRECATION")
    fun status(ctx: Context): JSONObject {
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val apState = runCatching { WifiManager::class.java.getMethod("getWifiApState").invoke(wm) as Int }.getOrDefault(-1)
        val apOn = apState == 13 // WIFI_AP_STATE_ENABLED
        val dumpWifi = runCatching { ShellExecutor.execSync("dumpsys wifi | grep -iE 'SoftApInfo|mConnectedClients|Connected clients|getConnectedClients|mCurrentSoftApInfo|frequency=|bandwidth=' | head -40", 10L).stdout }.getOrDefault("")
        val dumpTether = runCatching { ShellExecutor.execSync("dumpsys tethering | grep -iE 'upstream|tethered|wlan|ap0|swlan|state' | head -40", 10L).stdout }.getOrDefault("")
        val clients = parseClientCount(dumpWifi)
        val apFreq = parseApFrequency(dumpWifi)
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val upstream = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val info = runCatching { wm.connectionInfo }.getOrNull()
        val staConnected = wifiConnected(app)
        val conc = staApConcurrency(app)
        val tetherUpstream = parseTetherUpstream(dumpTether)
        val pass = p(app).getString("pass", "") ?: ""
        return JSONObject()
            .put("softap_enabled", apOn || lastMode == "local_only")
            .put("ap_state", apState)
            .put("mode", if (apOn) (if (lastMode == "local_only") "local_only" else "softap") else if (lastMode == "local_only") "local_only" else "off")
            .put("internet_shared", apOn && lastMode != "local_only" && tetherUpstream.isNotEmpty())
            .put("ssid", ssid(app))
            .put("passphrase_masked", if (pass.isEmpty()) "(generated on first start)" else mask(pass))
            .put("security", security(app))
            .put("band_pref", band(app))
            .put("ap_frequency_mhz", apFreq ?: JSONObject.NULL)
            .put("ap_band", apFreq?.let { WifiDetection.bandOf(it) } ?: JSONObject.NULL)
            .put("ap_channel", apFreq?.let { WifiDetection.channelOf(it) } ?: JSONObject.NULL)
            .put("connected_clients", clients ?: JSONObject.NULL)
            .put("upstream", upstream)
            .put("tether_upstream_iface", tetherUpstream)
            .put("sta_connected", staConnected)
            .put("upstream_rssi_dbm", if (staConnected) info?.rssi ?: JSONObject.NULL else JSONObject.NULL)
            .put("sta_ap_concurrency_supported", conc ?: JSONObject.NULL)
            .put("repeater_mode", apOn && staConnected && conc == true)
            .put("repeater_note", when (conc) {
                true -> "STA+AP concurrency supported: the phone keeps its Wi-Fi client up while the hotspot runs (repeater)."
                false -> "STA+AP concurrency NOT supported on this phone: starting the hotspot drops Wi-Fi, so only mobile data can be shared."
                else -> "STA+AP concurrency unknown on this Android version."
            })
            .put("started_at_ms", startedAtMs)
            .put("last_error", lastError)
            .put("honest_note", HONEST_NOTE)
    }

    private fun wifiConnected(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true }
    }.getOrDefault(false)

    private fun scrub(s: String, pass: String) = if (pass.isNotEmpty()) s.replace(pass, mask(pass)) else s

    // ---- pure parsers (unit-tested) ----
    fun parseClientCount(dump: String): Int? {
        Regex("""(?i)mConnectedClients[^\n]*?size[=:]\s*(\d+)""").find(dump)?.let { return it.groupValues[1].toInt() }
        Regex("""(?i)connected clients?[:=\s]+(\d+)""").find(dump)?.let { return it.groupValues[1].toInt() }
        Regex("""(?i)mConnectedClients[=:]\s*\[([^\]]*)\]""").find(dump)?.let { m ->
            val body = m.groupValues[1].trim(); return if (body.isEmpty()) 0 else Regex("""(?i)macAddress|[0-9a-f]{2}(:[0-9a-f]{2}){5}""").findAll(body).count().coerceAtLeast(1)
        }
        return null
    }

    fun parseApFrequency(dump: String): Int? =
        Regex("""(?i)(?:SoftApInfo|mCurrentSoftApInfo)[^\n]*?frequency\s*[=:]\s*(\d{4})""").find(dump)?.groupValues?.get(1)?.toInt()
            ?.takeIf { it > 0 }

    fun parseTetherUpstream(dump: String): String =
        Regex("""(?i)(?:current upstream interface\(s\)|upstream interface|mCurrentUpstreamIfaceSet|Upstream)\s*[:=]\s*\[?([A-Za-z0-9_.,\s-]+)\]?""")
            .find(dump)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) } ?: ""

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)
}
