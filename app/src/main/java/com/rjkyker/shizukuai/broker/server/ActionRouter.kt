package com.rjkyker.shizukuai.broker.server

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.admin.DelegatedAdminActionHandler
import com.rjkyker.shizukuai.broker.action.ActionAllowList
import com.rjkyker.shizukuai.broker.bridge.ShizukuBridge
import com.rjkyker.shizukuai.broker.action.RiskLevel
import com.rjkyker.shizukuai.broker.dhizuku.DhizukuGateway
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeApi
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeQuery
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultStore
import com.rjkyker.shizukuai.broker.cli.AdminCliService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routes JSON actions:
 * {op: tap|swipe|text|key|ui_dump|shell|admin_*|launch|datawedge_*}
 * Prefer AccessibilityService when available; else ShizukuBridge / DelegatedAdmin / intents.
 */
class ActionRouter(private val context: Context) {

    private val admin by lazy { DelegatedAdminActionHandler(context) }

    fun route(body: JSONObject): JSONObject {
        val rawOp = body.optString("op", body.optString("action", "")).trim()
        val op = rawOp.lowercase()
        if (rawOp.isNotEmpty() && !ActionAllowList.isAllowed(rawOp) && !ActionAllowList.isAllowed(op)) {
            return err("action not allow-listed: $rawOp")
        }
        return try {
            when (op) {
                "tap" -> handleTap(body)
                "swipe" -> handleSwipe(body)
                "text" -> handleText(body)
                "key" -> handleKey(body)
                "ui_dump", "uidump", "snapshot" -> handleUiDump(body)
                "shell" -> handleShell(body)
                "admin_scopes", "scopes" -> handleAdminScopes()
                "admin_perm", "perm" -> handleAdminPerm(body)
                "admin_suspend", "suspend" -> handleAdminSuspend(body)
                "launch" -> handleLaunch(body)
                "datawedge_status" -> DataWedgeApi.getStatus(context)
                "datawedge_version" -> DataWedgeApi.getVersionInfo(context)
                "datawedge_profiles" -> DataWedgeApi.getProfilesList(context)
                "datawedge_active_profile" -> DataWedgeApi.getActiveProfile(context)
                "datawedge_soft_scan" -> {
                    val mode = body.optString("mode", body.optString("trigger", "start"))
                    DataWedgeApi.softScan(context, mode)
                }
                "datawedge_switch_profile" -> {
                    val name = body.optString("profile", body.optString("name", ""))
                    DataWedgeApi.switchToProfile(context, name)
                }
                "datawedge_create_profile" -> {
                    val name = body.optString("profile", body.optString("name", "ShizukuAiBroker"))
                    DataWedgeApi.createProfile(context, name)
                }
                "datawedge_set_config" -> {
                    val name = body.optString("profile", body.optString("name", "ShizukuAiBroker"))
                    DataWedgeApi.setMinimalBroadcastProfile(context, name)
                }
                "datawedge_last" -> DataWedgeResultReceiver.snapshot()
                "datawedge_query" -> {
                    val q = body.optString("query", body.optString("q", "all"))
                    val t = body.optLong("timeoutMs", 6000L).coerceIn(500L, 10_000L)
                    DataWedgeQuery.run(context, q, t)
                }
                "datawedge_results" -> DataWedgeResultStore.readAll(context).put("ok", true)
                "dhizuku_status", "dhizuku_connect", "dhizuku_scopes", "dhizuku_add_restriction",
                "dhizuku_clear_restriction", "dhizuku_hide_app", "dhizuku_suspend_app",
                "dhizuku_perm", "dhizuku_set_scopes" -> handleDhizuku(op, body)
                else -> err("unknown op: $op")
            }
        } catch (e: Exception) {
            Log.e(TAG, "route failed op=$op", e)
            err(e.message ?: "route error")
        }
    }

