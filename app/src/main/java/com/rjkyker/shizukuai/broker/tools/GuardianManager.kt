package com.rjkyker.shizukuai.broker.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.database.ContentObserver
import android.net.Uri
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rjkyker.shizukuai.broker.shell.ShellExecutor
import com.rjkyker.shizukuai.broker.autonomy.audit.AuditLog
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import org.json.JSONArray
import org.json.JSONObject

internal data class GuardianPin(
    val id: String,
    val namespace: String,
    val key: String,
    val value: String,
    val failures: Int = 0,
    val nextAt: Long = 0L,
    val suspended: Boolean = false,
    val status: String = "pinned"
)

/** Small durable owner-controlled registry. Values are local-only and never sent to MCP. */
internal object GuardianStore {
    private const val PREFS = "owner_guardians"
    private const val PINS = "pins"
    private const val LOGS = "logs"
    private const val ENABLED = "enabled"

    @Synchronized fun all(context: Context): List<GuardianPin> {
        val a = JSONArray(context.getSharedPreferences(PREFS, 0).getString(PINS, "[]"))
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            GuardianPin(o.getString("id"), o.getString("namespace"), o.getString("key"), o.getString("value"),
                o.optInt("failures"), o.optLong("nextAt"), o.optBoolean("suspended"), o.optString("status", "pinned"))
        }.getOrNull() }
    }
    @Synchronized fun save(context: Context, pins: List<GuardianPin>) {
        val a = JSONArray()
        pins.forEach { p -> a.put(JSONObject().put("id", p.id).put("namespace", p.namespace).put("key", p.key)
            .put("value", p.value).put("failures", p.failures).put("nextAt", p.nextAt).put("suspended", p.suspended).put("status", p.status)) }
        context.getSharedPreferences(PREFS, 0).edit().putString(PINS, a.toString()).apply()
    }
    fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, 0).edit().putBoolean(ENABLED, value).apply() }
    fun pin(context: Context, namespace: String, key: String, value: String): String? {
        val ns = namespace.lowercase()
        GuardianPolicy.validate(ns, key, value)?.let { return it }
        val pins = all(context).filterNot { it.namespace == ns && it.key == key }.toMutableList()
        if (pins.size >= 50) return "Maximum of 50 pinned settings reached"
        pins += GuardianPin(java.util.UUID.randomUUID().toString(), ns, key, value)
        save(context, pins)
        log(context, "PIN", "$ns:$key", "owner pinned value")
        return null
    }
    fun remove(context: Context, id: String) { save(context, all(context).filterNot { it.id == id }); log(context, "REMOVE", id, "pin removed") }
    @Synchronized fun update(context: Context, updated: GuardianPin) { save(context, all(context).map { if (it.id == updated.id) updated else it }) }
    @Synchronized fun log(context: Context, action: String, target: String, result: String) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val old = JSONArray(prefs.getString(LOGS, "[]"))
        val next = JSONArray()
        next.put(JSONObject().put("at", System.currentTimeMillis()).put("action", action).put("target", target).put("result", result.take(220)))
        for (i in 0 until minOf(old.length(), 199)) next.put(old.getJSONObject(i))
        prefs.edit().putString(LOGS, next.toString()).apply()
        val decision = if (action.endsWith("FAIL")) "ERROR" else "ALLOW"
        AuditLog.record(context, Actor.WATCHDOG.name, "settings_guardian", "LOW_WRITE", decision,
            "owner-enabled guardian: $action", outcome = "$target: ${result.take(120)}")
    }
    fun recentLogs(context: Context): List<String> {
        val a = JSONArray(context.getSharedPreferences(PREFS, 0).getString(LOGS, "[]"))
        return (0 until minOf(a.length(), 12)).map { i -> val o = a.getJSONObject(i); "${java.text.DateFormat.getTimeInstance().format(java.util.Date(o.optLong("at")))} ${o.optString("action")} ${o.optString("target")}: ${o.optString("result")}" }
    }
}

internal object GuardianPolicy {
    private val keyPattern = Regex("[A-Za-z0-9_.-]{1,100}")
    private val blockedKeys = listOf("adb_enabled", "adb_wifi_enabled", "development_settings_enabled", "enabled_accessibility_services",
        "accessibility_enabled", "device_provisioned", "user_setup_complete", "oem_unlock", "package_verifier",
        "install_non_market", "lockscreen", "lock_screen", "password", "pattern", "biometric", "debug_app",
        "wait_for_debugger", "hidden_api", "always_finish_activities", "stay_on_while_plugged_in", "sms_",
        "default_sms", "telephony", "airplane_mode_on", "default_input_method", "enabled_input_methods", "device_owner")
    fun validate(namespace: String, key: String, value: String): String? {
        if (namespace !in setOf("global", "system", "secure", "property")) return "Unsupported namespace"
        if (!keyPattern.matches(key)) return "Key format is invalid"
        if (value.length > 256 || value.any { it == '\u0000' || it == '\n' || it == '\r' }) return "Value is too long or contains control characters"
        val lower = key.lowercase()
        if (blockedKeys.any { lower.contains(it) } || (namespace == "property" && listOf("adb", "unlock", "security").any { lower.contains(it) }))
            return "This key is security-sensitive and cannot be guarded"
        if (namespace == "property" && !key.startsWith("persist.rjkyker.")) return "Only app-owned persist.rjkyker.* properties are allowed"
        return null
    }
}

