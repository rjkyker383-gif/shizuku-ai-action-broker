package com.rjkyker.shizukuai.broker.ui

import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rjkyker.shizukuai.broker.autonomy.AutonomyFlags
import com.rjkyker.shizukuai.broker.autonomy.audit.AuditLog
import com.rjkyker.shizukuai.broker.autonomy.delivery.SendGate
import com.rjkyker.shizukuai.broker.autonomy.delivery.SendPolicy
import com.rjkyker.shizukuai.broker.autonomy.memory.MemoryStore
import com.rjkyker.shizukuai.broker.autonomy.queue.TaskQueue
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.PermissionEngine
import com.rjkyker.shizukuai.broker.autonomy.router.ModelRouter
import com.rjkyker.shizukuai.broker.autonomy.watchdog.Watchdog
import com.rjkyker.shizukuai.broker.databinding.ActivityMainBinding
import com.rjkyker.shizukuai.broker.llm.LlmClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 1.9.0 dashboard section: local model, autonomy flags, queue, audit, memory controls, send gate, watchdog. */
class AutonomyPanelController(
    private val act: AppCompatActivity,
    private val b: ActivityMainBinding,
    private val io: (() -> Unit) -> Unit,
) {
    private val ctx get() = act.applicationContext
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun setup() {
        b.autoWritingButton.setOnClickListener { act.startActivity(Intent(act, WritingAssistantActivity::class.java)) }
        b.autoRefreshButton.setOnClickListener { refresh() }
        b.autoToggleButton.setOnClickListener { toggleAutonomy() }
        b.autoStopAllButton.setOnClickListener {
            AutonomyFlags.stopAll(ctx)
            io { LlmClient.cancel(ctx); AuditLog.record(ctx, Actor.USER_DEVICE.name, "stop_all", "LOW_WRITE", "ALLOW", "owner STOP ALL") }
            Toast.makeText(act, "Autonomy, planner, sending and hosted AI are OFF", Toast.LENGTH_LONG).show()
            refresh()
        }
        b.autoAuditButton.setOnClickListener { io { show(AuditLog.recentJson(ctx, 40).toString(2)) } }
        b.autoMemoryButton.setOnClickListener { memoryDialog() }
        b.autoSendGateButton.setOnClickListener { sendGateDialog() }
        b.autoWatchdogButton.setOnClickListener { io { show(Watchdog.run(ctx).toString(2)) } }
    }

    fun refresh() = io {
        val q = TaskQueue(ctx)
        val sb = StringBuilder()
        sb.append("flags: ").append(AutonomyFlags.toJson(ctx)).append("\n")
        sb.append("route: ").append(ModelRouter.routeJson(ctx)).append("\n")
        sb.append("queue: ").append(q.countsJson()).append("\n")
        val runs = ModelRouter.recentRunsJson(ctx, 3)
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            sb.append("run ${fmt.format(Date(r.optLong("ts")))} ${r.optString("model")} ok=${r.optBoolean("ok")} " +
                "${"%.1f".format(r.optDouble("decode_tps"))} tok/s peak=${r.optLong("peak_rss_kb") / 1024}MB\n")
        }
        val sg = SendGate.statusJson(ctx, 3)
        sb.append("send: enabled=${sg.optBoolean("send_enabled")} apps=${sg.optInt("app_allowlist_size")} " +
            "recipients=${sg.optInt("recipient_allowlist_size")}\n")
        Watchdog.last?.let { sb.append("watchdog: ").append(Watchdog.summary(it.optJSONObject("probe")!!)).append("\n") }
        val audit = AuditLog.recentJson(ctx, 6).optJSONArray("entries")
        if (audit != null) for (i in 0 until audit.length()) {
            val a = audit.getJSONObject(i)
            sb.append("audit ${fmt.format(Date(a.optLong("ts")))} ${a.optString("actor")} ${a.optString("tool")} ${a.optString("decision")}\n")
        }
        show(sb.toString())
    }

    private fun show(s: String) = act.runOnUiThread { b.autoText.text = s.take(8000) }

    private fun toggleAutonomy() {
        val on = AutonomyFlags.get(ctx, AutonomyFlags.AUTONOMY)
        val keys = listOf(AutonomyFlags.AUTONOMY, AutonomyFlags.PLANNER, AutonomyFlags.LLM, AutonomyFlags.MEMORY, AutonomyFlags.WATCHDOG_PRIV_RESTART)
        val checked = keys.map { AutonomyFlags.get(ctx, it) }.toBooleanArray()
        AlertDialog.Builder(act).setTitle(if (on) "Autonomy is ON" else "Autonomy is OFF")
            .setMultiChoiceItems(keys.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Save") { _, _ ->
                keys.forEachIndexed { i, k -> AutonomyFlags.set(ctx, k, checked[i]) }
                io { AuditLog.record(ctx, Actor.USER_DEVICE.name, "autonomy_control", "MEDIUM", "ALLOW", "owner set flags ${AutonomyFlags.toJson(ctx)}") }
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun memoryDialog() = io {
        val items = MemoryStore.all(ctx)
        act.runOnUiThread {
            val labels = items.map { (if (it.pending == 1) "[PROPOSED by ${it.source}] " else "") + it.text.take(80) }.toMutableList()
            labels.add(0, "+ Add a note")
            labels.add(1, "Export all (shows JSON)")
            labels.add(2, "Wipe all memory")
            AlertDialog.Builder(act).setTitle("Memory (enabled=${MemoryStore.enabled(ctx)})")
                .setItems(labels.toTypedArray()) { _, i ->
                    when (i) {
                        0 -> { val e = EditText(act); AlertDialog.Builder(act).setTitle("New note").setView(e)
                            .setPositiveButton("Save") { _, _ -> io { show(MemoryStore.add(ctx, e.text.toString(), Actor.USER_DEVICE).toString(2)) } }
                            .setNegativeButton("Cancel", null).show() }
                        1 -> io { show(MemoryStore.exportJson(ctx).toString(2)) }
                        2 -> AlertDialog.Builder(act).setTitle("Wipe all memory?").setPositiveButton("Wipe") { _, _ ->
                            io { show("wiped ${MemoryStore.wipe(ctx)} items") } }.setNegativeButton("Cancel", null).show()
                        else -> {
                            val m = items[i - 3]
                            val opts = if (m.pending == 1) arrayOf("Accept", "Delete") else arrayOf("Delete")
                            AlertDialog.Builder(act).setTitle(m.text.take(200)).setItems(opts) { _, j ->
                                io {
                                    if (opts[j] == "Accept") {
                                        AuditLog.record(ctx, Actor.USER_DEVICE, "memory_accept", PermissionEngine.check(Actor.USER_DEVICE, "memory_accept"))
                                        show("accepted=${MemoryStore.acceptFromDevice(ctx, m.id)}")
                                    } else show("deleted=${MemoryStore.delete(ctx, m.id)}")
                                }
                            }.show()
                        }
                    }
                }.setNegativeButton("Close", null).show()
        }
    }

    private fun sendGateDialog() = io {
        val apps = SendGate.appAllowlist(ctx).joinToString("\n")
        val rec = SendGate.recipientAllowlist(ctx).joinToString("\n")
        val status = SendGate.statusJson(ctx, 10).toString(2)
        act.runOnUiThread {
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 0) }
            val note = TextView(act).apply { text = "Kill switch: ${if (AutonomyFlags.get(ctx, AutonomyFlags.SEND)) "ON (requests may be queued for approval)" else "OFF (every request is rejected)"}\n" +
                "Every message still needs your tap on the approval screen. Rules: ${SendPolicy.PER_RECIPIENT_HOUR}/h & " +
                "${SendPolicy.PER_RECIPIENT_DAY}/day per recipient, ${SendPolicy.GLOBAL_DAY}/day total, no links/codes." }
            val appsEt = EditText(act).apply { hint = "Allowed app packages, one per line"; setText(apps) }
            val recEt = EditText(act).apply { hint = "Allowed recipients: package|recipient, one per line"; setText(rec) }
            listOf(note, appsEt, recEt).forEach { col.addView(it) }
            AlertDialog.Builder(act).setTitle("Message send gate").setView(col)
                .setPositiveButton("Save allowlists") { _, _ ->
                    io { SendGate.setAllowlistsFromDevice(ctx, appsEt.text.lines().toSet(), recEt.text.lines().toSet()); show(SendGate.statusJson(ctx).toString(2)) }
                }
                .setNeutralButton(if (AutonomyFlags.get(ctx, AutonomyFlags.SEND)) "Turn sending OFF" else "Turn sending ON…") { _, _ -> killSwitch() }
                .setNegativeButton("Close") { _, _ -> show(status) }.show()
        }
    }

    private fun killSwitch() {
        val on = AutonomyFlags.get(ctx, AutonomyFlags.SEND)
        if (on) {
            AutonomyFlags.set(ctx, AutonomyFlags.SEND, false)
            io { AuditLog.record(ctx, Actor.USER_DEVICE, "send_kill_switch", PermissionEngine.check(Actor.USER_DEVICE, "send_kill_switch"), outcome = "off") }
            refresh(); return
        }
        AlertDialog.Builder(act).setTitle("Allow message requests?")
            .setMessage("Requests to allowlisted recipients will be queued for your approval. Nothing is ever sent without your tap.")
            .setPositiveButton("Turn ON") { _, _ ->
                AutonomyFlags.set(ctx, AutonomyFlags.SEND, true)
                io { AuditLog.record(ctx, Actor.USER_DEVICE, "send_kill_switch", PermissionEngine.check(Actor.USER_DEVICE, "send_kill_switch"), outcome = "on") }
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }
}
