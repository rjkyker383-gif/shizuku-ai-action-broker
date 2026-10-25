/*
 * ZebraCapabilityLayer — NO-LICENSE Zebra / Android enterprise control.
 *
 * This layer ONLY uses pathways that work WITHOUT a paid Zebra Mobility DNA license:
 *   - Zebra DataWedge Intent API (profile CRUD, scanner enable/disable, soft scan) — free on every
 *     Zebra device with DataWedge installed.
 *   - Standard Android WifiManager (network suggestions / legacy configs, enable/disable) and, when
 *     the broker holds device-owner via Dhizuku, DevicePolicyManager Wi-Fi/network policy.
 *   - Shizuku shell (`cmd wifi`, `svc wifi`) for radio/roaming knobs that the platform exposes to
 *     the shell user — still no license needed.
 *
 * INTENTIONALLY OMITTED — these GENUINELY require a paid Zebra license / server activation and there
 * is NO legitimate code path to "unlock" them, so this layer does not attempt to:
 *   - Fusion Advanced / enterprise Wi-Fi tiers (advanced roaming/802.11r-k-v tuning UI, band
 *     locking, advanced PMF, CCKM) gated by a Mobility DNA Wi-Fi license.
 *   - Zebra Enterprise Browser and the Enterprise Keyboard premium features.
 *   - OEMConfig premium / advanced MX feature tiers and premium StageNow profiles.
 *   - Any license-check bypass, reflection into Zebra licensing daemons, or forged/synthetic license
 *     keys. Defeating Zebra's license validation is NOT implemented here by design.
 *
 * Everything below is real, buildable, and reversible; it never fabricates success.
 */
package com.rjkyker.shizukuai.broker.zebra

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeApi
import com.rjkyker.shizukuai.broker.shell.ShellExecutor
import org.json.JSONArray
import org.json.JSONObject

object ZebraCapabilityLayer {

    private const val TAG = "ZebraCap"

    // ---------------- zebra_wifi_config ----------------

    /**
     * No-license Wi-Fi control. ops:
     *  - status: radio state + current association
     *  - enable / disable: toggle the radio (WifiManager pre-Q, else `svc wifi` via Shizuku)
     *  - add: register a Wi-Fi network (WifiNetworkSuggestion on Q+, legacy addNetwork pre-Q)
     *  - remove: drop a previously added suggestion/config by SSID
     *  - roaming: best-effort radio/roaming knobs via `cmd wifi` (shell user, no license)
     */
    fun wifiConfig(context: Context, args: JSONObject): JSONObject {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return err("WifiManager unavailable")
        return when (args.optString("op", "status").lowercase()) {
            "status" -> wifiStatus(wifi)
            "enable" -> setRadio(wifi, true)
            "disable" -> setRadio(wifi, false)
            "add" -> addNetwork(wifi, args)
            "remove" -> removeNetwork(wifi, args)
            "roaming" -> roaming(args)
            else -> err("op must be status|enable|disable|add|remove|roaming")
        }
    }

    private fun wifiStatus(wifi: WifiManager): JSONObject {
        return try {
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo
            JSONObject().put("ok", true)
                .put("wifi_enabled", wifi.isWifiEnabled)
                .put("state", wifi.wifiState)
                .put("connection", JSONObject()
                    .put("ssid", info?.ssid ?: JSONObject.NULL)
                    .put("bssid", info?.bssid ?: JSONObject.NULL)
                    .put("rssi_dbm", info?.rssi ?: JSONObject.NULL)
                    .put("link_speed_mbps", info?.linkSpeed ?: JSONObject.NULL)
                    .put("frequency_mhz", runCatching { info?.frequency }.getOrNull() ?: JSONObject.NULL))
        } catch (e: Exception) {
            err("wifi status failed: ${e.message}")
        }
    }