/** Guard engine only touches explicitly pinned keys. Retry budget is 3, then the pin suspends. */
internal object GuardianEngine {
    fun read(context: Context, ns: String, key: String): String? {
        val direct = runCatching {
            when (ns) {
                "global" -> Settings.Global.getString(context.contentResolver, key)
                "system" -> Settings.System.getString(context.contentResolver, key)
                "secure" -> Settings.Secure.getString(context.contentResolver, key)
                "property" -> null
                else -> null
            }
        }.getOrNull()
        return direct ?: readViaShell(ns, key)
    }
    private fun readViaShell(ns: String, key: String): String? {
        if (ns == "property") return shell("getprop ${quote(key)}")
        if (ns !in setOf("global", "system", "secure")) return null
        return shell("settings get $ns ${quote(key)}")?.takeUnless { it == "null" }
    }
    private fun write(context: Context, p: GuardianPin): Boolean {
        val direct = runCatching {
            when (p.namespace) {
                "global" -> Settings.Global.putString(context.contentResolver, p.key, p.value)
                "system" -> Settings.System.putString(context.contentResolver, p.key, p.value)
                "secure" -> Settings.Secure.putString(context.contentResolver, p.key, p.value)
                "property" -> false
                else -> false
            }
        }.getOrDefault(false)
        if (direct) return true
        val cmd = if (p.namespace == "property") "setprop ${quote(p.key)} ${quote(p.value)}"
            else "settings put ${p.namespace} ${quote(p.key)} ${quote(p.value)}"
        val r = ShellExecutor.execSync(cmd, 5)
        return !r.timedOut && r.exitCode == 0
    }
    fun runOnce(context: Context) {
        if (!GuardianStore.isEnabled(context)) return
        val now = System.currentTimeMillis()
        for (p in GuardianStore.all(context)) {
            if (p.suspended || now < p.nextAt) continue
            val current = runCatching { read(context, p.namespace, p.key) }.getOrNull()
            if (current == null) {
                val failed = p.copy(failures = p.failures + 1, nextAt = now + backoff(p.failures + 1), suspended = p.failures + 1 >= MAX_FAILURES, status = "read failed")
                GuardianStore.update(context, failed); GuardianStore.log(context, "READ_FAIL", "${p.namespace}:${p.key}", "access unavailable; ${failed.failures}/$MAX_FAILURES")
                continue
            }
            if (current == p.value) {
                if (p.failures != 0 || p.status != "steady") GuardianStore.update(context, p.copy(failures = 0, nextAt = 0, status = "steady"))
                continue
            }
            val ok = runCatching { write(context, p) }.getOrDefault(false)
            val verify = if (ok) runCatching { read(context, p.namespace, p.key) == p.value }.getOrDefault(false) else false
            if (verify) {
                GuardianStore.update(context, p.copy(failures = 0, nextAt = 0, status = "restored"))
                GuardianStore.log(context, "RESTORE", "${p.namespace}:${p.key}", "restored pinned value")
            } else {
                val n = p.failures + 1
                GuardianStore.update(context, p.copy(failures = n, nextAt = now + backoff(n), suspended = n >= MAX_FAILURES, status = if (n >= MAX_FAILURES) "suspended" else "retry scheduled"))
                GuardianStore.log(context, "RESTORE_FAIL", "${p.namespace}:${p.key}", "attempt $n/$MAX_FAILURES; suspended=${n >= MAX_FAILURES}")
            }
        }
    }
    private fun backoff(attempt: Int) = (30_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(15 * 60_000L)
    private fun shell(command: String): String? {
        val r = ShellExecutor.execSync(command, 5)
        return if (!r.timedOut && r.exitCode == 0) r.stdout.trimEnd() else null
    }
    private fun quote(v: String) = "'" + v.replace("'", "'\\''") + "'"
    private const val MAX_FAILURES = 3
}

class GuardianService : Service() {
    private val thread = HandlerThread("settings-guardian").also { it.start() }
    private val handler = Handler(thread.looper)
    private lateinit var settingsObserver: ContentObserver
    private val loop = object : Runnable {
        override fun run() {
            if (!GuardianStore.isEnabled(applicationContext)) { stopSelf(); return }
            runCatching { GuardianEngine.runOnce(applicationContext) }
            handler.postDelayed(this, CHECK_MS)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        settingsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handler.removeCallbacks(loop)
                handler.post {
                    runCatching { GuardianEngine.runOnce(applicationContext) }
                    handler.postDelayed(loop, CHECK_MS)
                }
            }
        }
        contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, settingsObserver)
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
        contentResolver.registerContentObserver(Settings.Secure.CONTENT_URI, true, settingsObserver)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GuardianStore.setEnabled(this, true)
        startForeground(NOTIFICATION_ID, notification())
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(loop)
        runCatching { contentResolver.unregisterContentObserver(settingsObserver) }
        thread.quitSafely()
        running = false
        super.onDestroy()
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Settings Guardian", NotificationManager.IMPORTANCE_LOW).apply { description = "Visible status for owner-pinned settings" })
    }
    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("Settings Guardian active")
        .setContentText("Monitoring only the settings you pinned. Tap to manage or Stop to pause.")
        .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(PendingIntent.getActivity(this, 40, Intent(this, GuardianActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "STOP", PendingIntent.getBroadcast(this, 41, Intent(this, GuardianStopReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()
    companion object {
        @Volatile var running: Boolean = false
            private set
        private const val CHANNEL = "settings_guardian"
        private const val NOTIFICATION_ID = 1927
        private const val CHECK_MS = 30_000L
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, GuardianService::class.java)) }
        fun ensureRunning(context: Context) { if (GuardianStore.isEnabled(context) && !running) start(context) }
        fun stop(context: Context) { GuardianStore.setEnabled(context, false); context.stopService(Intent(context, GuardianService::class.java)) }
    }
}

class GuardianStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        GuardianService.stop(context)
        GuardianStore.log(context, "STOP", "guardian", "stopped from notification")
    }
}

