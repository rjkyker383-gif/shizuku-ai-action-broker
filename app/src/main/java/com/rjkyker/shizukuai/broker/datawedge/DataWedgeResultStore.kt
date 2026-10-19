package com.rjkyker.shizukuai.broker.datawedge

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Persists DataWedge RESULT_ACTION payloads so other processes can read real answers.
 *
 * Files written (both locations, same content):
 *  - internal:  /data/data/<pkg>/files/datawedge/
 *  - external:  /sdcard/Android/data/<pkg>/files/datawedge/   (readable by `adb shell`)
 *
 *  status.json, version.json, profiles.json, active_profile.json,
 *  last_result.json, last_scan.json, last_query.json, results.jsonl (rolling, ~200 lines)
 *
 * Also lets callers block until a reply with a given COMMAND_IDENTIFIER arrives.
 */
object DataWedgeResultStore {

    private const val TAG = "DataWedgeResultStore"
    private const val DIR = "datawedge"
    private const val MAX_LINES = 200

    private val waiters = ConcurrentHashMap<String, Pair<CountDownLatch, Array<JSONObject?>>>()
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    fun dirs(context: Context): List<File> {
        val out = mutableListOf<File>()
        try { out += File(context.filesDir, DIR) } catch (_: Exception) {}
        try { context.getExternalFilesDir(DIR)?.let { out += it } } catch (_: Exception) {}
        out.forEach { it.mkdirs() }
        return out
    }

    fun externalPath(context: Context): String? =
        try { context.getExternalFilesDir(DIR)?.absolutePath } catch (_: Exception) { null }

    /** Returns false if this payload was already recorded (manifest + dynamic receiver both fire). */
    @Synchronized
    fun record(context: Context, json: JSONObject): Boolean {
        val key = json.optString("command_identifier", "") + "|" + json.optString("command", "") +
            "|" + json.optString("kind", "") + "|" + json.optString("data_string", "")
        val dedupe = json.optString("command_identifier", "").isNotEmpty() || json.optString("type") == "scan"
        if (dedupe) {
            val k = key + "|" + (json.optLong("ts") / 2000)
            if (!seen.add(k)) return false
            if (seen.size > 256) { val it = seen.iterator(); it.next(); it.remove() }
        }
        val kind = json.optString("kind", "")
        val text = json.toString(2)
        for (d in dirs(context)) {
            try {
                if (json.optString("type") == "scan") {
                    File(d, "last_scan.json").writeText(text)
                } else {
                    File(d, "last_result.json").writeText(text)
                    if (kind.isNotEmpty()) File(d, "$kind.json").writeText(text)
                }
                appendLine(File(d, "results.jsonl"), json.toString())
            } catch (e: Exception) {
                Log.w(TAG, "write failed in $d: ${e.message}")
            }
        }
        // GET_* query replies do not echo COMMAND_IDENTIFIER, so waiters are keyed by kind
        // (and by command id when DataWedge does echo it, e.g. SET_CONFIG results).
        json.optString("command_identifier", "").takeIf { it.isNotEmpty() }?.let { id ->
            waiters[id]?.let { (latch, slot) -> slot[0] = json; latch.countDown() }
        }
        if (kind.isNotEmpty()) {
            waiters["kind:$kind"]?.let { (latch, slot) -> if (slot[0] == null) { slot[0] = json; latch.countDown() } }
        }
        return true
    }

    fun writeQuery(context: Context, json: JSONObject) {
        for (d in dirs(context)) {
            try { File(d, "last_query.json").writeText(json.toString(2)) } catch (_: Exception) {}
        }
    }

    fun register(commandId: String): Pair<CountDownLatch, Array<JSONObject?>> {
        val entry = CountDownLatch(1) to arrayOfNulls<JSONObject>(1)
        waiters[commandId] = entry
        return entry
    }

    fun await(commandId: String, entry: Pair<CountDownLatch, Array<JSONObject?>>, timeoutMs: Long): JSONObject? {
        return try {
            entry.first.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            entry.second[0]
        } finally {
            waiters.remove(commandId)
        }
    }

    fun readAll(context: Context): JSONObject {
        val o = JSONObject()
        val d = dirs(context).firstOrNull() ?: return o
        for (name in listOf("status", "version", "profiles", "active_profile", "last_result", "last_scan", "last_query")) {
            val f = File(d, "$name.json")
            if (f.exists()) {
                try { o.put(name, JSONObject(f.readText())) } catch (_: Exception) {}
            }
        }
        externalPath(context)?.let { o.put("external_dir", it) }
        return o
    }

    private fun appendLine(f: File, line: String) {
        f.appendText(line + "\n")
        if (f.length() > 256 * 1024) {
            val lines = f.readLines()
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }
}
