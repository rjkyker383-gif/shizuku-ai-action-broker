package com.rjkyker.shizukuai.broker.datawedge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.json.JSONObject

/**
 * Zebra / Symbol DataWedge Intent API helpers.
 *
 * Soft-fails on non-Zebra devices (broadcast still sent; no crash if DW absent).
 * Results arrive asynchronously via [DataWedgeResultReceiver] (RESULT_ACTION).
 *
 * Reference:
 * ```
 * Intent i = new Intent();
 * i.setAction("com.symbol.datawedge.api.ACTION");
 * i.putExtra("com.symbol.datawedge.api.GET_DATAWEDGE_STATUS", "");
 * ```
 */
object DataWedgeApi {

    const val ACTION = "com.symbol.datawedge.api.ACTION"
    const val RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION"

    const val EXTRA_GET_DATAWEDGE_STATUS = "com.symbol.datawedge.api.GET_DATAWEDGE_STATUS"
    const val EXTRA_GET_VERSION_INFO = "com.symbol.datawedge.api.GET_VERSION_INFO"
    const val EXTRA_GET_PROFILES_LIST = "com.symbol.datawedge.api.GET_PROFILES_LIST"
    const val EXTRA_GET_ACTIVE_PROFILE = "com.symbol.datawedge.api.GET_ACTIVE_PROFILE"
    const val EXTRA_SOFT_SCAN_TRIGGER = "com.symbol.datawedge.api.SOFT_SCAN_TRIGGER"
    const val EXTRA_SWITCH_TO_PROFILE = "com.symbol.datawedge.api.SWITCH_TO_PROFILE"
    const val EXTRA_CREATE_PROFILE = "com.symbol.datawedge.api.CREATE_PROFILE"
    const val EXTRA_SET_CONFIG = "com.symbol.datawedge.api.SET_CONFIG"
    const val EXTRA_SEND_RESULT = "SEND_RESULT"
    const val EXTRA_COMMAND_IDENTIFIER = "COMMAND_IDENTIFIER"

    const val SOFT_SCAN_START = "START_SCANNING"
    const val SOFT_SCAN_STOP = "STOP_SCANNING"

    /** Default scan notify action for broker profile (configure in DataWedge Intent output). */
    const val SCAN_ACTION = "com.rjkyker.shizukuai.broker.SCAN"

    private const val TAG = "DataWedgeApi"
    private const val DEFAULT_PROFILE = "ShizukuAiBroker"

    fun buildActionIntent(extraKey: String, extraValue: Any? = ""): Intent {
        val i = Intent().setAction(ACTION)
        when (extraValue) {
            is Bundle -> i.putExtra(extraKey, extraValue)
            is Boolean -> i.putExtra(extraKey, extraValue)
            is Int -> i.putExtra(extraKey, extraValue)
            null -> i.putExtra(extraKey, "")
            else -> i.putExtra(extraKey, extraValue.toString())
        }
        return i
    }

    /**
     * Send a DataWedge API broadcast. Soft-fail: never throws to callers.
     * Uses sendBroadcast by default; [ordered]=true uses sendOrderedBroadcast.
     */
    fun send(
        context: Context,
        extraKey: String,
        extraValue: Any? = "",
        sendResult: Boolean = true,
        commandId: String? = null,
        ordered: Boolean = false,
    ): JSONObject {
        return try {
            val intent = buildActionIntent(extraKey, extraValue)
            if (sendResult) {
                intent.putExtra(EXTRA_SEND_RESULT, "LAST_RESULT")
                intent.putExtra(
                    EXTRA_COMMAND_IDENTIFIER,
                    commandId ?: "broker-${System.currentTimeMillis()}"
                )
            }
            if (ordered) {
                context.applicationContext.sendOrderedBroadcast(intent, null)
            } else {
                context.applicationContext.sendBroadcast(intent)
            }
            Log.i(TAG, "sent $extraKey value=${summarize(extraValue)}")
            JSONObject()
                .put("ok", true)
                .put("sent", true)
                .put("action", ACTION)
                .put("extra", extraKey)
                .put("note", "DataWedge required (Zebra/Symbol). Soft-fail if not installed.")
        } catch (e: Exception) {
            Log.w(TAG, "send failed soft: ${e.message}")
            JSONObject()
                .put("ok", false)
                .put("sent", false)
                .put("error", e.message ?: "send failed")
                .put("note", "DataWedge may be absent on non-Zebra devices")
        }
    }

    fun getStatus(context: Context): JSONObject =
        send(context, EXTRA_GET_DATAWEDGE_STATUS, "")

    fun getVersionInfo(context: Context): JSONObject =
        send(context, EXTRA_GET_VERSION_INFO, "")

    fun getProfilesList(context: Context): JSONObject =
        send(context, EXTRA_GET_PROFILES_LIST, "")

    fun getActiveProfile(context: Context): JSONObject =
        send(context, EXTRA_GET_ACTIVE_PROFILE, "")

