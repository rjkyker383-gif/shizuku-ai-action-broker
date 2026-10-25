package com.rjkyker.shizukuai.broker.airtable

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Background sync of broker telemetry to Airtable.
 *
 * - [AirtableSyncWorker] is a WorkManager [Worker]: on each run it flushes the on-device queue
 *   ([QUEUE_FILE]) of pending records to Airtable.
 * - The Airtable API key is read from [SecurePrefs] (`airtable_api_key`) at runtime and is NEVER
 *   hardcoded. If no key is set the worker no-ops, logs "Airtable key not set", and leaves records
 *   queued so nothing is lost.
 * - [flushNow] is the manual flush used by the MCP tooling; [log] posts one record directly
 *   (falling back to the queue on transport failure).
 *
 * Only the two owner bases are accepted ([BASE_PRIMARY], [BASE_SECONDARY]); any other base id is
 * rejected so telemetry can never be sent somewhere unexpected.
 */
class AirtableSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val r = flushNow(applicationContext)
        return if (r.optBoolean("ok", false) || r.optString("error") == KEY_NOT_SET) {
            Result.success()
        } else {
            // Transport trouble — let WorkManager retry with backoff.
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AirtableSync"
        private const val QUEUE_FILE = "airtable_queue.jsonl"
        private const val MAX_QUEUE = 500
        private const val UNIQUE_WORK = "airtable-sync-periodic"
        const val KEY_NOT_SET = "Airtable key not set"

        const val BASE_PRIMARY = "appQkHjQwQuyzVT99"
        const val BASE_SECONDARY = "appQDYTaD6gi64g7M"
        val ALLOWED_BASES = setOf(BASE_PRIMARY, BASE_SECONDARY)

        private const val API_ROOT = "https://api.airtable.com/v0"

        /** Schedule the periodic background flush (every 30 min, requires network). Idempotent. */
        fun schedulePeriodic(context: Context) {
            try {
                val req = PeriodicWorkRequestBuilder<AirtableSyncWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    req
                )
                Log.i(TAG, "scheduled periodic Airtable sync")
            } catch (e: Exception) {
                Log.w(TAG, "schedulePeriodic failed: ${e.message}")
            }
        }

        private fun queueFile(context: Context): File = File(context.filesDir, QUEUE_FILE)

        /** Validate + normalize a base id. Returns null when not an allow-listed owner base. */
        fun normalizeBase(base: String): String? {
            val b = base.trim()
            return when {
                b in ALLOWED_BASES -> b
                b.equals("primary", true) || b.equals("main", true) -> BASE_PRIMARY
                b.equals("secondary", true) || b.equals("alt", true) -> BASE_SECONDARY
                else -> null
            }
        }

        /** Append a record to the on-device queue (survives process death; flushed by the worker). */
        @Synchronized
        fun enqueue(context: Context, base: String, table: String, fields: JSONObject): JSONObject {
            val nb = normalizeBase(base)
                ?: return err("base must be one of $ALLOWED_BASES")
            if (table.isBlank()) return err("table required")
            val rec = JSONObject()
                .put("base", nb).put("table", table)
                .put("fields", fields).put("ts", System.currentTimeMillis())
            return try {
                val f = queueFile(context)
                // Trim if the queue grew unbounded (offline for a long time).
                val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
                lines.add(rec.toString())
                while (lines.size > MAX_QUEUE) lines.removeAt(0)
                f.writeText(lines.joinToString("\n"))
                JSONObject().put("ok", true).put("queued", true)
                    .put("queue_depth", lines.size).put("base", nb).put("table", table)
            } catch (e: Exception) {
                err("enqueue failed: ${e.message}")
            }
        }

        /** Convenience enqueuers used by the broker to log telemetry. */
        fun logExecutionReceipt(context: Context, fields: JSONObject) =
            enqueue(context, BASE_PRIMARY, "ExecutionReceipts", fields)

        fun logDeviceStatus(context: Context, fields: JSONObject) =
            enqueue(context, BASE_PRIMARY, "DeviceStatus", fields)

        fun logSensorSnapshot(context: Context, fields: JSONObject) =
            enqueue(context, BASE_SECONDARY, "SensorSnapshots", fields)

        /**
         * Post one record to Airtable immediately. On a transport failure the record is queued so a
         * later worker run can retry. If no API key is set: no-op (ok=false, error=[KEY_NOT_SET]).
         */
        fun log(context: Context, base: String, table: String, fields: JSONObject): JSONObject {
            val nb = normalizeBase(base) ?: return err("base must be one of $ALLOWED_BASES")
            if (table.isBlank()) return err("table required")
            val key = SecurePrefs.getAirtableApiKey(context)
            if (key.isBlank()) {
                Log.i(TAG, KEY_NOT_SET)
                val q = enqueue(context, nb, table, fields)
                return err(KEY_NOT_SET, "Set the Airtable API key in the broker app; record was queued.")
                    .put("queued", q.optBoolean("queued", false))
            }
            val posted = postRecord(nb, table, fields, key)
            if (!posted.optBoolean("ok", false) && isTransient(posted.optString("error"))) {
                val q = enqueue(context, nb, table, fields)
                posted.put("queued", q.optBoolean("queued", false))
            }
            return posted
        }

        /** Flush every queued record. Stops early (and keeps the rest) on a transport failure. */
        @Synchronized
        fun flushNow(context: Context): JSONObject {
            val key = SecurePrefs.getAirtableApiKey(context)
            if (key.isBlank()) {
                Log.i(TAG, KEY_NOT_SET)
                val f = queueFile(context)
                val depth = if (f.exists()) f.readLines().count { it.isNotBlank() } else 0
                return err(KEY_NOT_SET).put("queue_depth", depth)
            }
            val f = queueFile(context)
            if (!f.exists()) return JSONObject().put("ok", true).put("flushed", 0).put("queue_depth", 0)
            val lines = f.readLines().filter { it.isNotBlank() }.toMutableList()
            var flushed = 0
            val errors = JSONArray()
            while (lines.isNotEmpty()) {
                val line = lines.first()
                val rec = runCatching { JSONObject(line) }.getOrNull()
                if (rec == null) { lines.removeAt(0); continue } // drop corrupt line
                val r = postRecord(
                    rec.optString("base"), rec.optString("table"),
                    rec.optJSONObject("fields") ?: JSONObject(), key
                )
                if (r.optBoolean("ok", false)) {
                    lines.removeAt(0); flushed++
                } else if (isTransient(r.optString("error"))) {
                    errors.put(r.optString("error")); break // retry later, keep remaining
                } else {
                    // Permanent (e.g. 422 bad field) — drop so it doesn't block the queue.
                    errors.put("dropped: ${r.optString("error")}"); lines.removeAt(0)
                }
            }
            f.writeText(lines.joinToString("\n"))
            return JSONObject().put("ok", true).put("flushed", flushed)
                .put("queue_depth", lines.size).put("errors", errors)
        }

        private fun postRecord(base: String, table: String, fields: JSONObject, key: String): JSONObject {
            if (base !in ALLOWED_BASES) return err("base not allow-listed")
            return try {
                val url = "$API_ROOT/$base/${encode(table)}"
                val body = JSONObject().put("fields", fields).put("typecast", true)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.use {
                        BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
                    }.orEmpty()
                    if (code in 200..299) {
                        val id = runCatching { JSONObject(text).optString("id") }.getOrDefault("")
                        JSONObject().put("ok", true).put("base", base).put("table", table)
                            .put("record_id", id).put("http", code)
                    } else {
                        err("HTTP $code: ${text.take(240)}").put("http", code)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                err("post failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /** 429 / 5xx / network issues are worth retrying; 4xx (except 429) are not. */
        private fun isTransient(error: String): Boolean {
            val e = error.lowercase()
            if (e.contains("http 429") || e.contains("http 5")) return true
            if (Regex("http 4\\d\\d").containsMatchIn(e)) return false
            return e.contains("failed") || e.contains("timeout") || e.contains("connect") ||
                e.contains("unreachable") || e.contains("unknownhost")
        }

        private fun encode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun err(msg: String, hint: String? = null): JSONObject =
            JSONObject().put("ok", false).put("error", msg).also { if (hint != null) it.put("hint", hint) }
    }
}