    private fun setRadio(wifi: WifiManager, enable: Boolean): JSONObject {
        // Pre-Q apps may toggle directly; Q+ forbids it, so fall back to the shell (Shizuku).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return try {
                @Suppress("DEPRECATION")
                val ok = wifi.setWifiEnabled(enable)
                JSONObject().put("ok", ok).put("via", "WifiManager").put("enabled", enable)
            } catch (e: Exception) {
                shellRadio(enable)
            }
        }
        return shellRadio(enable)
    }

    private fun shellRadio(enable: Boolean): JSONObject {
        val cmd = if (enable) "svc wifi enable" else "svc wifi disable"
        val r = ShellExecutor.execSync(cmd, 10L)
        return JSONObject().put("ok", r.exitCode == 0).put("via", "shell:${r.via}")
            .put("enabled", enable).put("exit_code", r.exitCode)
            .put("stderr", r.stderr.take(200))
    }

    private fun addNetwork(wifi: WifiManager, args: JSONObject): JSONObject {
        val ssid = args.optString("ssid", "")
        if (ssid.isBlank()) return err("add requires ssid")
        val psk = args.optString("psk", args.optString("password", ""))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val builderClass = Class.forName("android.net.wifi.WifiNetworkSuggestion\$Builder")
                val builder = builderClass.getConstructor().newInstance()
                builderClass.getMethod("setSsid", String::class.java).invoke(builder, ssid)
                if (psk.isNotBlank()) {
                    builderClass.getMethod("setWpa2Passphrase", String::class.java).invoke(builder, psk)
                }
                val suggestion = builderClass.getMethod("build").invoke(builder)
                val list = arrayListOf(suggestion)
                val status = wifi.javaClass
                    .getMethod("addNetworkSuggestions", List::class.java)
                    .invoke(wifi, list) as Int
                JSONObject().put("ok", status == 0).put("via", "WifiNetworkSuggestion")
                    .put("ssid", ssid).put("status", status)
                    .put("note", "User may be asked to approve the suggested network.")
            } catch (e: Exception) {
                err("add via suggestion failed: ${e.message}")
            }
        }
        // Pre-Q legacy path.
        return try {
            @Suppress("DEPRECATION")
            val cfg = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (psk.isBlank()) {
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$psk\""
                }
            }
            @Suppress("DEPRECATION")
            val netId = wifi.addNetwork(cfg)
            JSONObject().put("ok", netId != -1).put("via", "legacy").put("ssid", ssid).put("net_id", netId)
        } catch (e: Exception) {
            err("legacy addNetwork failed: ${e.message}")
        }
    }

    private fun removeNetwork(wifi: WifiManager, args: JSONObject): JSONObject {
        val ssid = args.optString("ssid", "")
        if (ssid.isBlank()) return err("remove requires ssid")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Removing suggestions requires re-building the same suggestion object; simplest honest
            // path is to clear this app's suggestions.
            return try {
                val cleared = wifi.javaClass
                    .getMethod("removeNetworkSuggestions", List::class.java)
                    .invoke(wifi, arrayListOf<Any>()) as Int
                JSONObject().put("ok", cleared == 0).put("via", "WifiNetworkSuggestion")
                    .put("note", "Cleared this app's network suggestions.").put("status", cleared)
            } catch (e: Exception) {
                err("remove suggestions failed: ${e.message}")
            }
        }
        return try {
            @Suppress("DEPRECATION")
            val net = wifi.configuredNetworks?.firstOrNull { it.SSID == "\"$ssid\"" }
                ?: return err("ssid not found in configured networks")
            @Suppress("DEPRECATION")
            val ok = wifi.removeNetwork(net.networkId)
            JSONObject().put("ok", ok).put("via", "legacy").put("ssid", ssid)
        } catch (e: Exception) {
            err("legacy removeNetwork failed: ${e.message}")
        }
    }

    private fun roaming(args: JSONObject): JSONObject {
        // `cmd wifi` sub-commands vary by OS version; run only an allow-listed, reversible set.
        val setting = args.optString("setting", "").lowercase()
        val value = args.optString("value", "")
        val cmd = when (setting) {
            "scan_always" -> "cmd wifi set-scan-always-available ${if (value == "true") "enabled" else "disabled"}"
            "verbose" -> "cmd wifi set-verbose-logging ${if (value == "true") "enabled" else "disabled"}"
            "status", "" -> "cmd wifi status"
            else -> return err("roaming setting must be scan_always|verbose|status")
        }
        val r = ShellExecutor.execSync(cmd, 10L)
        return JSONObject().put("ok", r.exitCode == 0).put("via", "shell:${r.via}")
            .put("cmd", cmd).put("stdout", r.stdout.take(600)).put("exit_code", r.exitCode)
            .put("note", "Uses shell `cmd wifi` (no Zebra license). Availability varies by OS build.")
    }

    // ---------------- zebra_datawedge_profile ----------------

    /** DataWedge profile CRUD (no license). ops: list|create|set_config|switch|active. */
    fun datawedgeProfile(context: Context, args: JSONObject): JSONObject {
        val name = args.optString("profile", args.optString("name", "ShizukuAiBroker"))
        return when (args.optString("op", "list").lowercase()) {
            "list" -> DataWedgeApi.getProfilesList(context)
            "active" -> DataWedgeApi.getActiveProfile(context)
            "create" -> DataWedgeApi.createProfile(context, name)
            "set_config" -> DataWedgeApi.setMinimalBroadcastProfile(context, name)
            "switch" -> DataWedgeApi.switchToProfile(context, name)
            else -> err("op must be list|active|create|set_config|switch")
        }
    }

    // ---------------- zebra_scanner_control ----------------

    /** Enable/disable the scanner input and fire soft scans via DataWedge (no license). */
    fun scannerControl(context: Context, args: JSONObject): JSONObject {
        return when (args.optString("op", "status").lowercase()) {
            "status" -> DataWedgeApi.getStatus(context)
            "enable" -> setScannerInput(context, args, true)
            "disable" -> setScannerInput(context, args, false)
            "soft_scan_start" -> DataWedgeApi.softScan(context, "start")
            "soft_scan_stop" -> DataWedgeApi.softScan(context, "stop")
            else -> err("op must be status|enable|disable|soft_scan_start|soft_scan_stop")
        }
    }

    private fun setScannerInput(context: Context, args: JSONObject, enabled: Boolean): JSONObject {
        val profile = args.optString("profile", "ShizukuAiBroker")
        return try {
            val barcodeProps = android.os.Bundle().apply {
                putString("scanner_input_enabled", if (enabled) "true" else "false")
                putString("scanner_selection", "auto")
            }
            val barcodeConfig = android.os.Bundle().apply {
                putString("PLUGIN_NAME", "BARCODE")
                putString("RESET_CONFIG", "false")
                putBundle("PARAM_LIST", barcodeProps)
            }
            val profileConfig = android.os.Bundle().apply {
                putString("PROFILE_NAME", profile)
                putString("PROFILE_ENABLED", "true")
                putString("CONFIG_MODE", "UPDATE")
                putParcelableArray("PLUGIN_CONFIG", arrayOf(barcodeConfig))
            }
            DataWedgeApi.send(context, DataWedgeApi.EXTRA_SET_CONFIG, profileConfig)
                .put("scanner_input_enabled", enabled).put("profile", profile)
        } catch (e: Exception) {
            err("scanner_control failed: ${e.message}")
        }
    }

    /** Human-readable list of the paid-license features this layer deliberately does not touch. */
    fun omittedLicensedFeatures(): JSONArray = JSONArray(
        listOf(
            "Fusion Advanced / enterprise Wi-Fi tiers (advanced roaming 802.11r/k/v tuning, band lock, advanced PMF/CCKM)",
            "Zebra Enterprise Browser",
            "Enterprise Keyboard premium features",
            "OEMConfig premium / advanced MX feature tiers",
            "Premium StageNow profiles",
            "Any Mobility DNA feature gated behind a server-validated license key"
        )
    )

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)
}
