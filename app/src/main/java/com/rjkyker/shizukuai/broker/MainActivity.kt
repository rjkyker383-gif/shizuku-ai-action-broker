package com.rjkyker.shizukuai.broker

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var requestButton: Button

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshStatus() }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQUEST_CODE) {
            runOnUiThread { refreshStatus() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    private fun buildContentView(): ScrollView {
        val padding = (20 * resources.displayMetrics.density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        content.addView(TextView(this).apply {
            text = "Shizuku AI Action Broker"
            textSize = 24f
        })

        content.addView(TextView(this).apply {
            text = "Permissioned, allow-listed Android actions. Arbitrary shell execution is not exposed."
            textSize = 15f
        })

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, padding, 0, padding / 2)
        }
        content.addView(statusView)

        requestButton = Button(this).apply {
            text = "Request Shizuku permission"
            setOnClickListener {
                val requested = ShizukuGateway.requestPermission(REQUEST_CODE)
                if (!requested) {
                    Toast.makeText(
                        this@MainActivity,
                        "Shizuku is unavailable or permission must be enabled in the Shizuku app.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshStatus()
            }
        }
        content.addView(requestButton)

        content.addView(TextView(this).apply {
            setPadding(0, padding, 0, 0)
            text = buildString {
                appendLine("Current allow-list:")
                ActionAllowList.snapshot().sorted().forEach { action ->
                    appendLine("• $action")
                }
            }.trimEnd()
            textSize = 15f
        })

        return ScrollView(this).apply {
            addView(content)
        }
    }

    private fun refreshStatus() {
        val status = ShizukuGateway.status()
        val backend = when (status.backendUid) {
            0 -> "root (uid 0)"
            2000 -> "ADB shell (uid 2000)"
            null -> "not available"
            else -> "uid ${status.backendUid}"
        }

        statusView.text = buildString {
            appendLine("Binder: ${if (status.binderAlive) "CONNECTED" else "NOT CONNECTED"}")
            appendLine("Permission: ${if (status.permissionGranted) "GRANTED" else "NOT GRANTED"}")
            append("Backend: $backend")
        }

        requestButton.isEnabled = status.binderAlive && !status.permissionGranted
    }

    private companion object {
        const val REQUEST_CODE = 1001
    }
}
