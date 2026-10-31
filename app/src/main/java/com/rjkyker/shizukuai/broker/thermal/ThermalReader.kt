package com.rjkyker.shizukuai.broker.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import com.rjkyker.shizukuai.broker.priv.PrivServiceClient
import org.json.JSONObject
import java.io.File

/**
 * Real temperatures, best source first:
 *  1. direct /sys/class/thermal walk (works in any process where SELinux allows it);
 *  2. the broker's own uid-2000 priv server (`cat` of the same files) — main process only;
 *  3. BatteryManager battery temperature (always readable).
 * PowerManager status is recorded as advisory. Cached for [CACHE_MS].
 */
object ThermalReader {
    private const val PREFS = "thermal_cfg"
    const val CACHE_MS = 10_000L
    @Volatile private var cached: Pair<Long, ThermalPolicy.Readings>? = null
    private const val ZONE_SCRIPT =
        "for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null) \$(cat \$z/temp 2>/dev/null)\"; done"

    fun config(ctx: Context): ThermalPolicy.Config {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = ThermalPolicy.Config()
        return ThermalPolicy.clamp(ThermalPolicy.Config(
            p.getFloat("cpu_throttle_c", d.cpuThrottleC.toFloat()).toDouble(), p.getFloat("cpu_stop_c", d.cpuStopC.toFloat()).toDouble(),
            p.getFloat("battery_throttle_c", d.batteryThrottleC.toFloat()).toDouble(), p.getFloat("battery_stop_c", d.batteryStopC.toFloat()).toDouble()))
    }

    fun setConfig(ctx: Context, c: ThermalPolicy.Config): ThermalPolicy.Config {
        val k = ThermalPolicy.clamp(c)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("cpu_throttle_c", k.cpuThrottleC.toFloat()).putFloat("cpu_stop_c", k.cpuStopC.toFloat())
            .putFloat("battery_throttle_c", k.batteryThrottleC.toFloat()).putFloat("battery_stop_c", k.batteryStopC.toFloat()).apply()
        return k
    }

    fun androidStatus(ctx: Context): Int =
        runCatching { ctx.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrDefault(-1)

    fun batteryC(ctx: Context): Double? {
        val s = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = s.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE || t <= 0) null else t / 10.0
    }

    private fun directZones(): List<Pair<String, String>> = runCatching {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().mapNotNull { z ->
            val type = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            val temp = runCatching { File(z, "temp").readText().trim() }.getOrNull() ?: return@mapNotNull null
            type to temp
        }
    }.getOrDefault(emptyList())

    private fun privZones(): List<Pair<String, String>> {
        if (Looper.myLooper() == Looper.getMainLooper() || !PrivServiceClient.isAlive()) return emptyList()
        val r = runCatching { PrivServiceClient.exec(ZONE_SCRIPT, 8) }.getOrNull() ?: return emptyList()
        return r.optString("stdout", "").lines().mapNotNull { l ->
            val parts = l.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[0].isNotEmpty()) parts[0] to parts[1] else null
        }
    }

    /** @param allowPriv false inside the :llm process (no priv client there). */
    fun read(ctx: Context, allowPriv: Boolean = true, force: Boolean = false): ThermalPolicy.Readings {
        val now = System.currentTimeMillis()
        cached?.let { (t, r) -> if (!force && now - t < CACHE_MS) return r }
        val status = androidStatus(ctx)
        val bat = batteryC(ctx)
        var zones = directZones()
        var src = "sysfs"
        if (zones.none { ThermalPolicy.classify(it.first) == ThermalPolicy.Kind.CPU } && allowPriv) {
            val pz = privZones()
            if (pz.isNotEmpty()) { zones = pz; src = "priv_sysfs" }
        }
        if (zones.isEmpty()) src = if (bat != null) "battery" else "none"
        else if (bat != null) src += "+battery"
        val r = ThermalPolicy.fromZones(zones, bat, status, src)
        cached = now to r
        return r
    }

    fun verdict(ctx: Context, allowPriv: Boolean = true): ThermalPolicy.Verdict = ThermalPolicy.evaluate(read(ctx, allowPriv), config(ctx))

    fun statusJson(ctx: Context, allowPriv: Boolean = true, force: Boolean = false): JSONObject {
        val r = read(ctx, allowPriv, force)
        val c = config(ctx)
        return ThermalPolicy.toJson(r, ThermalPolicy.evaluate(r, c), c).put("ok", true)
    }
}