    fun softScan(context: Context, mode: String): JSONObject {
        val trigger = when (mode.trim().lowercase()) {
            "start", "start_scanning", SOFT_SCAN_START.lowercase() -> SOFT_SCAN_START
            "stop", "stop_scanning", SOFT_SCAN_STOP.lowercase() -> SOFT_SCAN_STOP
            else -> return JSONObject()
                .put("ok", false)
                .put("error", "mode must be start|stop")
        }
        return send(context, EXTRA_SOFT_SCAN_TRIGGER, trigger)
    }

    fun switchToProfile(context: Context, profileName: String): JSONObject {
        if (profileName.isBlank()) {
            return JSONObject().put("ok", false).put("error", "profile required")
        }
        return send(context, EXTRA_SWITCH_TO_PROFILE, profileName)
    }

    fun createProfile(context: Context, profileName: String = DEFAULT_PROFILE): JSONObject {
        if (profileName.isBlank()) {
            return JSONObject().put("ok", false).put("error", "profile required")
        }
        return send(context, EXTRA_CREATE_PROFILE, profileName)
    }

    /**
     * Minimal SET_CONFIG: enable Intent output plugin broadcasting to [SCAN_ACTION]
     * for the given profile (create profile first if needed).
     */
    fun setMinimalBroadcastProfile(
        context: Context,
        profileName: String = DEFAULT_PROFILE,
        packageName: String = context.packageName,
    ): JSONObject {
        return try {
            createProfile(context, profileName)

            val intentProps = Bundle().apply {
                putString("intent_output_enabled", "true")
                putString("intent_action", SCAN_ACTION)
                putString("intent_category", "android.intent.category.DEFAULT")
                // 0=start activity, 1=start service, 2=broadcast
                putString("intent_delivery", "2")
                // Keep raw bytes + control characters in the intent extras
                putString("intent_encode_data", "true")      // include decode_data byte arrays
                putString("intent_use_data_format", "false")  // do not strip / reformat
            }
            val intentConfig = Bundle().apply {
                putString("PLUGIN_NAME", "INTENT")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", intentProps)
            }
            // Free (no paid license) symbologies commonly available on TC22 SE4710/SE55.
            // Do NOT enable paid decoder licenses / bypass anything.
            val barcodeProps = Bundle().apply {
                putString("scanner_selection", "auto")
                putString("scanner_input_enabled", "true")
                putString("decoder_code128", "true")
                putString("decoder_code39", "true")
                putString("decoder_code93", "true")
                putString("decoder_ean13", "true")
                putString("decoder_ean8", "true")
                putString("decoder_upca", "true")
                putString("decoder_upce0", "true")
                putString("decoder_upce1", "true")
                putString("decoder_i2of5", "true")
                putString("decoder_d2of5", "true")
                putString("decoder_codabar", "true")
                putString("decoder_pdf417", "true")
                putString("decoder_qrcode", "true")
                putString("decoder_datamatrix", "true")
                putString("decoder_aztec", "true")
                putString("decoder_gs1_databar", "true")
                putString("decoder_gs1_databar_lim", "true")
                putString("decoder_gs1_databar_exp", "true")
                putString("decoder_composite_ab", "true")
                putString("decoder_composite_c", "true")
                putString("decoder_microqr", "true")
                putString("decoder_micropdf", "true")
                putString("decoder_maxi_code", "true")
                putString("decoder_trioptic39", "true")
                putString("decoder_msi", "true")
                putString("decoder_usps_4state", "true")
                putString("decoder_australian_postal", "true")
                // Keep AIM code identifier when DataWedge supports it (free)
                putString("code_id_type", "2") // AIM (1=None, 2=AIM, 3=Symbol)
                putString("aim_mode", "on")
            }
            val barcodeConfig = Bundle().apply {
                putString("PLUGIN_NAME", "BARCODE")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", barcodeProps)
            }
            // Keystroke output: keep control chars (do not strip)
            val keystrokeProps = Bundle().apply {
                putString("keystroke_output_enabled", "false") // intent is primary
            }
            val keystrokeConfig = Bundle().apply {
                putString("PLUGIN_NAME", "KEYSTROKE")
                putString("RESET_CONFIG", "false")
                putBundle("PARAM_LIST", keystrokeProps)
            }
            val appConfig = Bundle().apply {
                putString("PACKAGE_NAME", packageName)
                putStringArray("ACTIVITY_LIST", arrayOf("*"))
            }
            val profileConfig = Bundle().apply {
                putString("PROFILE_NAME", profileName)
                putString("PROFILE_ENABLED", "true")
                putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
                putParcelableArray("APP_LIST", arrayOf(appConfig))
                putParcelableArray("PLUGIN_CONFIG", arrayOf(barcodeConfig, intentConfig, keystrokeConfig))
            }
            send(context, EXTRA_SET_CONFIG, profileConfig)
                .put("profile", profileName)
                .put("scan_action", SCAN_ACTION)
        } catch (e: Exception) {
            Log.w(TAG, "setMinimalBroadcastProfile soft-fail: ${e.message}")
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: "set_config failed")
                .put("note", "DataWedge may be absent on non-Zebra devices")
        }
    }

    private fun summarize(value: Any?): String = when (value) {
        is Bundle -> "Bundle(keys=${value.keySet()})"
        null -> ""
        else -> value.toString().take(80)
    }
}
