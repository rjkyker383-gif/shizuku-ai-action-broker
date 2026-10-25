package com.rjkyker.shizukuai.broker.sensing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Wi-Fi detection" (1.8.5): always-on, low-duty background loop, DEFAULT ON.
 *
 *  - Every [AP_SCAN_MS] it asks WifiManager for a Wi-Fi scan and keeps the nearby access-point list
 *    (SSID, BSSID, RSSI dBm, frequency, channel) the phone itself can hear.
 *  - Every [PRESENCE_EVERY_N] AP rounds it also runs a short [WifiPresenceEngine] window (RSSI variance
 *    of the phone's own link) for a coarse "motion near this phone" score.
 *
 * Honest limits: this is the phone's own receiver measuring signal strength. It cannot see through walls,
 * cannot identify, count or locate people, and has no CSI. RSSI per AP only tells you roughly how loud
 * that AP is at the phone. Android throttles foreground scans (4 per 2 min) and needs Location on plus
 * ACCESS_FINE_LOCATION for scan results; when those are missing the state says so instead of guessing.
 */
object WifiDetection {
    private const val TAG = "WifiDetection"
    private const val PREFS = "broker_wifi_detection"
    private const val KEY_ENABLED = "enabled"
    const val DEFAULT_ENABLED = true
    const val AP_SCAN_MS = 45_000L
    private const val PRESENCE_EVERY_N = 4
    private const val MAX_APS = 40

    @Volatile private var thread: Thread? = null
    @Volatile private var lastApScanAtMs = 0L
    @Volatile private var lastApError = ""
    @Volatile private var lastAps: JSONArray = JSONArray()
    @Volatile private var lastScanRequestAccepted: Boolean? = null
    @Volatile private var rounds = 0L

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start(ctx) else stop()
    }

    @Synchronized
    fun start(ctx: Context) {
        if (!isEnabled(ctx)) return
        if (thread?.isAlive == true) return
        val app = ctx.applicationContext
        thread = Thread({
            var n = 0
            while (!Thread.currentThread().isInterrupted && isEnabled(app)) {
                runCatching { scanAps(app, requestFresh = true) }.onFailure { lastApError = it.message ?: "scan failed" }
                if (n % PRESENCE_EVERY_N == 0) runCatching { WifiPresenceEngine.scan(app, 4_000L) }
                n++; rounds++
                try { Thread.sleep(AP_SCAN_MS) } catch (_: InterruptedException) { break }
            }
        }, "broker-wifi-detection").apply { isDaemon = true; start() }
        Log.i(TAG, "Wi-Fi detection loop started")
    }

    @Synchronized
    fun stop() {
        thread?.interrupt(); thread = null
    }

    fun hasLocationPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationOn(ctx: Context): Boolean = runCatching {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else true
    }.getOrDefault(false)

    /** Blocking-ish (≤ ~4 s when [requestFresh]); call off the main thread. */
    @Suppress("DEPRECATION")
    fun scanAps(ctx: Context, requestFresh: Boolean): JSONObject {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return fail("WifiManager unavailable")
        if (!wifi.isWifiEnabled) return fail("Wi-Fi radio is off")
        if (!hasLocationPermission(ctx)) return fail("ACCESS_FINE_LOCATION not granted (needed by Android for scan results)")
        if (!locationOn(ctx)) return fail("Location services are off (Android needs them on for Wi-Fi scan results)")
        if (requestFresh) {
            lastScanRequestAccepted = runCatching { wifi.startScan() }.getOrDefault(false)
            try { Thread.sleep(3_500) } catch (_: InterruptedException) {}
        }
        val results = runCatching { wifi.scanResults }.getOrNull().orEmpty()
        val nowElapsedUs = android.os.SystemClock.elapsedRealtime() * 1000
        val arr = JSONArray()
        results.sortedByDescending { it.level }.take(MAX_APS).forEach { r ->
            arr.put(JSONObject()
                .put("ssid", r.SSID ?: "")
                .put("bssid", r.BSSID ?: "")
                .put("rssi_dbm", r.level)
                .put("freq_mhz", r.frequency)
                .put("channel", channelOf(r.frequency))
                .put("band", bandOf(r.frequency))
                .put("strength", strengthLabel(r.level))
                .put("age_s", ((nowElapsedUs - r.timestamp) / 1_000_000L).coerceAtLeast(0)))
        }
        lastAps = arr
        lastApScanAtMs = System.currentTimeMillis()
        lastApError = if (arr.length() == 0) "scan returned 0 APs" else ""
        return JSONObject().put("ok", arr.length() > 0).put("ap_count", arr.length()).put("aps", arr)
            .put("scan_request_accepted", lastScanRequestAccepted)
            .put("at_ms", lastApScanAtMs).put("note", NOTE)
    }

    fun stateJson(ctx: Context, includeAps: Boolean = false): JSONObject {
        val o = JSONObject()
            .put("enabled", isEnabled(ctx))
            .put("running", thread?.isAlive == true)
            .put("location_permission", hasLocationPermission(ctx))
            .put("location_on", locationOn(ctx))
            .put("interval_ms", AP_SCAN_MS)
            .put("rounds", rounds)
            .put("last_ap_scan_at_ms", lastApScanAtMs)
            .put("last_ap_count", lastAps.length())
            .put("strongest_rssi_dbm", lastAps.optJSONObject(0)?.optInt("rssi_dbm") ?: JSONObject.NULL)
            .put("last_error", lastApError)
            .put("presence", WifiPresenceEngine.stateJson())
            .put("kind", "near_device_rssi_only")
            .put("cannot_see_through_walls", true)
        if (includeAps) o.put("aps", lastAps)
        return o
    }

    private fun fail(msg: String): JSONObject {
        lastApError = msg
        return JSONObject().put("ok", false).put("error", msg).put("note", NOTE)
    }

    const val NOTE = "RSSI of access points as heard by this phone only. No CSI, not through-wall, " +
        "cannot identify, count or locate people."

    fun channelOf(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5160..5885 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> 0
    }

    fun bandOf(freq: Int): String = when {
        freq in 2400..2500 -> "2.4GHz"
        freq in 4900..5900 -> "5GHz"
        freq in 5925..7125 -> "6GHz"
        else -> "other"
    }

    fun strengthLabel(rssi: Int): String = when {
        rssi >= -55 -> "strong"
        rssi >= -67 -> "good"
        rssi >= -75 -> "fair"
        else -> "weak"
    }
}
