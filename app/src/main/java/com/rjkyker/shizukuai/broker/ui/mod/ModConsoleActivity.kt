package com.rjkyker.shizukuai.broker.ui.mod

import android.animation.Animator
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rjkyker.shizukuai.broker.action.ActionAllowList
import com.rjkyker.shizukuai.broker.action.RiskLevel
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.bridge.ShizukuGateway
import com.rjkyker.shizukuai.broker.dhizuku.DhizukuGateway
import com.rjkyker.shizukuai.broker.mcp.McpToolExecutor
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyCatalog
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyEngine
import com.rjkyker.shizukuai.broker.priv.PrivServiceClient
import com.rjkyker.shizukuai.broker.server.ActionRouter
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.service.BrokerPause
import com.rjkyker.shizukuai.broker.ui.ownerArgTemplate
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 1.9.0 "Mod Console": animated HUD over the existing Dhizuku + owner_policy ops. Purely visual —
 * every op still goes through OwnerPolicyEngine (Caller.DASHBOARD) or the manage_dhizuku_policy executor with
 * the same tiers and confirm rules. HIGH = hold-to-confirm ring, LOCKOUT = type the op name.
 * Ambient animation stops in onStop (screen off / backgrounded), when paused, and when animations are disabled.
 */
class ModConsoleActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var hud: HudBackgroundView
    private lateinit var chain: PrivilegeChainView
    private lateinit var ram: RadialGaugeView
    private lateinit var disk: RadialGaugeView
    private lateinit var temp: RadialGaugeView
    private lateinit var term: TerminalLogView
    private lateinit var pauseBanner: TextView
    private lateinit var thermalNote: TextView
    private val executor by lazy { McpToolExecutor(applicationContext, ActionRouter(applicationContext)) }
    private var visible = false
    private val d by lazy { resources.displayMetrics.density }

    /** Dhizuku command set exposed by manage_dhizuku_policy (tier = mapped ActionAllowList risk). */
    private val dhizukuOps = linkedMapOf(
        "status" to "dhizuku_status", "connect" to "dhizuku_connect", "scopes" to "dhizuku_scopes",
        "delegated_scopes" to "admin_scopes", "restriction" to "dhizuku_add_restriction", "hide" to "dhizuku_hide_app",
        "suspend" to "dhizuku_suspend_app", "perm" to "dhizuku_perm", "set_scopes" to "dhizuku_set_scopes",
    )

    private fun dpi(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Mod Console"
        window.statusBarColor = ModFx.BG
        window.navigationBarColor = ModFx.BG
        val root = FrameLayout(this)
        hud = HudBackgroundView(this)
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12), dpi(14), dpi(12), dpi(24)) }
        val scroll = ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS; isFocusableInTouchMode = true }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        scroll.post { scroll.scrollTo(0, 0) }

        col.addView(label("◉ MOD CONSOLE // DEVICE OWNER", 17f, ModFx.CYAN, bold = true))
        col.addView(label("Dhizuku device owner · own uid-2000 server · no root. Same risk gates as the dashboard.", 11f, ModFx.DIM))
        pauseBanner = label("⏸ BROKER PAUSED — ambient animation + polling stopped", 11f, ModFx.AMBER, bold = true).apply { visibility = View.GONE }
        col.addView(pauseBanner)
        col.addView(section("PRIVILEGE CHAIN"))
        chain = PrivilegeChainView(this)
        col.addView(panel(chain))
        col.addView(section("TELEMETRY"))
        val gauges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ram = RadialGaugeView(this, "RAM"); disk = RadialGaugeView(this, "STORAGE"); temp = RadialGaugeView(this, "TEMP")
        listOf(ram, disk, temp).forEach { gauges.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        col.addView(panel(gauges))
        thermalNote = label("thermal: reading…", 10f, ModFx.DIM)
        col.addView(thermalNote)

        col.addView(section("LIVE LOG"))
        term = TerminalLogView(this)
        col.addView(term, LinearLayout.LayoutParams(-1, dpi(170)).apply { bottomMargin = dpi(8) })

        col.addView(section("DHIZUKU COMMANDS"))
        val dz = dhizukuOps.entries.groupBy { tierForRisk(ActionAllowList.riskLevelFor(it.value)) }
        for (tier in listOf("READ", "MEDIUM", "HIGH")) dz[tier]?.let { list ->
            col.addView(tierHeader(tier)); addCards(col, list.map { it.key }, tier) { op, card -> runDhizuku(op, tier, card) }
        }
        col.addView(section("OWNDROID PARITY · owner_policy"))
        val byTier = OwnerPolicyCatalog.OPS.entries.groupBy { it.value }
        for (tier in listOf(OwnerPolicyCatalog.Tier.READ, OwnerPolicyCatalog.Tier.MEDIUM, OwnerPolicyCatalog.Tier.HIGH, OwnerPolicyCatalog.Tier.LOCKOUT)) {
            val ops = byTier[tier]?.map { it.key } ?: continue
            col.addView(tierHeader(tier.name)); addCards(col, ops, tier.name) { op, card -> runOwner(op, tier, card) }
        }
        setContentView(root)
        term.log("> mod console online · ${OwnerPolicyCatalog.OPS.size} owner ops · ${dhizukuOps.size} dhizuku ops", ModFx.CYAN)
        if (!ModFx.animationsOn(this)) term.log("> system animations off: effects disabled", ModFx.DIM)
    }

    // ---------------- lifecycle: no ambient work unless visible + not paused ----------------

    override fun onStart() { super.onStart(); visible = true; refreshLoop.run() }
    override fun onStop() { visible = false; main.removeCallbacks(refreshLoop); hud.stop(); chain.stop(); super.onStop() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private val refreshLoop = object : Runnable {
        override fun run() {
            if (!visible) return
            val paused = BrokerPause.isPaused(this@ModConsoleActivity)
            pauseBanner.visibility = if (paused) View.VISIBLE else View.GONE
            if (paused) { hud.stop(); chain.stop() } else { hud.start(); chain.start() }
            io.execute { telemetry() }
            if (!paused) main.postDelayed(this, 5000) else main.postDelayed(this, 30_000)
        }
    }

    private fun telemetry() {
        val chainStates = listOf(
            runCatching { ShizukuGateway.isReady() }.getOrNull(),
            runCatching { DhizukuGateway.isGranted() }.getOrNull(),
            runCatching { PrivServiceClient.isAlive() }.getOrNull(),
            BrokerServer.get() != null,
        )
        val mi = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val st = runCatching { StatFs(filesDir.path) }.getOrNull()
        val total = st?.totalBytes ?: 1L; val free = st?.availableBytes ?: 0L
        val th = com.rjkyker.shizukuai.broker.thermal.ThermalReader.statusJson(applicationContext)
        val tC = th.optDouble("hottest_c", 0.0).toFloat()
        val stopC = th.optJSONObject("config")?.optDouble("cpu_stop_c", 80.0)?.toFloat() ?: 80f
        runOnUiThread {
            if (!visible) return@runOnUiThread
            chain.setStates(chainStates)
            ram.set(1f - mi.availMem.toFloat() / mi.totalMem, "${mi.availMem / (1 shl 20)}M")
            disk.set(1f - free.toFloat() / total, "${free / (1 shl 20)}M")
            temp.set(tC / stopC, "${"%.0f".format(tC)}°")
            thermalNote.text = "thermal ${th.optString("level")} · ${th.optString("reason")} · hottest ${th.optString("hottest_zone")} · " +
                "Android status ${th.optInt("android_status_advisory")} (advisory only)"
            thermalNote.setTextColor(when (th.optString("level")) { "STOP" -> ModFx.RED; "THROTTLE" -> ModFx.AMBER; else -> ModFx.DIM })
        }
    }

    // ---------------- cards ----------------

    private fun tierForRisk(r: RiskLevel?) = when (r) { RiskLevel.LOW -> "READ"; RiskLevel.MEDIUM -> "MEDIUM"; else -> "HIGH" }

    private fun addCards(parent: LinearLayout, ops: List<String>, tier: String, onTap: (String, TextView) -> Unit) {
        var row: LinearLayout? = null
        ops.forEachIndexed { i, op ->
            if (i % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; parent.addView(row) }
            val card = opCard(op, tier)
            card.setOnClickListener { onTap(op, card) }
            row!!.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dpi(3), dpi(3), dpi(3), dpi(3)) })
            if (ModFx.animationsOn(this)) { card.alpha = 0f; card.translationY = dpi(8).toFloat()
                card.animate().alpha(1f).translationY(0f).setStartDelay((i * 25L).coerceAtMost(400)).setDuration(260).start() }
        }
        if (ops.size % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    }

    private fun opCard(op: String, tier: String): TextView {
        val c = ModFx.tierColor(tier)
        return TextView(this).apply {
            text = op.replace('_', ' ').uppercase(); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 11f; setTextColor(c); gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(10), dpi(10), dpi(10), dpi(10)); minHeight = dpi(44)
            background = GradientDrawable().apply { cornerRadius = 8 * d; setColor(ModFx.withAlpha(c, 22)); setStroke(dpi(1).coerceAtLeast(2), ModFx.withAlpha(c, 200)) }
            elevation = 3 * d
            contentDescription = "$op, $tier tier"
        }
    }

    private fun tierHeader(tier: String): TextView {
        val desc = when (tier) { "READ" -> "read-only"; "MEDIUM" -> "normal · reversible"; "HIGH" -> "high · hold to confirm"; "LOCKOUT" -> "lockout risk · type to confirm"; else -> "" }
        return label("▌$tier  ·  $desc", 11f, ModFx.tierColor(tier), bold = true).apply { setPadding(dpi(4), dpi(10), 0, dpi(2)) }
    }

    // ---------------- running ops (gates unchanged) ----------------

    private fun editArgs(title: String, template: String, then: (JSONObject) -> Unit) {
        val input = EditText(this).apply { setText(template); textSize = 12f; typeface = Typeface.MONOSPACE }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Next") { _, _ ->
                val a = runCatching { JSONObject(input.text.toString()) }.getOrNull()
                if (a == null) term.log("[ERR] bad JSON", ModFx.RED) else then(a)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun runOwner(op: String, tier: OwnerPolicyCatalog.Tier, card: TextView) {
        editArgs("$op [$tier]", ownerArgTemplate(op)) { args ->
            when (tier) {
                OwnerPolicyCatalog.Tier.READ, OwnerPolicyCatalog.Tier.MEDIUM -> exec(op, card, args) {
                    OwnerPolicyEngine.run(applicationContext, op, args, OwnerPolicyCatalog.Caller.DASHBOARD, false) }
                OwnerPolicyCatalog.Tier.HIGH -> holdConfirm(op, args) { exec(op, card, args) {
                    OwnerPolicyEngine.run(applicationContext, op, args, OwnerPolicyCatalog.Caller.DASHBOARD, true) } }
                OwnerPolicyCatalog.Tier.LOCKOUT -> typeConfirm(op, args) { exec(op, card, args) {
                    OwnerPolicyEngine.run(applicationContext, op, args, OwnerPolicyCatalog.Caller.DASHBOARD, true) } }
                OwnerPolicyCatalog.Tier.FORBIDDEN -> term.log("[DENY] $op is not implemented", ModFx.RED)
            }
        }
    }

    private fun runDhizuku(op: String, tier: String, card: TextView) {
        val tmpl = when (op) {
            "restriction" -> """{"restriction":"no_config_bluetooth","enabled":true}"""
            "hide", "suspend" -> """{"package":"","enabled":true}"""
            "perm" -> """{"package":"","permission":"android.permission.CAMERA","state":"granted"}"""
            "set_scopes" -> """{"package":"","scopes":[]}"""
            else -> "{}"
        }
        val run: (JSONObject, Boolean) -> Unit = { a, confirmed ->
            exec("dhizuku.$op", card, a) {
                executor.call("manage_dhizuku_policy", JSONObject(a.toString()).put("op", op), confirmHeader = confirmed, actor = Actor.USER_DEVICE)
            }
        }
        if (tmpl == "{}" && tier == "READ") run(JSONObject(), false)
        else editArgs("dhizuku $op [$tier]", tmpl) { a -> if (tier == "HIGH") holdConfirm(op, a) { run(a, true) } else run(a, false) }
    }

    private fun holdConfirm(op: String, args: JSONObject, onOk: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dpi(16), dpi(8), dpi(16), 0) }
        box.addView(label("HIGH-risk device-wide change:\n$op $args", 12f, ModFx.AMBER))
        val ring = HoldToConfirmView(this, ModFx.AMBER)
        box.addView(ring)
        box.addView(label("Press and hold the ring to apply", 11f, ModFx.DIM))
        val dlg = AlertDialog.Builder(this).setTitle("Confirm $op").setView(box).setNegativeButton("Cancel", null).create()
        ring.onConfirmed = { dlg.dismiss(); onOk() }
        dlg.show()
    }

    private fun typeConfirm(op: String, args: JSONObject, onOk: () -> Unit) {
        val field = EditText(this).apply { hint = op; typeface = Typeface.MONOSPACE }
        AlertDialog.Builder(this).setTitle("LOCKOUT · $op")
            .setMessage("This can cut off ADB, the camera, Tailscale/VPN, accessibility or input.\nType the op name to apply.\n\n$args")
            .setView(field)
            .setPositiveButton("Apply") { _, _ ->
                if (field.text.toString().trim() != op) term.log("[ABORT] $op: name did not match; nothing applied", ModFx.AMBER) else onOk()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun exec(name: String, card: TextView, args: JSONObject, block: () -> JSONObject) {
        val pulse: Animator? = ModFx.pulse(card)
        card.isEnabled = false
        term.log("> $name ${args.toString().take(120)}", ModFx.CYAN)
        io.execute {
            val r = runCatching { block() }.getOrElse { JSONObject().put("ok", false).put("error", "${it.javaClass.simpleName}: ${it.message}") }
            runOnUiThread {
                ModFx.settle(card, pulse); card.isEnabled = true
                val ok = r.optBoolean("ok", false)
                if (ok) { ModFx.burst(card); term.log("[OK] $name ${summarize(r)}", ModFx.GREEN) }
                else { ModFx.shake(card); term.log("[ERR] $name ${r.optString("error", summarize(r))}", ModFx.RED) }
            }
        }
    }

    private fun summarize(r: JSONObject): String =
        JSONObject(r.toString()).apply { remove("ok") }.toString().replace(Regex("\\s+"), " ").take(220)

    // ---------------- small view helpers ----------------

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    private fun section(t: String) = label("── $t ──", 12f, ModFx.TEXT, bold = true).apply { setPadding(0, dpi(14), 0, dpi(6)); letterSpacing = 0.08f }

    private fun panel(v: View) = FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = 10 * d; setColor(ModFx.PANEL); setStroke(2, ModFx.withAlpha(ModFx.CYAN, 90)) }
        setPadding(dpi(6), dpi(6), dpi(6), dpi(6)); addView(v)
    }
}
