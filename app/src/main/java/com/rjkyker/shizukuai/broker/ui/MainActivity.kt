package com.rjkyker.shizukuai.broker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.rjkyker.shizukuai.broker.R
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.admin.DelegatedAdminActionHandler
import com.rjkyker.shizukuai.broker.bridge.PermissionRequestResult
import com.rjkyker.shizukuai.broker.bridge.ShizukuGateway
import com.rjkyker.shizukuai.broker.databinding.ActivityMainBinding
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeQuery
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver
import com.rjkyker.shizukuai.broker.datawedge.ScanHistoryStore
import com.rjkyker.shizukuai.broker.datawedge.ScanParsers
import org.json.JSONObject
import com.rjkyker.shizukuai.broker.dhizuku.DhizukuGateway
import com.rjkyker.shizukuai.broker.discovery.BrokerStatusSnapshot
import com.rjkyker.shizukuai.broker.discovery.DiscoveryProvider
import com.rjkyker.shizukuai.broker.discovery.NsdAdvertiser
import com.rjkyker.shizukuai.broker.mcp.BridgeMeshRelay
import com.rjkyker.shizukuai.broker.sensing.WifiPresenceEngine
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.service.BrokerService
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import rikka.shizuku.Shizuku
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Command-center dashboard. Every panel is bound to real broker state:
 * Systems (server/Shizuku/Dhizuku/a11y/upstream/Airtable/Wi-Fi engine/mDNS/app), Bridge mesh
 * (live GET …/health probes), Connections (from the same discovery manifest served at /discover),
 * Security (token reveal / rotate), Controls, Settings (prefilled URLs, Airtable key), Customize.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var admin: DelegatedAdminActionHandler
    private var tokenVisible = false

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusAdapter: StatusRowAdapter
    private lateinit var bridgeAdapter: StatusRowAdapter
    private lateinit var connectionAdapter: StatusRowAdapter
    private lateinit var scanAdapter: StatusRowAdapter
    private var selectedScan: JSONObject? = null

    private data class Probe(val ok: Boolean, val detail: String)
    private val probes = ConcurrentHashMap<String, Probe>()
    @Volatile private var probing = false
    @Volatile private var lastProbeAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            if (System.currentTimeMillis() - lastProbeAt > PROBE_INTERVAL_MS) probeBridges()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshStatus() } }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refreshStatus() } }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == REQUEST_CODE_SHIZUKU) runOnUiThread { refreshStatus() }
        }

    /** Hideable panels: pref key → card. Customize itself is always shown. */
    private val panels: List<Pair<String, MaterialCardView>> by lazy {
        listOf(
            "systems" to binding.panelSystems,
            "mesh" to binding.panelMesh,
            "connections" to binding.panelConnections,
            "security" to binding.panelSecurity,
            "controls" to binding.panelControls,
            "settings" to binding.panelSettings,
            "scopes" to binding.panelScopes,
            "scans" to binding.panelScans
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        admin = DelegatedAdminActionHandler(this)

        SecurePrefs.ensureMcpToken(applicationContext)
        SecurePrefs.seedDefaultUrls(applicationContext)
        try {
            BrokerService.start(applicationContext)
        } catch (_: Exception) {
            // never crash the UI if the service can't start
        }

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        val copy: (String, String) -> Unit = { label, text -> copyToClipboard(label, text) }
        statusAdapter = StatusRowAdapter(copy)
        bridgeAdapter = StatusRowAdapter(copy)
        connectionAdapter = StatusRowAdapter(copy)
        scanAdapter = StatusRowAdapter(copy)
        setupList(binding.statusList, statusAdapter)
        setupList(binding.bridgeList, bridgeAdapter)
        setupList(binding.connectionList, connectionAdapter)
        setupList(binding.scanList, scanAdapter)
        binding.scanCopyButton.setOnClickListener { copySelectedScan() }
        binding.scanClearButton.setOnClickListener { clearScans() }
        ScanHistoryStore.ensureLoaded(applicationContext)

        binding.refreshButton.setOnClickListener { refreshStatus(); probeBridges() }
        binding.probeBridgesButton.setOnClickListener { probeBridges() }
        binding.a11yButton.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.shizukuPermissionButton.setOnClickListener { requestShizukuPermission() }
        binding.dhizukuButton.setOnClickListener { connectDhizuku(userInitiated = true) }
        binding.datawedgeButton.setOnClickListener { queryDataWedge() }
        binding.saveMeshBaseButton.setOnClickListener { saveMeshBase() }
        binding.saveUpstreamButton.setOnClickListener { saveUpstreamUrl() }
        binding.mcpTokenButton.setOnClickListener { toggleMcpToken() }
        binding.mcpTokenButton.setOnLongClickListener { rotateMcpToken(); true }
        binding.rotateTokenButton.setOnClickListener { rotateMcpToken() }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.saveAirtableButton.setOnClickListener { saveAirtableKey() }
        binding.saveBridgesButton.setOnClickListener { saveBridgeUrls() }

        setupCustomize()
        applyPanelVisibility()
        applyAccent()
        fillSettingsFields(force = true)
        refreshStatus()

        if (!dhizukuAutoRequested) {
            dhizukuAutoRequested = true
            connectDhizuku(userInitiated = false)
        }
        if (!batteryPromptShown) {
            batteryPromptShown = true
            maybePromptBatteryExemption()
        }
    }

    private fun setupList(rv: RecyclerView, adapter: StatusRowAdapter) {
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.itemAnimator = null
    }

    // ---------------- Customize ----------------

    private fun setupCustomize() {
        val container = binding.panelToggleContainer
        container.removeAllViews()
        val titles = mapOf(
            "systems" to R.string.panel_systems, "mesh" to R.string.panel_mesh,
            "connections" to R.string.panel_connections, "security" to R.string.panel_security,
            "controls" to R.string.panel_controls, "settings" to R.string.panel_settings,
            "scans" to R.string.panel_scans,
            "scopes" to R.string.scopes_label
        )
        for ((key, _) in panels) {
            val cb = MaterialCheckBox(this).apply {
                text = getString(titles.getValue(key))
                setTextColor(ContextCompat.getColor(context, R.color.cc_text))
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                isChecked = UiPrefs.isPanelVisible(context, key)
                setOnCheckedChangeListener { _, checked ->
                    UiPrefs.setPanelVisible(context, key, checked)
                    applyPanelVisibility()
                }
            }
            container.addView(cb)
        }
        val group = binding.accentGroup
        group.removeAllViews()
        val current = UiPrefs.accent(this).key
        for (a in UiPrefs.ACCENTS) {
            val color = ContextCompat.getColor(this, a.colorRes)
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = a.label
                isCheckable = true
                isChecked = a.key == current
                chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.cc_row))
                chipStrokeColor = ColorStateList.valueOf(color)
                chipStrokeWidth = 2f
                setTextColor(color)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        UiPrefs.setAccent(context, a.key)
                        applyAccent()
                    }
                }
            }
            group.addView(chip)
        }
    }

    private fun applyPanelVisibility() {
        for ((key, card) in panels) {
            card.visibility = if (UiPrefs.isPanelVisible(this, key)) View.VISIBLE else View.GONE
        }
    }

    private fun applyAccent() {
        val accent = ContextCompat.getColor(this, UiPrefs.accent(this).colorRes)
        val stroke = ColorUtils.setAlphaComponent(accent, 0x55)
        fun walk(v: View) {
            when (v) {
                is MaterialCardView -> v.strokeColor = stroke
                is MaterialButton -> if (v.id != R.id.rowCopy) {
                    v.setTextColor(accent)
                    v.strokeColor = ColorStateList.valueOf(accent)
                }
                is TextView -> if (v.tag == "accent") v.setTextColor(accent)
            }
            if (v is ViewGroup && v !is RecyclerView) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(binding.root)
        listOf(statusAdapter, bridgeAdapter, connectionAdapter, scanAdapter).forEach { it.accent = accent }
    }

    // ---------------- Status binding (real state only) ----------------

    private fun refreshStatus() {
        val ctx = applicationContext
        val snap = runCatching { BrokerStatusSnapshot.build(ctx) }.getOrNull()
        val server = BrokerServer.get()
        val running = server?.isRunning == true
        val shizuku = ShizukuGateway.status()
        val pm = getSystemService(PowerManager::class.java)
        val battExempt = pm?.isIgnoringBatteryOptimizations(packageName) == true
        val a11y = AgentAccessibilityService.isEnabled()
        val airtableSet = SecurePrefs.getAirtableApiKey(ctx).isNotBlank()
        val wifi = WifiPresenceEngine.stateJson()
        val up = probes["upstream"]
        val dzInstalled = DhizukuGateway.isInstalled(this)
        val dzGranted = DhizukuGateway.isGranted()

        val rows = mutableListOf<StatusRow>()
        rows += StatusRow(
            "MCP server",
            if (running) "LISTENING ${BrokerServer.HOST}:${BrokerServer.PORT} · bearer required"
            else if (server == null) "NOT STARTED" else "STOPPED",
            if (running) Led.OK else Led.BAD
        )
        rows += StatusRow("Shizuku binder", if (shizuku.binderAlive) "CONNECTED" else "NOT CONNECTED",
            if (shizuku.binderAlive) Led.OK else Led.BAD)
        rows += StatusRow("Shizuku permission", if (shizuku.permissionGranted) "GRANTED" else "NOT GRANTED",
            if (shizuku.permissionGranted) Led.OK else Led.WARN)
        rows += StatusRow("Shizuku backend", when (shizuku.backendUid) {
            0 -> "root (uid 0)"; 2000 -> "ADB shell (uid 2000)"; null -> "not available"
            else -> "uid ${shizuku.backendUid}"
        }, if (shizuku.backendUid != null) Led.INFO else Led.OFF)
        rows += StatusRow(
            "Dhizuku device-owner",
            when {
                !dzInstalled -> "NOT INSTALLED"
                dzGranted -> "GRANTED (owner ${DhizukuGateway.ownerComponent()?.packageName ?: "?"})"
                else -> "NOT GRANTED (${DhizukuGateway.lastPermissionResult})"
            },
            when { dzGranted -> Led.OK; dzInstalled -> Led.WARN; else -> Led.OFF }
        )
        rows += StatusRow("Accessibility service", if (a11y) "ON" else "OFF", if (a11y) Led.OK else Led.WARN)
        rows += StatusRow(
            "Upstream reachable",
            when {
                up == null -> "not probed yet · ${hostOf(SecurePrefs.getUpstreamMcpUrl(ctx))}"
                up.ok -> "YES · ${hostOf(SecurePrefs.getUpstreamMcpUrl(ctx))}"
                else -> "NO · ${up.detail}"
            },
            when { up == null -> Led.OFF; up.ok -> Led.OK; else -> Led.BAD }
        )
        rows += StatusRow("Airtable key", if (airtableSet) "SET (hidden)" else "NOT SET",
            if (airtableSet) Led.OK else Led.OFF)
        val wState = wifi.optString("state")
        rows += StatusRow(
            "Wi-Fi presence engine (RSSI, near-device only)",
            if (wifi.optLong("last_scan_at_ms") > 0)
                "$wState · last presence=${wifi.optBoolean("last_presence_detected")} " +
                    "motion=${wifi.optDouble("last_motion_score")} conf=${wifi.optDouble("last_confidence")}"
            else wState,
            when (wState) { "scanning" -> Led.INFO; "idle" -> Led.OK; "idle_last_failed" -> Led.WARN; else -> Led.OFF }
        )
        val nsdTypes = NsdAdvertiser.registeredTypes()
        rows += StatusRow(
            "mDNS advertisement",
            if (nsdTypes.isNotEmpty()) "${nsdTypes.joinToString(" + ")} as ${NsdAdvertiser.serviceName()}"
            else NsdAdvertiser.lastError().ifBlank { "not registered" },
            if (nsdTypes.isNotEmpty()) Led.OK else Led.WARN
        )
        rows += StatusRow("Battery optimization", if (battExempt) "EXEMPT" else "NOT EXEMPT (Doze may stop server)",
            if (battExempt) Led.OK else Led.WARN)
        rows += StatusRow(
            "App",
            "v${versionName()} · pid ${snap?.optInt("pid") ?: android.os.Process.myPid()} · up ${fmtUptime(BrokerStatusSnapshot.uptimeMs())}",
            Led.INFO
        )
        statusAdapter.submit(rows)
        refreshScans()

        binding.headerLed.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, when {
                running && shizuku.permissionGranted -> R.color.led_ok
                running -> R.color.led_warn
                else -> R.color.led_bad
            })
        )
        binding.headerSubtitle.text = "v${versionName()} · ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · MCP :${BrokerServer.PORT}"

        bindBridges()
        bindConnections()

        // Controls / scopes
        binding.dhizukuButton.isEnabled = !dzGranted
        binding.shizukuPermissionButton.isEnabled = shizuku.binderAlive && !shizuku.permissionGranted
        val scopes = admin.getActiveScopes()
        val dhizukuScopes = if (dzGranted) {
            runCatching { com.rosan.dhizuku.api.Dhizuku.getDelegatedScopes()?.toList() }.getOrNull().orEmpty()
        } else emptyList()
        val allScopes = (scopes + dhizukuScopes).distinct()
        binding.scopesText.text = when {
            allScopes.isNotEmpty() -> allScopes.joinToString("\n")
            dzGranted -> getString(R.string.no_scopes_dhizuku_granted)
            dzInstalled -> getString(R.string.no_scopes_dhizuku_pending, DhizukuGateway.lastPermissionResult)
            else -> getString(R.string.no_scopes)
        }
        if (binding.datawedgeStatusText.text.isNullOrBlank() ||
            binding.datawedgeStatusText.text == getString(R.string.datawedge_none)
        ) {
            binding.datawedgeStatusText.text = DataWedgeResultReceiver.lastStatusString.get()
        }
        if (!tokenVisible) binding.mcpTokenText.text = getString(R.string.mcp_token_hidden)
        fillSettingsFields(force = false)
    }

    private fun bindBridges() {
        val mesh = BridgeMeshRelay(applicationContext)
        val rows = BridgeMeshRelay.BRIDGES.map { name ->
            val url = mesh.urlFor(name)
            val p = probes[name]
            StatusRow(
                name,
                when {
                    url.isBlank() -> "not configured"
                    p == null -> "${if (probing) "probing…" else "unknown"} · $url"
                    p.ok -> "REACHABLE · $url"
                    else -> "UNREACHABLE · ${p.detail}"
                },
                when { url.isBlank() -> Led.OFF; p == null -> Led.OFF; p.ok -> Led.OK; else -> Led.BAD },
                copyText = url.ifBlank { null }
            )
        }
        bridgeAdapter.submit(rows)
        binding.meshProbeText.text = when {
            probing -> getString(R.string.mesh_probing)
            lastProbeAt == 0L -> getString(R.string.mesh_not_probed)
            else -> getString(R.string.mesh_probed_at, DateFormat.getTimeInstance().format(Date(lastProbeAt)))
        }
    }

    /** Rows come from the very same manifest served at GET /discover, so UI and AIs agree. */
    private fun bindConnections() {
        val manifest = runCatching { DiscoveryProvider.manifest(applicationContext) }.getOrNull() ?: return
        val rows = mutableListOf<StatusRow>()
        val base = "http://${BrokerServer.HOST}:${BrokerServer.PORT}"
        rows += StatusRow("Discovery manifest (open)", "$base/discover", Led.INFO, "$base/discover")
        rows += StatusRow("Well-known (open)", "$base/.well-known/mcp.json", Led.INFO, "$base/.well-known/mcp.json")
        val conns = manifest.optJSONArray("connections")
        for (i in 0 until (conns?.length() ?: 0)) {
            val c = conns!!.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "loopback" -> {
                    rows += StatusRow("Loopback MCP (HTTP)", c.optString("mcp_http"), Led.OK, c.optString("mcp_http"))
                    rows += StatusRow("Loopback MCP (WebSocket)", c.optString("mcp_ws"), Led.OK, c.optString("mcp_ws"))
                }
                "tailscale" -> rows += StatusRow(
                    "Tailscale node", "${c.optString("address")} · via adb forward (server binds loopback)",
                    Led.INFO, c.optString("address")
                )
                "mesh_bridge" -> {
                    val p = probes[c.optString("bridge")]
                    rows += StatusRow(
                        "Mesh · ${c.optString("bridge")}", c.optString("mcp_http"),
                        when { p == null -> Led.OFF; p.ok -> Led.OK; else -> Led.BAD }, c.optString("mcp_http")
                    )
                }
                "upstream" -> rows += StatusRow("Upstream", c.optString("mcp_http"), Led.INFO, c.optString("mcp_http"))
            }
        }
        val mdns = manifest.optJSONObject("mdns")
        if (mdns != null) {
            rows += StatusRow(
                "mDNS / zeroconf",
                "${mdns.optJSONArray("service_types")?.join(" + ")?.replace("\"", "")} · ${mdns.optString("service_name")} · port ${mdns.optInt("port")}",
                if (mdns.optBoolean("registered")) Led.OK else Led.WARN
            )
        }
        connectionAdapter.submit(rows)
    }

    private fun probeBridges() {
        if (probing) return
        probing = true
        bindBridges()
        val ctx = applicationContext
        Thread({
            val mesh = BridgeMeshRelay(ctx)
            for (name in BridgeMeshRelay.BRIDGES) {
                if (mesh.urlFor(name).isBlank()) { probes.remove(name); continue }
                val h = runCatching { mesh.health(name, PROBE_TIMEOUT_MS) }.getOrNull()
                probes[name] = if (h?.optBoolean("ok") == true) Probe(true, "HTTP ${h.optInt("http")}")
                else Probe(false, h?.optString("error")?.take(90) ?: "probe failed")
            }
            lastProbeAt = System.currentTimeMillis()
            probing = false
            runOnUiThread { if (!isFinishing) refreshStatus() }
        }, "BridgeProbe").start()
    }

    // ---------------- Settings (prefilled, editable) ----------------

    private fun fillSettingsFields(force: Boolean) {
        val ctx = applicationContext
        if (force || !binding.meshBaseInput.hasFocus()) binding.meshBaseInput.setText(SecurePrefs.getMeshBaseUrl(ctx))
        if (force || !binding.upstreamUrlInput.hasFocus()) binding.upstreamUrlInput.setText(SecurePrefs.getUpstreamMcpUrl(ctx))
        if (force || !binding.bridgeUrlsInput.hasFocus()) {
            binding.bridgeUrlsInput.setText(
                SecurePrefs.BRIDGE_NAMES.joinToString(",\n") { "$it=${SecurePrefs.getBridgeUrl(ctx, it)}" }
            )
        }
        if (force || !binding.airtableKeyInput.hasFocus()) {
            binding.airtableKeyInput.hint = getString(
                if (SecurePrefs.getAirtableApiKey(ctx).isNotBlank()) R.string.airtable_key_set else R.string.airtable_key_hint
            )
        }
    }

    private fun saveMeshBase() {
        val raw = binding.meshBaseInput.text?.toString().orEmpty().trim()
        try {
            val v = if (raw.trimEnd('/') == SecurePrefs.DEFAULT_MESH_BASE_URL) "" else raw
            SecurePrefs.setMeshBaseUrl(applicationContext, v)
            Toast.makeText(this, "Mesh base: ${SecurePrefs.getMeshBaseUrl(applicationContext)}", Toast.LENGTH_SHORT).show()
            fillSettingsFields(force = true)
            refreshStatus()
            probeBridges()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid URL: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveUpstreamUrl() {
        val raw = binding.upstreamUrlInput.text?.toString().orEmpty().trim()
        try {
            val def = SecurePrefs.defaultBridgeUrl("android", SecurePrefs.getMeshBaseUrl(applicationContext))
            SecurePrefs.setUpstreamMcpUrl(applicationContext, if (raw == def) "" else raw)
            Toast.makeText(this, "Upstream: ${SecurePrefs.getUpstreamMcpUrl(applicationContext)}", Toast.LENGTH_LONG).show()
            if (raw.contains("192.168.") || raw.contains("10.0.")) {
                Toast.makeText(this, "Warning: that looks LAN-only; it will not work off the home Wi-Fi.", Toast.LENGTH_LONG).show()
            }
            fillSettingsFields(force = true)
            refreshStatus()
            BrokerService.start(applicationContext)
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid URL: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveBridgeUrls() {
        // Format: name=url separated by commas/newlines. Blank url or the built-in default = restore default.
        val raw = binding.bridgeUrlsInput.text?.toString().orEmpty()
        val base = SecurePrefs.getMeshBaseUrl(applicationContext)
        var saved = 0
        val errors = StringBuilder()
        raw.split(',', '\n').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
            val name = pair.substringBefore('=').trim().lowercase()
            val url = pair.substringAfter('=').trim()
            if (name in SecurePrefs.BRIDGE_NAMES) {
                try {
                    val v = if (url == SecurePrefs.defaultBridgeUrl(name, base)) "" else url
                    SecurePrefs.setBridgeUrl(applicationContext, name, v)
                    saved++
                } catch (e: Exception) {
                    errors.append("$name: ${e.message}; ")
                }
            } else {
                errors.append("unknown bridge '$name'; ")
            }
        }
        Toast.makeText(this, "Saved $saved bridge URL(s)${if (errors.isNotEmpty()) " — $errors" else ""}", Toast.LENGTH_LONG).show()
        fillSettingsFields(force = true)
        refreshStatus()
        probeBridges()
    }

    private fun saveAirtableKey() {
        val raw = binding.airtableKeyInput.text?.toString().orEmpty().trim()
        SecurePrefs.setAirtableApiKey(applicationContext, raw)
        binding.airtableKeyInput.setText("")
        Toast.makeText(this, if (raw.isEmpty()) "Airtable key cleared" else "Airtable key saved (encrypted)", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    // ---------------- Security ----------------

    private fun toggleMcpToken() {
        if (tokenVisible) {
            hideToken()
            return
        }
        val token = SecurePrefs.ensureMcpToken(applicationContext)
        showTokenOnce(token, "MCP token copied to clipboard")
    }

    private fun rotateMcpToken() {
        AlertDialog.Builder(this)
            .setTitle(R.string.rotate_confirm_title)
            .setMessage(R.string.rotate_confirm_msg)
            .setPositiveButton("Rotate") { _, _ ->
                val token = SecurePrefs.rotateMcpToken(applicationContext)
                showTokenOnce(token, "New MCP token generated and copied")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTokenOnce(token: String, msg: String) {
        tokenVisible = true
        binding.mcpTokenText.text = token
        binding.mcpTokenButton.text = getString(R.string.hide_mcp_token)
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("mcp_token", token))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun hideToken() {
        tokenVisible = false
        binding.mcpTokenText.text = getString(R.string.mcp_token_hidden)
        binding.mcpTokenButton.text = getString(R.string.reveal_mcp_token)
    }

    private fun copyToClipboard(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.copied, text), Toast.LENGTH_SHORT).show()
    }

    // ---------------- Controls ----------------

    private fun queryDataWedge() {
        binding.datawedgeStatusText.text = "DataWedge: querying status/version/profiles…"
        Thread({
            val r = DataWedgeQuery.run(applicationContext, "all", 6000L)
            runOnUiThread {
                Toast.makeText(this, if (r.optBoolean("datawedge_replied")) "DataWedge replied" else "No DataWedge reply", Toast.LENGTH_SHORT).show()
                binding.datawedgeStatusText.text = buildString {
                    append("status: ").append(r.opt("status")).append('\n')
                    append("version: ").append(r.opt("version").toString().take(160)).append('\n')
                    append("active: ").append(r.opt("active_profile")).append('\n')
                    append("profiles: ").append(r.opt("profiles").toString().take(400))
                }
            }
        }, "DwQueryUi").start()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already ignoring battery optimizations", Toast.LENGTH_SHORT).show()
            refreshStatus()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun maybePromptBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        Toast.makeText(this, "Tip: tap “Ignore battery optimizations” so the MCP server survives Doze", Toast.LENGTH_LONG).show()
    }

    private fun connectDhizuku(userInitiated: Boolean) {
        if (userInitiated && DhizukuGateway.isInstalled(applicationContext)) {
            DhizukuGateway.reconnect(applicationContext)
        }
        val result = DhizukuGateway.requestPermission(applicationContext) { _ -> runOnUiThread { refreshStatus() } }
        if (userInitiated || result != "dhizuku_not_installed") {
            val msg = when (result) {
                "requested" -> "Dhizuku: approve Shizuku AI Broker in the Dhizuku prompt / app list"
                "already_granted" -> "Dhizuku: already granted"
                "dhizuku_not_installed" -> "Dhizuku is not installed"
                "dhizuku_not_running" -> "Dhizuku is not activated / not reachable"
                else -> "Dhizuku: $result"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }

    private fun requestShizukuPermission() {
        when (val result = ShizukuGateway.requestPermission(REQUEST_CODE_SHIZUKU)) {
            PermissionRequestResult.AlreadyGranted -> refreshStatus()
            PermissionRequestResult.Requested -> Toast.makeText(this, "Shizuku permission request sent.", Toast.LENGTH_SHORT).show()
            PermissionRequestResult.BinderUnavailable -> Toast.makeText(this, "Shizuku service is not connected.", Toast.LENGTH_LONG).show()
            PermissionRequestResult.RationaleRequired -> Toast.makeText(this, "Enable permission from the Shizuku app, then try again.", Toast.LENGTH_LONG).show()
            is PermissionRequestResult.Failed -> Toast.makeText(this, "Permission request failed: ${result.cause?.message ?: "unknown"}", Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }

    // ---------------- Lifecycle ----------------

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        // "Show once": never leave a revealed token on screen when the app is backgrounded.
        hideToken()
        super.onPause()
    }


    private fun refreshScans() {
        ScanHistoryStore.ensureLoaded(applicationContext)
        val arr = ScanHistoryStore.latest(50)
        val rows = mutableListOf<StatusRow>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val ts = s.optLong("ts")
            val whenStr = if (ts > 0) DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(ts)) else "?"
            val label = s.optString("label_type", "unknown")
            val visible = s.optString("visible_text", s.optString("data_string")).take(60)
            val src = s.optString("source", "?")
            rows += StatusRow(
                label = "$whenStr · $label · $src",
                value = visible,
                state = Led.INFO,
                copyText = s.toString(2)
            )
        }
        scanAdapter.submit(rows)
        // Keep detail pane on selected or newest
        val detail = selectedScan ?: arr.optJSONObject(0)
        binding.scanDetailText.text = if (detail != null) formatScanDetail(detail) else getString(R.string.scans_empty)
        // Tap a row → expand: StatusRowAdapter copy button already copies; also set selection via long-press value
        // Simpler: if user hits Copy on a row it copies JSON; detail shows newest unless selectedScan set via copyText match
    }

    private fun formatScanDetail(s: JSONObject): String {
        val sb = StringBuilder()
        sb.append("label: ").append(s.optString("label_type", "unknown")).append('\n')
        sb.append("aim: ").append(s.optString("aim_id", "unknown")).append('\n')
        sb.append("source: ").append(s.optString("source", "unknown")).append('\n')
        sb.append("mode: ").append(s.optString("decoded_mode", "unknown")).append('\n')
        sb.append("len: ").append(s.optInt("length")).append(" chars / ").append(s.optInt("byte_length")).append(" bytes\n")
        sb.append("charset: ").append(s.optString("charset")).append('\n')
        sb.append("visible: ").append(s.optString("visible_text")).append('\n')
        sb.append("base64: ").append(s.optString("base64").take(120)).append("…\n")
        val segs = s.optJSONArray("decode_data")
        if (segs != null) {
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONObject(i) ?: continue
                sb.append("--- decode_data[").append(i).append("] ").append(seg.optInt("byte_length")).append(" bytes ---\n")
                sb.append(seg.optString("hex_dump")).append('\n')
            }
        }
        val parsers = s.optJSONArray("parsers")
        if (parsers != null) {
            sb.append("--- parsers ---\n")
            for (i in 0 until parsers.length()) {
                sb.append(parsers.optJSONObject(i)?.toString(2) ?: "").append('\n')
            }
        }
        s.optString("structure_note").takeIf { it.isNotEmpty() }?.let {
            sb.append("note: ").append(it).append('\n')
        }
        return sb.toString()
    }

    private fun copySelectedScan() {
        val detail = selectedScan ?: ScanHistoryStore.latest(1).optJSONObject(0)
        if (detail == null) {
            Toast.makeText(this, "No scan to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("scan", detail.toString(2)))
        Toast.makeText(this, "Scan JSON copied", Toast.LENGTH_SHORT).show()
    }

    private fun clearScans() {
        ScanHistoryStore.clear(applicationContext)
        selectedScan = null
        refreshScans()
        Toast.makeText(this, "Scan history cleared", Toast.LENGTH_SHORT).show()
    }


    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    // ---------------- helpers ----------------

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun hostOf(url: String): String = runCatching { Uri.parse(url).host }.getOrNull() ?: url

    private fun fmtUptime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m ${s % 60}s"
    }

    companion object {
        private const val REQUEST_CODE_SHIZUKU = 1001
        private const val TICK_MS = 5_000L
        private const val PROBE_INTERVAL_MS = 60_000L
        private const val PROBE_TIMEOUT_MS = 5_000L
        @Volatile private var dhizukuAutoRequested = false
        @Volatile private var batteryPromptShown = false
    }
}
