package com.rjkyker.shizukuai.broker.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.rjkyker.shizukuai.broker.assist.AssistSettings
import com.rjkyker.shizukuai.broker.cache.CacheCleaner
import com.rjkyker.shizukuai.broker.policy.BrokerPolicies
import com.rjkyker.shizukuai.broker.stagenow.StageNowLauncher
import com.rjkyker.shizukuai.broker.ui.mod.ModFx
import java.util.concurrent.Executors

/** 1.10.0: Policies + Clear cache + StageNow. Soft toggles only; hard denylist is locked. */
class PoliciesActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Broker policies"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); setBackgroundColor(ModFx.BG) }
        fun txt(t: String, c: Int = ModFx.TEXT, sp: Float = 14f) = TextView(this).apply {
            text = t; setTextColor(c); textSize = sp; setPadding(0, pad / 3, 0, pad / 3)
        }
        col.addView(txt("POLICIES", ModFx.CYAN, 18f))
        col.addView(txt("Soft toggles below. The hard shell denylist is locked and cannot be turned off.", ModFx.DIM, 13f))

        fun soft(key: String, label: String, hardening: Boolean = false) = SwitchCompat(this).apply {
            text = label; setTextColor(ModFx.TEXT); isChecked = BrokerPolicies.get(this@PoliciesActivity, key)
            isEnabled = !hardening
            setOnCheckedChangeListener { _, v ->
                val err = BrokerPolicies.set(this@PoliciesActivity, key, v)
                if (err != null) { Toast.makeText(this@PoliciesActivity, err, Toast.LENGTH_LONG).show(); isChecked = true }
                refresh()
            }
        }
        col.addView(soft(BrokerPolicies.TYPE_ANYWHERE, "Type Anywhere (finish my sentences)"))
        col.addView(soft(BrokerPolicies.SKIP_SENSITIVE, "Skip password / OTP / PIN / card fields (locked ON)", hardening = true))
        col.addView(soft(BrokerPolicies.PREVIEW_FIRST, "Preview-first messaging (locked ON; AI never auto-sends)", hardening = true))
        col.addView(soft(BrokerPolicies.FOREGROUND_RECHECK, "Re-check foreground app before every touch"))
        col.addView(soft(BrokerPolicies.MCP_HOST_ALLOWLIST, "Enforce MCP host allowlist for upstream/relays"))

        col.addView(Button(this).apply {
            text = "MCP host allowlist…"; setOnClickListener {
                val e = EditText(this@PoliciesActivity).apply {
                    setText(BrokerPolicies.hostAllowlist(this@PoliciesActivity).sorted().joinToString("\n")); minLines = 4
                }
                AlertDialog.Builder(this@PoliciesActivity).setTitle("Allowed MCP hosts (one per line)")
                    .setView(e).setPositiveButton("Save") { _, _ ->
                        BrokerPolicies.setHostAllowlist(this@PoliciesActivity, e.text.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                        refresh()
                    }.setNegativeButton("Cancel", null).show()
            }
        })
        col.addView(Button(this).apply {
            text = "Type Anywhere excluded apps…"; setOnClickListener {
                startActivity(Intent(this@PoliciesActivity, com.rjkyker.shizukuai.broker.assist.AssistSettingsActivity::class.java))
            }
        })

        col.addView(txt("LOCKED DENYLIST (read-only)", ModFx.AMBER, 16f))
        BrokerPolicies.LOCKED_DENYLIST.forEach { col.addView(txt("• $it", ModFx.DIM, 12f)) }

        col.addView(txt("CLEAR CACHE", ModFx.CYAN, 18f))
        col.addView(txt("Clears only this app's cache folders. Never pm clear, never your photos/Downloads.", ModFx.DIM, 13f))
        col.addView(Button(this).apply {
            text = "Clear broker cache now"
            setOnClickListener {
                io.execute {
                    val r = CacheCleaner.clearOwn(this@PoliciesActivity)
                    runOnUiThread {
                        Toast.makeText(this@PoliciesActivity, "Freed ${r.optLong("freed_bytes")} bytes", Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }
            }
        })
        col.addView(Button(this).apply {
            text = "Trim system caches (pm trim-caches 2G)…"
            setOnClickListener {
                AlertDialog.Builder(this@PoliciesActivity)
                    .setTitle("Trim other apps' caches?")
                    .setMessage("Runs `pm trim-caches 2G` via Shizuku. Frees other apps' cache files only. Never deletes your media. Needs Shizuku.")
                    .setPositiveButton("Trim") { _, _ ->
                        io.execute {
                            val r = CacheCleaner.trimSystem("2G")
                            runOnUiThread {
                                Toast.makeText(this@PoliciesActivity, if (r.optBoolean("ok")) "Trim requested" else "Failed: ${r.optString("error").ifBlank { r.optString("stderr") }}", Toast.LENGTH_LONG).show()
                                refresh()
                            }
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
        })

        col.addView(txt("STAGENOW / MX", ModFx.CYAN, 18f))
        col.addView(txt("Legitimate MX XML only. No Mobility DNA license bypass. Premium MX is listed as omitted.", ModFx.DIM, 13f))
        listOf(
            "unlock_combined" to "Export unlock pack (AccessMgr Full + SettingsMgr)",
            "accessmgr_full" to "Export AccessMgr Full only",
            "appmgr_disable_osx" to "Export AppMgr disable OSX Provision Service",
        ).forEach { (id, label) ->
            col.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    val needConfirm = id.startsWith("appmgr_")
                    fun go() = io.execute {
                        val r = StageNowLauncher.exportNamed(this@PoliciesActivity, id)
                        runOnUiThread {
                            if (!r.optBoolean("ok")) {
                                Toast.makeText(this@PoliciesActivity, r.optString("error"), Toast.LENGTH_LONG).show(); return@runOnUiThread
                            }
                            val path = r.getString("path")
                            Toast.makeText(this@PoliciesActivity, "Saved $path", Toast.LENGTH_LONG).show()
                            StageNowLauncher.shareIntent(this@PoliciesActivity, path)?.let { startActivity(Intent.createChooser(it, "Share StageNow XML")) }
                            refresh()
                        }
                    }
                    if (needConfirm) AlertDialog.Builder(this@PoliciesActivity).setTitle("Export disable profile?")
                        .setMessage("This XML disables com.symbol.osx.osxprovisionservice via AppMgr. Apply only after Settings are unlocked.")
                        .setPositiveButton("Export") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
                    else go()
                }
            })
        }
        col.addView(Button(this).apply {
            text = "Open StageNow app"
            setOnClickListener {
                val r = StageNowLauncher.launchStageNow(this@PoliciesActivity)
                if (!r.optBoolean("ok")) Toast.makeText(this@PoliciesActivity, r.optString("error"), Toast.LENGTH_LONG).show()
            }
        })

        status = txt("", ModFx.DIM, 12f)
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
        refresh()
    }

    private fun refresh() = io.execute {
        val s = buildString {
            append(BrokerPolicies.toJson(this@PoliciesActivity).toString(2)).append("\n\n")
            append("cache: ").append(CacheCleaner.status(this@PoliciesActivity)).append("\n\n")
            append("stagenow: ").append(StageNowLauncher.status(this@PoliciesActivity))
            append("\nassist excluded: ").append(AssistSettings.excluded(this@PoliciesActivity))
        }
        runOnUiThread { status.text = s }
    }
}
