package com.rjkyker.shizukuai.broker.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.rjkyker.shizukuai.broker.autonomy.delivery.SendGate
import com.rjkyker.shizukuai.broker.autonomy.delivery.SendPolicy
import java.util.concurrent.Executors

/**
 * Per-message approval screen (exported=false; reachable only from our own notification/dashboard).
 * FLAG_SECURE (no screenshots / screen capture, including the broker's own a11y screenshot tool),
 * obscured-touch rejection and an a11y-proof [GuardedButton]. Approval opens the target app's compose
 * screen with the text; the owner still presses send there.
 */
class SendConfirmActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); filterTouchesWhenObscured = true }
        val info = TextView(this).apply { textSize = 16f }
        val approve = GuardedButton(this).apply { text = "Approve — open in app"; isEnabled = false }
        val decline = Button(this).apply { text = "Decline" }
        root.addView(ScrollView(this).apply { addView(info) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(approve, LinearLayout.LayoutParams(-1, -2))
        root.addView(decline, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        io.execute {
            val o = SendGate.get(this, id)
            runOnUiThread {
                if (o == null) { info.text = "Message not found."; return@runOnUiThread }
                info.text = buildString {
                    append("Send this message?\n\nApp: ${o.targetPkg}\nTo: ${o.recipient}\nRequested by: ${o.actor}\nState: ${o.state}\n\n")
                    append("———\n${o.body}\n———\n\n")
                    append("Approving opens ${o.targetPkg} with this text. Pick ${o.recipient} there and press send yourself.")
                }
                info.gravity = Gravity.START
                if (o.state == SendPolicy.PENDING_CONFIRM) {
                    approve.rearm()
                    approve.postDelayed({ approve.isEnabled = true }, approve.armDelayMs)
                }
            }
        }
        approve.onGenuineClick = {
            approve.isEnabled = false
            io.execute {
                val intent = SendGate.approveFromDevice(this, id)
                runOnUiThread {
                    if (intent == null) { Toast.makeText(this, "Blocked by send gate re-check (see audit).", Toast.LENGTH_LONG).show(); finish() }
                    else {
                        runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, "Could not open app: ${it.message}", Toast.LENGTH_LONG).show() }
                        finish()
                    }
                }
            }
        }
        decline.setOnClickListener { io.execute { SendGate.declineFromDevice(this, id); runOnUiThread { finish() } } }
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    companion object { const val EXTRA_ID = "outbox_id" }
}
