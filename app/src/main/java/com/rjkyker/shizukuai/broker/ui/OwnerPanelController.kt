package com.rjkyker.shizukuai.broker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rjkyker.shizukuai.broker.databinding.ActivityMainBinding
import com.rjkyker.shizukuai.broker.delegation.BrokerDelegationService
import com.rjkyker.shizukuai.broker.delegation.DelegationRegistry
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyCatalog
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyCatalog.Tier
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyEngine
import com.rjkyker.shizukuai.broker.priv.PrivServiceClient
import org.json.JSONObject

/** 1.8.8 dashboard section: OwnDroid-parity policies, privileged server, delegated clients. */
class OwnerPanelController(
    private val act: AppCompatActivity,
    private val b: ActivityMainBinding,
    private val io: (() -> Unit) -> Unit,
) {
    fun setup() {
        b.ownerProbeButton.setOnClickListener { runOp("probe", JSONObject()) }
        b.modConsoleButton.setOnClickListener {
            act.startActivity(android.content.Intent(act, com.rjkyker.shizukuai.broker.ui.mod.ModConsoleActivity::class.java))
        }
        b.ownerOpButton.setOnClickListener { pickOp() }
        b.privStartButton.setOnClickListener { io { show(PrivServiceClient.start(act.applicationContext)) } }
        b.privStopButton.setOnClickListener { io { show(PrivServiceClient.stop()) } }
        b.privAdbButton.setOnClickListener {
            io {
                runCatching { PrivServiceClient.writeScript(act.applicationContext) }
                val cmd = PrivServiceClient.adbCommand(act.applicationContext)
                act.runOnUiThread {
                    act.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("priv bootstrap", cmd))
                    Toast.makeText(act, "Copied: $cmd", Toast.LENGTH_LONG).show()
                    b.ownerText.text = "$cmd\n\n${PrivServiceClient.HONEST_NOTE}"
                }
            }
        }
        b.delegationButton.setOnClickListener { manageClients() }
    }

    private fun show(o: JSONObject) = act.runOnUiThread {
        b.ownerText.text = o.toString(2).take(6000)
    }

    private fun runOp(op: String, args: JSONObject, confirmed: Boolean = false) = io {
        show(OwnerPolicyEngine.run(act.applicationContext, op, args, OwnerPolicyCatalog.Caller.DASHBOARD, confirmed))
    }

    private fun pickOp() {
        val ops = OwnerPolicyCatalog.OPS.keys.toList()
        val labels = ops.map { "${it}  [${OwnerPolicyCatalog.OPS[it]}]" }.toTypedArray()
        AlertDialog.Builder(act).setTitle("Policy op (via Dhizuku owner)")
            .setItems(labels) { _, i -> editArgs(ops[i]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun template(op: String): String = ownerArgTemplate(op)

    private fun editArgs(op: String) {
        val tier = OwnerPolicyCatalog.OPS[op] ?: return
        val input = EditText(act).apply { setText(template(op)); textSize = 12f }
        AlertDialog.Builder(act).setTitle("$op [$tier] args (JSON)").setView(input)
            .setPositiveButton("Next") { _, _ ->
                val args = runCatching { JSONObject(input.text.toString()) }.getOrElse {
                    Toast.makeText(act, "bad JSON", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                when (tier) {
                    Tier.READ, Tier.MEDIUM -> runOp(op, args)
                    Tier.HIGH -> confirm(op, args, "HIGH-risk device-wide change. Apply?", typed = false)
                    Tier.LOCKOUT -> confirm(op, args,
                        "LOCKOUT RISK: this can cut off ADB, the camera, Tailscale/VPN, accessibility or input. " +
                            "Type the op name to apply.", typed = true)
                    Tier.FORBIDDEN -> Unit
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirm(op: String, args: JSONObject, msg: String, typed: Boolean) {
        val field = EditText(act).apply { hint = op }
        val d = AlertDialog.Builder(act).setTitle("Confirm $op").setMessage("$msg\n\n$args")
            .setPositiveButton("Apply") { _, _ ->
                if (typed && field.text.toString().trim() != op) {
                    Toast.makeText(act, "Not applied: name did not match", Toast.LENGTH_SHORT).show()
                } else runOp(op, args, confirmed = true)
            }.setNegativeButton("Cancel", null)
        if (typed) d.setView(field)
        d.show()
    }

    fun manageClients() = io {
        val reg = BrokerDelegationService.registry(act.applicationContext)
        val clients = reg.list()
        act.runOnUiThread {
            if (clients.isEmpty()) {
                AlertDialog.Builder(act).setTitle("Delegated client apps")
                    .setMessage("No client has requested access. Apps bind com.rjkyker.shizukuai.broker.DELEGATION and call requestConsent().")
                    .setPositiveButton("OK", null).show()
                return@runOnUiThread
            }
            val labels = clients.map { "${it.pkg}  [${it.state}]" }.toTypedArray()
            AlertDialog.Builder(act).setTitle("Delegated client apps")
                .setItems(labels) { _, i -> decide(reg, clients[i]) }
                .setNegativeButton("Close", null).show()
        }
    }

    private fun decide(reg: DelegationRegistry, c: DelegationRegistry.Client) {
        AlertDialog.Builder(act).setTitle(c.pkg)
            .setMessage("State: ${c.state}\nSigner SHA-256: ${c.sig.take(16)}…\n\nAllowed ops if approved: " +
                OwnerPolicyCatalog.CLIENT_OPS.sorted().joinToString(", ") + "\n(HIGH / LOCKOUT ops are never delegated.)")
            .setPositiveButton("Approve") { _, _ -> io { reg.approve(c.pkg, System.currentTimeMillis()); show(reg.toJson()) } }
            .setNeutralButton("Revoke") { _, _ -> io { reg.revoke(c.pkg); show(reg.toJson()) } }
            .setNegativeButton("Deny") { _, _ -> io { reg.deny(c.pkg, System.currentTimeMillis()); show(reg.toJson()) } }
            .show()
    }
}

/** Starter JSON args per owner_policy op (shared by the dashboard panel and the 1.9.0 Mod Console). */
fun ownerArgTemplate(op: String): String = when (op) {
    "app_info", "enable_system_app", "install_existing", "clear_app_storage", "default_dialer" -> """{"package":""}"""
    "hide", "suspend", "metered_data", "user_control", "block_uninstall" -> """{"package":"","value":true}"""
    "permission" -> """{"package":"","permission":"android.permission.CAMERA","state":"granted"}"""
    "restriction" -> """{"restriction":"no_config_bluetooth","value":true}"""
    "lockout_restriction" -> """{"restriction":"no_debugging_features","value":false}"""
    "lock_screen_info", "organization_name", "support_message" -> """{"text":""}"""
    "permission_policy" -> """{"policy":"prompt"}"""
    "system_update_policy" -> """{"policy":"postpone"}"""
    "private_dns" -> """{"mode":"opportunistic"}"""
    "global_proxy" -> """{"host":"","port":8080}"""
    "max_time_to_lock" -> """{"ms":0}"""
    "keyguard_features" -> """{"flags":0}"""
    "delegate_scopes" -> """{"package":"","scopes":["delegation-package-access"]}"""
    "lock_task_packages", "permitted_accessibility", "permitted_input_methods" -> """{"packages":[]}"""
    "always_on_vpn" -> """{"package":"","lockdown":false}"""
    "probe", "features", "restrictions", "lock_task_get", "system_update_get", "network_get",
    "password_info", "users", "delegates_get", "lock_now", "reboot" -> "{}"
    else -> """{"value":true}"""
}
