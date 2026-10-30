package com.rjkyker.shizukuai.broker.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import com.rjkyker.shizukuai.broker.autonomy.audit.AuditLog
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.PermissionEngine
import com.rjkyker.shizukuai.broker.autonomy.scheduler.TaskRunner
import com.rjkyker.shizukuai.broker.llm.LlmClient
import com.rjkyker.shizukuai.broker.llm.ModelCatalog
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * On-device writing assistant: rewrite / reply / summarize with the local model. Draft-only:
 * Copy puts text on the clipboard; nothing is ever sent from here. Model management (preflight,
 * download of pinned + SHA-256-verified files into app-private storage, unload, delete) lives here too.
 */
class WritingAssistantActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var out: TextView
    private lateinit var status: TextView
    private val modes = listOf("Rewrite clearly", "Reply to this message", "Summarize", "Make it shorter", "Free prompt")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Writing assistant (on-device)"
        val pad = (12 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val mode = Spinner(this).apply { adapter = ArrayAdapter(this@WritingAssistantActivity, android.R.layout.simple_spinner_dropdown_item, modes) }
        val input = EditText(this).apply {
            hint = "Paste or type text"; minLines = 4; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val gen = Button(this).apply { text = "Generate draft" }
        val stop = Button(this).apply { text = "Stop" }
        val copy = Button(this).apply { text = "Copy draft" }
        out = TextView(this).apply { setTextIsSelectable(true); textSize = 15f }
        status = TextView(this).apply { textSize = 12f }
        val models = Button(this).apply { text = "Model: status / download / unload" }
        listOf(mode, input, gen, stop, copy, out, models, status).forEach { col.addView(it) }
        setContentView(ScrollView(this).apply { addView(col) })

        gen.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            val m = modes[mode.selectedItemPosition]
            val prompt = if (m == "Free prompt") text else "$m:\n\n$text"
            out.text = "Generating locally…"
            gen.isEnabled = false
            io.execute {
                val v = PermissionEngine.check(Actor.USER_DEVICE, "llm_draft")
                AuditLog.record(this, Actor.USER_DEVICE, "llm_draft", v, JSONObject().put("prompt", prompt))
                val r = TaskRunner.draft(this, JSONObject().put("prompt", prompt).put("max_new_tokens", 200))
                runOnUiThread {
                    gen.isEnabled = true
                    out.text = if (r.ok) r.text else "No draft: ${r.error}"
                    val j = r.json
                    status.text = if (j.has("decode_tps")) "model=${j.optString("model")} ${"%.1f".format(j.optDouble("decode_tps"))} tok/s, " +
                        "${j.optInt("gen_tokens")} tokens, peak RSS ${j.optLong("peak_rss_kb") / 1024} MB" +
                        (if (r.needsUser) "\nVerifier flagged: ${j.optJSONObject("verifier")?.optJSONArray("issues")}" else "")
                    else j.toString(2).take(1500)
                }
            }
        }
        stop.setOnClickListener { Thread { LlmClient.cancel(this) }.start() }
        copy.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("draft", out.text))
            Toast.makeText(this, "Draft copied (nothing was sent)", Toast.LENGTH_SHORT).show()
        }
        models.setOnClickListener { modelMenu() }
        refreshStatus()
    }

    private fun refreshStatus() = Thread {
        val s = LlmClient.status(this)
        val pf = LlmClient.preflight(this, null)
        runOnUiThread { status.text = "status: ${compact(s)}\n\npreflight: ${pf.toString(2).take(2500)}" }
    }.start()

    private fun compact(s: JSONObject) = JSONObject(s.toString()).apply { remove("models") }.toString(2).take(1500)

    private fun modelMenu() {
        val items = ModelCatalog.ALL.map { "Download ${it.id} (${it.sizeMb} MB)" } + listOf("Unload model", "Refresh status") +
            ModelCatalog.ALL.map { "Delete ${it.id}" }
        AlertDialog.Builder(this).setTitle("Local model").setItems(items.toTypedArray()) { _, i ->
            val n = ModelCatalog.ALL.size
            Thread {
                val r = when {
                    i < n -> LlmClient.ensureModel(this, ModelCatalog.ALL[i].id)
                    i == n -> LlmClient.unload(this)
                    i == n + 1 -> LlmClient.status(this)
                    else -> LlmClient.deleteModel(this, ModelCatalog.ALL[i - n - 2].id)
                }
                runOnUiThread { status.text = r.toString(2).take(3000) }
            }.start()
        }.setNegativeButton("Close", null).show()
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
