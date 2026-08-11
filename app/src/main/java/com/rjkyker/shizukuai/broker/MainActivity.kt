package com.rjkyker.shizukuai.broker

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var permissionButton: Button

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshStatus() }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == ShizukuGateway.REQUEST_CODE) {
            runOnUiThread { refreshStatus() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            textSize = 18f
        }

        permissionButton = Button(this).apply {
            text = "Request Shizuku permission"
            setOnClickListener {
                ShizukuGateway.requestPermission()
                refreshStatus()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(
                statusView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                permissionButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(layout)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
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

    private fun refreshStatus() {
        val binderAvailable = ShizukuGateway.isBinderAvailable()
        val hasPermission = ShizukuGateway.hasPermission()

        statusView.text = ShizukuGateway.statusText()
        permissionButton.isEnabled = binderAvailable && !hasPermission
    }
}
