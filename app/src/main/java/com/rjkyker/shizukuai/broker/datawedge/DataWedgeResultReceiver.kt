package com.rjkyker.shizukuai.broker.datawedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Receives DataWedge RESULT_ACTION replies and scan notify broadcasts.
 *
 * Registered two ways:
 *  - in the manifest (explicit / package-targeted deliveries, cold start), and
 *  - dynamically via [ensureRegistered] (Android 8+ drops implicit broadcasts to
 *    manifest receivers, and DataWedge's RESULT_ACTION is implicit on many builds).
 * Duplicates are filtered by [DataWedgeResultStore.record].
 *
 * Parsed replies are persisted by [DataWedgeResultStore] (JSON files readable via adb).
 */
class DataWedgeResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        try {
            when (action) {
                DataWedgeApi.RESULT_ACTION -> handleResult(context, intent)
                DataWedgeApi.SCAN_ACTION -> handleScan(context, intent)
                else -> {
                    if (intent.hasExtra(EXTRA_DATA_STRING) || intent.hasExtra(EXTRA_DATA_STRING_ALT)) {
                        handleScan(context, intent)
                    } else {
                        Log.d(TAG, "ignored action=$action")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onReceive soft-fail: ${e.message}")
        }
    }

    private fun handleResult(context: Context, intent: Intent) {
        val json = parseResult(intent)
        if (!DataWedgeResultStore.record(context.applicationContext, json)) return
        lastEventJson.set(json)
        lastStatusString.set(summaryLine(json))
        Log.i(TAG, "RESULT_ACTION: $json")
    }

    private fun handleScan(context: Context, intent: Intent) {
        val data = intent.getStringExtra(EXTRA_DATA_STRING)
            ?: intent.getStringExtra(EXTRA_DATA_STRING_ALT) ?: ""
        val label = intent.getStringExtra(EXTRA_LABEL_TYPE)
            ?: intent.getStringExtra("com.symbol.datawedge.label_type") ?: ""
        val source = intent.getStringExtra(EXTRA_SOURCE)
            ?: intent.getStringExtra(EXTRA_SOURCE_ALT) ?: ""
        val aim = intent.getStringExtra(EXTRA_AIM_ID)
            ?: intent.getStringExtra("com.symbol.datawedge.aim_id")
        val decodedMode = intent.getStringExtra(EXTRA_DECODED_MODE)
            ?: intent.getStringExtra("com.symbol.datawedge.decoded_mode")
        val segments = ArrayList<ByteArray>()
        // Primary decode_data byte[] array-list / byte[][]
        @Suppress("DEPRECATION")
        val decodeData = intent.extras?.get("com.symbol.datawedge.decode_data")
        when (decodeData) {
            is Array<*> -> decodeData.forEach { v ->
                when (v) {
                    is ByteArray -> segments.add(v)
                    is Array<*> -> v.filterIsInstance<ByteArray>().forEach { segments.add(it) }
                }
            }
            is ByteArray -> segments.add(decodeData)
            is ArrayList<*> -> decodeData.forEach { v -> if (v is ByteArray) segments.add(v) }
        }
        // Also accept a single byte[] under decode_data[0]-style extras some DW builds use
        intent.getByteArrayExtra("com.symbol.datawedge.decode_data")?.let {
            if (segments.isEmpty()) segments.add(it)
        }
        val json = ScanParsers.enrich(
            dataString = data,
            labelType = label,
            source = source,
            decodeSegments = segments,
            aimId = aim,
            decodedMode = decodedMode,
        ).put("action", intent.action)
        if (!DataWedgeResultStore.record(context.applicationContext, json)) return
        ScanHistoryStore.add(context.applicationContext, json)
        lastEventJson.set(json)
        lastScanJson.set(json)
        lastStatusString.set("scan: $label | ${ScanParsers.renderVisible(data).take(80)}")
        Log.i(TAG, "SCAN: label=$label len=${data.length} segs=${segments.size} source=$source")
    }

    companion object {
        private const val TAG = "DataWedgeResultRx"

        const val EXTRA_DATA_STRING = "com.symbol.datawedge.data_string"
        const val EXTRA_LABEL_TYPE = "com.symbol.datawedge.label_type"
        const val EXTRA_SOURCE = "com.symbol.datawedge.source"
        const val EXTRA_DATA_STRING_ALT = "com.symbol.datawedge.data"
        const val EXTRA_SOURCE_ALT = "com.symbol.datawedge.source_uri"
        const val EXTRA_AIM_ID = "com.symbol.datawedge.aimid"
        const val EXTRA_DECODED_MODE = "com.symbol.datawedge.decoded_mode"
        const val EXTRA_DECODE_DATA = "com.symbol.datawedge.decode_data"

        private const val P = "com.symbol.datawedge.api."
        /** RESULT_* extra -> short kind used for file names / query keys. */
        val RESULT_KINDS = linkedMapOf(
            P + "RESULT_GET_DATAWEDGE_STATUS" to "status",
            P + "RESULT_GET_VERSION_INFO" to "version",
            P + "RESULT_GET_PROFILES_LIST" to "profiles",
            P + "RESULT_GET_ACTIVE_PROFILE" to "active_profile",
        )

        val lastEventJson = AtomicReference<JSONObject?>(null)
        val lastScanJson = AtomicReference<JSONObject?>(null)
        val lastStatusString = AtomicReference("DataWedge: no events yet")

        private val registered = AtomicBoolean(false)
        private var dynamicReceiver: DataWedgeResultReceiver? = null

        /** Register a runtime receiver for RESULT_ACTION + scan action (idempotent). */
        fun ensureRegistered(appContext: Context) {
            if (!registered.compareAndSet(false, true)) return
            try {
                val filter = IntentFilter().apply {
                    addAction(DataWedgeApi.RESULT_ACTION)
                    addAction(DataWedgeApi.SCAN_ACTION)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                val r = DataWedgeResultReceiver()
                ContextCompat.registerReceiver(
                    appContext.applicationContext, r, filter, ContextCompat.RECEIVER_EXPORTED
                )
                dynamicReceiver = r
                Log.i(TAG, "dynamic RESULT_ACTION receiver registered")
            } catch (e: Exception) {
                registered.set(false)
                Log.w(TAG, "dynamic register failed: ${e.message}")
            }
        }

        fun parseResult(intent: Intent): JSONObject {
            val json = JSONObject()
                .put("type", "result")
                .put("action", DataWedgeApi.RESULT_ACTION)
                .put("ts", System.currentTimeMillis())
            intent.getStringExtra("COMMAND")?.let { json.put("command", it) }
            intent.getStringExtra("COMMAND_IDENTIFIER")?.let { json.put("command_identifier", it) }
            intent.getStringExtra("RESULT")?.let { json.put("result", it) }
            intent.getStringExtra("PROFILE_NAME")?.let { json.put("profile_name", it) }
            intent.getBundleExtra("RESULT_INFO")?.let { json.put("result_info", bundleToJson(it)) }

            val extras = intent.extras
            for ((key, kind) in RESULT_KINDS) {
                if (extras == null || !extras.containsKey(key)) continue
                @Suppress("DEPRECATION")
                val v = extras.get(key)
                json.put("kind", kind)
                json.put("value", toJsonValue(v))
            }
            if (!json.has("kind")) {
                // Map by echoed command if DataWedge only sent RESULT/RESULT_INFO
                val cmd = json.optString("command")
                RESULT_KINDS.values.firstOrNull { k ->
                    cmd.endsWith(DataWedgeQuery.KINDS[k]?.substringAfterLast('.') ?: "#")
                }?.let { json.put("kind", it) }
            }
            if (extras != null) {
                val dump = JSONObject()
                for (key in extras.keySet()) {
                    if (key in RESULT_KINDS || key in setOf("COMMAND", "COMMAND_IDENTIFIER", "RESULT", "RESULT_INFO", "PROFILE_NAME")) continue
                    @Suppress("DEPRECATION")
                    dump.put(key.removePrefix(P), toJsonValue(extras.get(key)))
                }
                if (dump.length() > 0) json.put("extras", dump)
            }
            return json
        }

        fun summaryLine(json: JSONObject): String {
            val kind = json.optString("kind", json.optString("command", "result"))
            val v = json.opt("value")
            val s = when (v) {
                is JSONArray -> "${v.length()} item(s): " + (0 until minOf(v.length(), 5)).joinToString { v.optString(it) }
                is JSONObject -> v.toString().take(120)
                null -> json.optString("result", "")
                else -> v.toString()
            }
            return "DataWedge $kind: $s"
        }

        fun snapshot(): JSONObject = JSONObject()
            .put("ok", true)
            .put("last_status", lastStatusString.get())
            .put("last_event", lastEventJson.get())
            .put("last_scan", lastScanJson.get())

        private fun toJsonValue(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Bundle -> bundleToJson(v)
            is Array<*> -> JSONArray(v.map { toJsonValue(it) })
            is Collection<*> -> JSONArray(v.map { toJsonValue(it) })
            is Boolean, is Number -> v
            else -> v.toString()
        }

        private fun bundleToJson(bundle: Bundle): JSONObject {
            val o = JSONObject()
            for (key in bundle.keySet()) {
                @Suppress("DEPRECATION")
                o.put(key, toJsonValue(bundle.get(key)))
            }
            return o
        }
    }
}