class GuardianActivity : AppCompatActivity() {
    private lateinit var namespace: Spinner
    private lateinit var key: EditText
    private lateinit var value: EditText
    private lateinit var status: TextView
    private lateinit var pins: LinearLayout
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (14 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        box.addView(TextView(this).apply { text = "SETTINGS GUARDIANS"; textSize = 22f })
        box.addView(TextView(this).apply { text = "Pin only settings you choose. Restoration is bounded to 3 attempts per change. Security-sensitive keys are blocked."; textSize = 12f })
        namespace = Spinner(this).apply { adapter = ArrayAdapter(this@GuardianActivity, android.R.layout.simple_spinner_dropdown_item, listOf("global", "system", "secure", "property")) }
        box.addView(namespace)
        key = EditText(this).apply { hint = "Setting key (e.g. screen_brightness)"; singleLine = true }
        box.addView(key)
        value = EditText(this).apply { hint = "Pinned value"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 1 }
        box.addView(value)
        box.addView(Button(this).apply { text = "Read current value"; setOnClickListener {
            val ns = namespace.selectedItem.toString(); val k = key.text.toString().trim()
            Thread { val v = runCatching { GuardianEngine.read(this@GuardianActivity, ns, k) }.getOrNull(); runOnUiThread { if (v == null) toast("Read failed: check namespace, key, and Shizuku permission") else value.setText(v) } }.start()
        } })
        box.addView(Button(this).apply { text = "Pin this value"; setOnClickListener {
            val err = GuardianStore.pin(this@GuardianActivity, namespace.selectedItem.toString(), key.text.toString().trim(), value.text.toString())
            if (err != null) toast(err) else { toast("Pinned locally"); renderPins() }
        } })
        box.addView(Button(this).apply { text = "Start foreground guardian"; setOnClickListener {
            if (GuardianStore.all(this@GuardianActivity).isEmpty()) toast("Pin at least one setting first") else runCatching { GuardianService.start(this@GuardianActivity) }.onFailure { toast("Start blocked: ${it.message}") }
            renderStatus()
        } })
        box.addView(Button(this).apply { text = "Stop guardian"; setOnClickListener { GuardianService.stop(this@GuardianActivity); GuardianStore.setEnabled(this@GuardianActivity, false); renderStatus() } })
        status = TextView(this).apply { setPadding(0, pad, 0, pad) }
        box.addView(status)
        box.addView(TextView(this).apply { text = "PINNED SETTINGS"; textSize = 16f })
        pins = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(pins)
        box.addView(TextView(this).apply { text = "RECENT AUDIT"; textSize = 16f; setPadding(0, pad, 0, 4) })
        val logView = TextView(this).apply { textSize = 11f; text = GuardianStore.recentLogs(this@GuardianActivity).joinToString("\n") }
        box.addView(logView)
        setContentView(ScrollView(this).apply { addView(box) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
        }
        renderPins(); renderStatus()
    }
    override fun onResume() { super.onResume(); if (::pins.isInitialized) { renderPins(); renderStatus() } }
    private fun renderStatus() { if (::status.isInitialized) status.text = "Guardian: ${if (GuardianStore.isEnabled(this)) "ON" else "OFF"} · check interval 30 seconds · failures suspend after 3 attempts" }
    private fun renderPins() {
        if (!::pins.isInitialized) return
        pins.removeAllViews()
        GuardianStore.all(this).forEach { p ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = "${p.namespace}:${p.key} = ${p.value}\n${p.status} · failures ${p.failures}/3"; textSize = 12f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply { text = "Remove"; setOnClickListener { GuardianStore.remove(this@GuardianActivity, p.id); renderPins() } })
            pins.addView(row)
        }
        if (GuardianStore.all(this).isEmpty()) pins.addView(TextView(this).apply { text = "No pins yet." })
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}