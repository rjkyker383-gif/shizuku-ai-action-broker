package com.rjkyker.shizukuai.broker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rjkyker.shizukuai.broker.R
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.admin.DelegatedAdminActionHandler
import com.rjkyker.shizukuai.broker.bridge.PermissionRequestResult
import com.rjkyker.shizukuai.broker.bridge.ShizukuGateway
import com.rjkyker.shizukuai.broker.databinding.ActivityMainBinding
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.service.BrokerService
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeQuery
import com.rjkyker.shizukuai.broker.dhizuku.DhizukuGateway
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var admin: DelegatedAdminActionHandler
    private var tokenVisible = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshStatus() }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == REQUEST_CODE_SHIZUKU) {
                runOnUiThread { refreshStatus() }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        admin = DelegatedAdminActionHandler(this)

        // Ensure MCP bearer token exists before the UI shows the reveal button.
        SecurePrefs.ensureMcpToken(applicationContext)

        try {
            BrokerService.start(applicationContext)
        } catch (_: Exception) {
            // Guard: never crash UI if bind fails
        }

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        binding.refreshButton.setOnClickListener { refreshStatus() }
        binding.a11yButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.shizukuPermissionButton.setOnClickListener { requestShizukuPermission() }
        binding.dhizukuButton.setOnClickListener { connectDhizuku(userInitiated = true) }
        binding.datawedgeButton.setOnClickListener {
            binding.datawedgeStatusText.text = "DataWedge: querying status/version/profiles…"
            Thread({
                val r = DataWedgeQuery.run(applicationContext, "all", 6000L)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (r.optBoolean("datawedge_replied")) "DataWedge replied" else "No DataWedge reply",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.datawedgeStatusText.text = buildString {
                        append("status: ").append(r.opt("status")).append('\n')
                        append("version: ").append(r.opt("version").toString().take(160)).append('\n')
                        append("active: ").append(r.opt("active_profile")).append('\n')
                        append("profiles: ").append(r.opt("profiles").toString().take(400))
                    }
                }
            }, "DwQueryUi").start()
        }
        binding.saveUpstreamButton.setOnClickListener { saveUpstreamUrl() }
        binding.mcpTokenButton.setOnClickListener { toggleMcpToken() }
        binding.mcpTokenButton.setOnLongClickListener {
            rotateMcpToken()
            true
        }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }

        refreshStatus()
        // On start: init Dhizuku and, if not yet granted, request permission once per process so the
        // broker appears in Dhizuku's app list for the user to approve.
        if (!dhizukuAutoRequested) {
            dhizukuAutoRequested = true
            connectDhizuku(userInitiated = false)
        }
        // Prompt once per process for battery exemption (non-blocking; user can dismiss).
        if (!batteryPromptShown) {
            batteryPromptShown = true
            maybePromptBatteryExemption()
        }
    }

    private fun saveUpstreamUrl() {
        val raw = binding.upstreamUrlInput.text?.toString().orEmpty().trim()
        try {
            SecurePrefs.setUpstreamMcpUrl(applicationContext, raw)
            val host = if (raw.isEmpty()) "(cleared — set a Tailscale/tunnel URL)" else raw
            Toast.makeText(this, "Upstream saved: $host", Toast.LENGTH_LONG).show()
            // Optional: if user pasted a 192.168 address as primary, warn.
            if (raw.contains("192.168.") || raw.contains("10.0.") ) {
                Toast.makeText(
                    this,
                    "Warning: that looks LAN-only. Prefer Tailscale 100.x or an https tunnel as primary; put 192.168 in LAN fallback.",
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshStatus()
            // Nudge notification text
            BrokerService.start(applicationContext)
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid URL: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleMcpToken() {
        if (tokenVisible) {
            tokenVisible = false
            binding.mcpTokenText.text = getString(R.string.mcp_token_hidden)
            binding.mcpTokenButton.text = getString(R.string.reveal_mcp_token)
            return
        }
        val token = SecurePrefs.ensureMcpToken(applicationContext)
        tokenVisible = true
        binding.mcpTokenText.text = token
        binding.mcpTokenButton.text = getString(R.string.hide_mcp_token)
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("mcp_token", token))
        Toast.makeText(this, "MCP token copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun rotateMcpToken() {
        AlertDialog.Builder(this)
            .setTitle("Rotate MCP token?")
            .setMessage("Any Termux scripts / MCP clients using the old token will stop working until you update them.")
            .setPositiveButton("Rotate") { _, _ ->
                val token = SecurePrefs.rotateMcpToken(applicationContext)
                tokenVisible = true
                binding.mcpTokenText.text = token
                binding.mcpTokenButton.text = getString(R.string.hide_mcp_token)
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("mcp_token", token))
                Toast.makeText(this, "New MCP token generated and copied", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already ignoring battery optimizations", Toast.LENGTH_SHORT).show()
            refreshStatus()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun maybePromptBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        // Soft toast only — tapping the dedicated button opens the system dialog.
        Toast.makeText(
            this,
            "Tip: tap “Ignore battery optimizations” so the MCP server survives Doze",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun connectDhizuku(userInitiated: Boolean) {
        val result = DhizukuGateway.requestPermission(applicationContext) { _ ->
            runOnUiThread { refreshStatus() }
        }
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

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    private fun requestShizukuPermission() {
        when (val result = ShizukuGateway.requestPermission(REQUEST_CODE_SHIZUKU)) {
            PermissionRequestResult.AlreadyGranted -> refreshStatus()
            PermissionRequestResult.Requested -> Toast.makeText(
                this,
                "Shizuku permission request sent.",
                Toast.LENGTH_SHORT
            ).show()
            PermissionRequestResult.BinderUnavailable -> Toast.makeText(
                this,
                "Shizuku service is not connected.",
                Toast.LENGTH_LONG
            ).show()
            PermissionRequestResult.RationaleRequired -> Toast.makeText(
                this,
                "Enable permission from the Shizuku app, then try again.",
                Toast.LENGTH_LONG
            ).show()
            is PermissionRequestResult.Failed -> Toast.makeText(
                this,
                "Permission request failed: ${result.cause?.message ?: "unknown"}",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val scopes = admin.getActiveScopes()
        val server = BrokerServer.get()
        val serverStatus = when {
            server == null -> "not started"
            server.isRunning -> "listening ${BrokerServer.HOST}:${server.port} (MCP)"
            else -> "stopped"
        }
        val a11y = if (AgentAccessibilityService.isEnabled()) "enabled" else "disabled"

        val shizuku = ShizukuGateway.status()
        val binder = if (shizuku.binderAlive) "CONNECTED" else "NOT CONNECTED"
        val perm = if (shizuku.permissionGranted) "GRANTED" else "NOT GRANTED"
        val backend = when (shizuku.backendUid) {
            0 -> "root (uid 0)"
            2000 -> "ADB shell (uid 2000)"
            null -> "not available"
            else -> "uid ${shizuku.backendUid}"
        }

        binding.statusText.text = getString(
            R.string.status_template,
            packageName,
            serverStatus,
            a11y,
            scopes.size,
            binder,
            perm,
            backend
        )
        val dz = when {
            !DhizukuGateway.isInstalled(this) -> "not installed"
            DhizukuGateway.isGranted() -> "GRANTED (owner ${DhizukuGateway.ownerComponent()?.packageName ?: "?"})"
            else -> "NOT GRANTED (${DhizukuGateway.lastPermissionResult})"
        }
        binding.statusText.append(getString(R.string.dhizuku_status_line, dz))
        val pm = getSystemService(PowerManager::class.java)
        val batt = if (pm?.isIgnoringBatteryOptimizations(packageName) == true) "exempt" else "not exempt"
        binding.statusText.append("\nbattery opt: $batt · MCP auth: required")
        binding.dhizukuButton.isEnabled = !DhizukuGateway.isGranted()
        binding.scopesText.text = if (scopes.isEmpty()) {
            getString(R.string.no_scopes)
        } else {
            scopes.joinToString("\n")
        }
        binding.shizukuPermissionButton.isEnabled =
            shizuku.binderAlive && !shizuku.permissionGranted
        binding.datawedgeStatusText.text = DataWedgeResultReceiver.lastStatusString.get()
        if (!tokenVisible) {
            binding.mcpTokenText.text = getString(R.string.mcp_token_hidden)
        }
        if (!binding.upstreamUrlInput.hasFocus()) {
            binding.upstreamUrlInput.setText(SecurePrefs.getUpstreamMcpUrl(applicationContext))
        }
    }

    companion object {
        private const val REQUEST_CODE_SHIZUKU = 1001
        @Volatile private var dhizukuAutoRequested = false
        @Volatile private var batteryPromptShown = false
    }
}