    private fun handleTap(body: JSONObject): JSONObject {
        val a11y = AgentAccessibilityService.instance
        val text = body.optString("text", "")
        if (text.isNotEmpty() && a11y != null) {
            val ok = a11y.clickByText(text)
            return JSONObject().put("ok", ok).put("via", "a11y_text")
        }
        val x = body.optDouble("x", Double.NaN)
        val y = body.optDouble("y", Double.NaN)
        if (!x.isNaN() && !y.isNaN()) {
            if (a11y != null) {
                val ok = a11y.tap(x.toFloat(), y.toFloat())
                return JSONObject().put("ok", ok).put("via", "a11y_gesture")
            }
            val (code, out) = ShizukuBridge.exec("input tap ${x.toInt()} ${y.toInt()}")
            return JSONObject().put("ok", code == 0).put("code", code).put("stdout", out).put("via", "shell")
        }
        return err("tap requires text or x/y")
    }

    private fun handleSwipe(body: JSONObject): JSONObject {
        val x1 = body.optDouble("x1", Double.NaN)
        val y1 = body.optDouble("y1", Double.NaN)
        val x2 = body.optDouble("x2", Double.NaN)
        val y2 = body.optDouble("y2", Double.NaN)
        val dur = body.optLong("durationMs", body.optLong("duration", 300L))
        if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) {
            return err("swipe requires x1,y1,x2,y2")
        }
        val a11y = AgentAccessibilityService.instance
        if (a11y != null) {
            val ok = a11y.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), dur)
            return JSONObject().put("ok", ok).put("via", "a11y")
        }
        val (code, out) = ShizukuBridge.exec(
            "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $dur"
        )
        return JSONObject().put("ok", code == 0).put("code", code).put("stdout", out).put("via", "shell")
    }

    private fun handleText(body: JSONObject): JSONObject {
        val text = body.optString("text", body.optString("value", ""))
        if (text.isEmpty()) return err("text requires text/value")
        val a11y = AgentAccessibilityService.instance
        if (a11y != null) {
            val ok = a11y.inputText(text)
            if (ok) return JSONObject().put("ok", true).put("via", "a11y")
        }
        val escaped = text.replace("'", "'\\''")
        val (code, out) = ShizukuBridge.exec("input text '$escaped'")
        return JSONObject().put("ok", code == 0).put("code", code).put("stdout", out).put("via", "shell")
    }

    private fun handleKey(body: JSONObject): JSONObject {
        val key = body.optString("key", body.optString("keycode", "")).lowercase()
        val a11y = AgentAccessibilityService.instance
        when (key) {
            "back" -> {
                if (a11y != null) {
                    return JSONObject().put("ok", a11y.back()).put("via", "a11y")
                }
                val (code, out) = ShizukuBridge.exec("input keyevent 4")
                return JSONObject().put("ok", code == 0).put("code", code).put("stdout", out).put("via", "shell")
            }
            "home" -> {
                if (a11y != null) {
                    return JSONObject().put("ok", a11y.home()).put("via", "a11y")
                }
                val (code, out) = ShizukuBridge.exec("input keyevent 3")
                return JSONObject().put("ok", code == 0).put("code", code).put("stdout", out).put("via", "shell")
            }
            else -> {
                val codeNum = body.optInt("keycode", key.toIntOrNull() ?: -1)
                if (codeNum < 0) return err("key requires key=back|home or keycode")
                val (code, out) = ShizukuBridge.exec("input keyevent $codeNum")
                return JSONObject().put("ok", code == 0).put("code", code).put("stdout", out).put("via", "shell")
            }
        }
    }

    private fun handleUiDump(body: JSONObject): JSONObject {
        val a11y = AgentAccessibilityService.instance
        if (a11y != null) {
            val max = body.optInt("maxNodes", 200)
            return a11y.snapshot(max).put("via", "a11y")
        }
        val (code, out) = ShizukuBridge.exec("uiautomator dump /dev/tty 2>/dev/null || uiautomator dump")
        return JSONObject()
            .put("ok", code == 0)
            .put("code", code)
            .put("stdout", out)
            .put("via", "shell")
    }

    private fun handleShell(body: JSONObject): JSONObject {
        if (!body.optBoolean("confirm", false)) {
            return err("shell is HIGH risk; resend with \"confirm\": true")
        }
        val cmd = body.optString("cmd", body.optString("command", ""))
        if (cmd.isEmpty()) return err("shell requires cmd")
        val r = ShizukuBridge.execSeparate(cmd)
        return JSONObject()
            .put("ok", r.exitCode == 0)
            .put("code", r.exitCode)
            .put("exit_code", r.exitCode)
            .put("stdout", r.stdout)
            .put("stderr", r.stderr)
            .put("via", r.via)
            .put("timed_out", r.timedOut)
    }

    private fun handleAdminScopes(): JSONObject {
        val scopes = admin.getActiveScopes()
        val arr = JSONArray()
        scopes.forEach { arr.put(it) }
        return JSONObject().put("ok", true).put("count", scopes.size).put("scopes", arr)
    }

    private fun handleAdminPerm(body: JSONObject): JSONObject {
        val pkg = body.optString("package", body.optString("pkg", ""))
        val permission = body.optString("permission", body.optString("perm", ""))
        val stateRaw = body.optString("state", "default")
        if (pkg.isEmpty() || permission.isEmpty()) return err("admin_perm requires package + permission")
        val state = AdminCliService.mapGrantState(stateRaw)
        val ok = admin.setPermissionGrantState(pkg, permission, state)
        return JSONObject().put("ok", ok).put("package", pkg).put("permission", permission).put("state", stateRaw)
    }

    private fun handleAdminSuspend(body: JSONObject): JSONObject {
        val pkg = body.optString("package", body.optString("pkg", ""))
        if (pkg.isEmpty()) return err("admin_suspend requires package")
        val suspended = body.optBoolean("suspended", true)
        val failed = admin.setPackagesSuspended(arrayOf(pkg), suspended)
        val ok = !failed.contains(pkg)
        return JSONObject().put("ok", ok).put("package", pkg).put("suspended", suspended)
    }

    private fun handleDhizuku(op: String, body: JSONObject): JSONObject {
        // HIGH-risk Dhizuku ops require an explicit confirm flag; risk comes from ActionAllowList only.
        if (ActionAllowList.riskLevelFor(op) == RiskLevel.HIGH && !body.optBoolean("confirm", false)) {
            return err("$op is HIGH risk; resend with \"confirm\": true")
        }
        val pkg = body.optString("package", body.optString("pkg", ""))
        return when (op) {
            "dhizuku_status" -> DhizukuGateway.status(context)
            "dhizuku_connect" -> DhizukuGateway.connect(context)
            "dhizuku_scopes" -> DhizukuGateway.getScopes()
            "dhizuku_add_restriction" -> DhizukuGateway.setRestriction(context, body.optString("restriction"), true)
            "dhizuku_clear_restriction" -> DhizukuGateway.setRestriction(context, body.optString("restriction"), false)
            "dhizuku_hide_app" -> DhizukuGateway.setHidden(context, pkg, body.optBoolean("hidden", true))
            "dhizuku_suspend_app" -> DhizukuGateway.setSuspended(context, pkg, body.optBoolean("suspended", true))
            "dhizuku_perm" -> {
                val permission = body.optString("permission", body.optString("perm", ""))
                if (pkg.isEmpty() || permission.isEmpty()) return err("dhizuku_perm requires package + permission")
                DhizukuGateway.setPermissionGrantState(
                    context, pkg, permission, AdminCliService.mapGrantState(body.optString("state", "default"))
                )
            }
            "dhizuku_set_scopes" -> {
                val arr = body.optJSONArray("scopes") ?: return err("dhizuku_set_scopes requires scopes[]")
                DhizukuGateway.setScopes((0 until arr.length()).map { arr.getString(it) })
            }
            else -> err("unknown dhizuku op: $op")
        }
    }

    private fun handleLaunch(body: JSONObject): JSONObject {
        val pkg = body.optString("package", body.optString("pkg", ""))
        val action = body.optString("action", "")
        val data = body.optString("data", "")
        return try {
            val intent = when {
                pkg.isNotEmpty() -> {
                    context.packageManager.getLaunchIntentForPackage(pkg)
                        ?: Intent().setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                action.isNotEmpty() -> Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                else -> return err("launch requires package or action")
            }
            if (data.isNotEmpty()) intent.data = Uri.parse(data)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            JSONObject().put("ok", true).put("package", pkg).put("action", action)
        } catch (e: Exception) {
            err("launch failed: ${e.message}")
        }
    }

    private fun err(msg: String): JSONObject =
        JSONObject().put("ok", false).put("error", msg)

    companion object {
        private const val TAG = "ActionRouter"

        fun openAccessibilitySettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
