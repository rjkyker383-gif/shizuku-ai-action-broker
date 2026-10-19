package com.rjkyker.shizukuai.broker.datawedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Shell-friendly trigger for read-only DataWedge queries. Example (adb / rish):
 *
 *   am broadcast -n com.rjkyker.shizukuai.broker/.datawedge.DataWedgeQueryReceiver \
 *      -a com.rjkyker.shizukuai.broker.DW_QUERY --es query all
 *   cat /sdcard/Android/data/com.rjkyker.shizukuai.broker/files/datawedge/last_query.json
 *
 * The broker (not the shell) sends the DataWedge command, so DataWedge's RESULT_ACTION
 * reply comes back to this app, which writes it to files the shell can read.
 * Only GET_* queries are reachable from here (no config changes).
 */
class DataWedgeQueryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val query = intent?.getStringExtra("query") ?: "all"
        val timeout = (intent?.getStringExtra("timeout_ms")?.toLongOrNull() ?: 6000L).coerceIn(500L, 8000L)
        val pending = goAsync()
        Thread({
            try {
                val r = DataWedgeQuery.run(context.applicationContext, query, timeout)
                Log.i(TAG, "DW_QUERY $query -> $r")
                pending.resultData = r.toString()
                pending.resultCode = if (r.optBoolean("datawedge_replied")) 1 else 0
            } catch (e: Exception) {
                Log.w(TAG, "DW_QUERY failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }, "DwQuery").start()
    }

    companion object {
        private const val TAG = "DataWedgeQueryRx"
        const val ACTION = "com.rjkyker.shizukuai.broker.DW_QUERY"
    }
}
