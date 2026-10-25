package com.rjkyker.shizukuai.broker.datawedge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory + on-disk rolling history of enriched scans (newest first, max [MAX]).
 * Files: filesDir/datawedge/scans.jsonl and external twin (adb-readable).
 */
object ScanHistoryStore {
    private const val TAG = "ScanHistoryStore"
    const val MAX = 200
    private val scans = CopyOnWriteArrayList<JSONObject>()
    @Volatile private var loaded = false
    /** Bumped on every add/clear so the UI can skip re-rendering an unchanged scan panel. */
    val version = java.util.concurrent.atomic.AtomicInteger(0)

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(File(context.filesDir, "datawedge"), "scans.jsonl")
            if (!f.exists()) return
            val lines = f.readLines().takeLast(MAX)
            val tmp = ArrayList<JSONObject>(lines.size)
            for (ln in lines) {
                try { tmp.add(JSONObject(ln)) } catch (_: Exception) {}
            }
            scans.clear()
            // file is oldest→newest; we want newest first in memory
            scans.addAll(tmp.reversed())
            version.incrementAndGet()
        } catch (e: Exception) {
            Log.w(TAG, "load: ${e.message}")
        }
    }

    @Synchronized
    fun add(context: Context, scan: JSONObject) {
        ensureLoaded(context)
        scans.add(0, scan)
        while (scans.size > MAX) scans.removeAt(scans.size - 1)
        version.incrementAndGet()
        persist(context, scan)
    }

    fun latest(limit: Int = 50): JSONArray {
        val n = limit.coerceIn(1, MAX)
        val arr = JSONArray()
        for (i in 0 until minOf(n, scans.size)) arr.put(scans[i])
        return arr
    }

    fun snapshot(limit: Int = 50): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("count", scans.size)
            .put("limit", limit.coerceIn(1, MAX))
            .put("scans", latest(limit))

    fun clear(context: Context) {
        scans.clear()
        version.incrementAndGet()
        for (d in DataWedgeResultStore.dirs(context)) {
            try { File(d, "scans.jsonl").writeText("") } catch (_: Exception) {}
        }
    }

    private fun persist(context: Context, scan: JSONObject) {
        val line = scan.toString()
        for (d in DataWedgeResultStore.dirs(context)) {
            try {
                val f = File(d, "scans.jsonl")
                f.appendText(line + "\n")
                if (f.length() > 512 * 1024) {
                    val kept = f.readLines().takeLast(MAX)
                    f.writeText(kept.joinToString("\n", postfix = "\n"))
                }
                File(d, "last_scan.json").writeText(scan.toString(2))
            } catch (e: Exception) {
                Log.w(TAG, "persist $d: ${e.message}")
            }
        }
    }
}
