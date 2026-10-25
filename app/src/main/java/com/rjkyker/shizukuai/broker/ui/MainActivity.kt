package com.rjkyker.shizukuai.broker.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rjkyker.shizukuai.broker.R
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.admin.DelegatedAdminActionHandler
import com.rjkyker.shizukuai.broker.bridge.PermissionRequestResult
import com.rjkyker.shizukuai.broker.bridge.ShizukuGateway
import com.rjkyker.shizukuai.broker.databinding.ActivityMainBinding
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeApi
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.service.BrokerService
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeQuery
import com.rjkyker.shizukuai.broker.barcode.BarcodeCommandRuntime
import com.rjkyker.shizukuai.broker.barcode.BarcodeLabActivity
import com.rjkyker.shizukuai.broker.barcode.BarcodeExperienceActivity
import com.rjkyker.shizukuai.broker.barcode.CustomBarcodeCommand
import com.rjkyker.shizukuai.broker.dhizuku.DhizukuGateway
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var admin: DelegatedAdminActionHandler

    private val barcodeCommandListener: (CustomBarcodeCommand) -> Unit = { command ->
        val launch = Intent(this, BarcodeExperienceActivity::class.java).apply {
            putExtra(BarcodeExperienceActivity.EXTRA_TYPE, command.type)
            putExtra(BarcodeExperienceActivity.EXTRA_ID, command.id)
            putExtra(BarcodeExperienceActivity.EXTRA_VERSION, command.version)
        }
        startActivity(launch)
    }

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
        binding.barcodeLabButton.setOnClickListener {
            startActivity(Intent(this, BarcodeLabActivity::class.java))
        }
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
        refreshStatus()
        // On start: init Dhizuku and, if not yet granted, request permission once per process so the
        // broker appears in Dhizuku's app list for the user to approve.
        if (!dhizukuAutoRequested) {
            dhizukuAutoRequested = true
            connectDhizuku(userInitiated = false)
        }
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

    override fun onStart() {
        super.onStart()
        BarcodeCommandRuntime.attach(barcodeCommandListener)
        BarcodeCommandRuntime.consumePending(applicationContext)?.let(barcodeCommandListener)
    }

    override fun onStop() {
        BarcodeCommandRuntime.detach(barcodeCommandListener)
        super.onStop()
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
            server.isRunning -> "listening ${BrokerServer.HOST}:${server.port}"
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
        binding.dhizukuButton.isEnabled = !DhizukuGateway.isGranted()
        binding.scopesText.text = if (scopes.isEmpty()) {
            getString(R.string.no_scopes)
        } else {
            scopes.joinToString("\n")
        }
        binding.shizukuPermissionButton.isEnabled =
            shizuku.binderAlive && !shizuku.permissionGranted
        binding.datawedgeStatusText.text = DataWedgeResultReceiver.lastStatusString.get()
    }

    companion object {
        private const val REQUEST_CODE_SHIZUKU = 1001
        @Volatile private var dhizukuAutoRequested = false
    }
}
