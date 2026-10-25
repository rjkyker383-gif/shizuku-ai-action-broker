package com.rjkyker.shizukuai.broker.datawedge

import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-tap "Repair DataWedge profile".
 *
 * Why scans weren't landing: DataWedge only uses an app's profile while THAT app is in the foreground;
 * in every other app the default "Profile0" is active and only types keystrokes, so the broker never
 * got an intent. And the broker profile itself was only created when an AI called set_config.
 *
 * Repair plan (all documented DataWedge Intent API; free symbologies only; no licence anything):
 *  1. ENABLE_DATAWEDGE true
 *  2. CREATE_PROFILE "ShizukuAiBroker" + SET_CONFIG(UPDATE): bound to this package, BARCODE (free decoders,
 *     AIM code IDs on), INTENT output → broadcast [DataWedgeApi.SCAN_ACTION] / category DEFAULT,
 *     BDF disabled (raw data incl. control chars), KEYSTROKE off.
 *  3. Profile0 SET_CONFIG(UPDATE): INTENT output ALSO on (same action) with RESET_CONFIG=false — keystroke
 *     typing into other apps is left exactly as it was; the broker simply also receives each scan.
 *
 * [plan] is pure JSON (unit-tested); [apply] converts it to Bundles and sends it.
 */
object DataWedgeRepair {
    private const val TAG = "DataWedgeRepair"
    const val PROFILE = "ShizukuAiBroker"
    const val DEFAULT_PROFILE = "Profile0 (default)"
    const val EXTRA_ENABLE = "com.symbol.datawedge.api.ENABLE_DATAWEDGE"

    val FREE_DECODERS = listOf(
        "decoder_code128", "decoder_code39", "decoder_code93", "decoder_ean13", "decoder_ean8",
        "decoder_upca", "decoder_upce0", "decoder_upce1", "decoder_i2of5", "decoder_d2of5",
        "decoder_codabar", "decoder_pdf417", "decoder_qrcode", "decoder_datamatrix", "decoder_aztec",
        "decoder_gs1_databar", "decoder_gs1_databar_lim", "decoder_gs1_databar_exp",
        "decoder_microqr", "decoder_micropdf", "decoder_maxicode", "decoder_msi"
    )

    fun intentParams(): JSONObject = JSONObject()
        .put("intent_output_enabled", "true")
        .put("intent_action", DataWedgeApi.SCAN_ACTION)
        .put("intent_category", "android.intent.category.DEFAULT")
        .put("intent_delivery", "2") // 2 = broadcast

    /** Pure, testable description of every DataWedge command the repair sends, in order. */
    fun plan(packageName: String, includeDefaultProfile: Boolean = true): JSONArray {
        val steps = JSONArray()
        steps.put(JSONObject().put("extra", EXTRA_ENABLE).put("value", true))
        steps.put(JSONObject().put("extra", DataWedgeApi.EXTRA_CREATE_PROFILE).put("value", PROFILE))
        val barcode = JSONObject().put("scanner_selection", "auto").put("scanner_input_enabled", "true")
            .put("code_id_type", "2") // AIM identifiers (free)
        for (d in FREE_DECODERS) barcode.put(d, "true")
        steps.put(JSONObject().put("extra", DataWedgeApi.EXTRA_SET_CONFIG).put("value", JSONObject()
            .put("PROFILE_NAME", PROFILE)
            .put("PROFILE_ENABLED", "true")
            .put("CONFIG_MODE", "UPDATE")
            .put("APP_LIST", JSONArray().put(JSONObject().put("PACKAGE_NAME", packageName).put("ACTIVITY_LIST", JSONArray().put("*"))))
            .put("PLUGIN_CONFIG", JSONArray()
                .put(JSONObject().put("PLUGIN_NAME", "BARCODE").put("RESET_CONFIG", "true").put("PARAM_LIST", barcode))
                .put(JSONObject().put("PLUGIN_NAME", "INTENT").put("RESET_CONFIG", "true").put("PARAM_LIST", intentParams()))
                .put(JSONObject().put("PLUGIN_NAME", "BDF").put("OUTPUT_PLUGIN_NAME", "INTENT").put("RESET_CONFIG", "true")
                    .put("PARAM_LIST", JSONObject().put("bdf_enabled", "false")))
                .put(JSONObject().put("PLUGIN_NAME", "KEYSTROKE").put("RESET_CONFIG", "false")
                    .put("PARAM_LIST", JSONObject().put("keystroke_output_enabled", "false"))))))
        if (includeDefaultProfile) {
            steps.put(JSONObject().put("extra", DataWedgeApi.EXTRA_SET_CONFIG).put("value", JSONObject()
                .put("PROFILE_NAME", DEFAULT_PROFILE)
                .put("PROFILE_ENABLED", "true")
                .put("CONFIG_MODE", "UPDATE")
                .put("PLUGIN_CONFIG", JSONArray()
                    .put(JSONObject().put("PLUGIN_NAME", "INTENT").put("RESET_CONFIG", "false").put("PARAM_LIST", intentParams())))))
        }
        return steps
    }

    /** JSON → Bundle (strings, booleans, nested objects, arrays of objects → Parcelable[], arrays of strings → String[]). */
    fun toBundle(o: JSONObject): Bundle {
        val b = Bundle()
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                is JSONObject -> b.putBundle(k, toBundle(v))
                is JSONArray -> {
                    if (v.length() > 0 && v.opt(0) is JSONObject) {
                        b.putParcelableArray(k, Array(v.length()) { toBundle(v.getJSONObject(it)) })
                    } else {
                        b.putStringArray(k, Array(v.length()) { v.optString(it) })
                    }
                }
                is Boolean -> b.putBoolean(k, v)
                else -> b.putString(k, v.toString())
            }
        }
        return b
    }

    fun apply(context: Context, includeDefaultProfile: Boolean = true): JSONObject {
        val steps = plan(context.packageName, includeDefaultProfile)
        val results = JSONArray()
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val value: Any = when (val v = s.get("value")) { is JSONObject -> toBundle(v); else -> v }
            val r = DataWedgeApi.send(context, s.getString("extra"), value, commandId = "broker-repair-$i-${System.currentTimeMillis()}")
            results.put(JSONObject().put("step", i).put("extra", s.getString("extra").substringAfterLast('.'))
                .put("profile", (s.opt("value") as? JSONObject)?.optString("PROFILE_NAME") ?: s.opt("value").toString())
                .put("sent", r.optBoolean("sent")))
        }
        DataWedgeResultReceiver.ensureRegistered(context.applicationContext)
        Log.i(TAG, "repair sent ${steps.length()} DataWedge commands (default profile=$includeDefaultProfile)")
        return JSONObject().put("ok", true).put("steps", results)
            .put("scan_action", DataWedgeApi.SCAN_ACTION)
            .put("note", "Results arrive asynchronously as RESULT_ACTION; scan anything to verify.")
    }
}
