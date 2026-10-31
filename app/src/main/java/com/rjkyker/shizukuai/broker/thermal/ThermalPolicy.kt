package com.rjkyker.shizukuai.broker.thermal

import org.json.JSONObject

/**
 * 1.9.1 thermal gate on REAL temperatures. The TC22's PowerManager thermal status reports SEVERE at ~47 °C CPU,
 * so it is advisory only; it is used for gating solely when no real sensor can be read.
 */
object ThermalPolicy {
    enum class Level { OK, THROTTLE, STOP }

    data class Readings(
        val cpuMaxC: Double? = null,
        val gpuMaxC: Double? = null,
        val skinMaxC: Double? = null,
        val batteryC: Double? = null,
        /** PowerManager.THERMAL_STATUS_*; -1 unknown. Advisory. */
        val androidStatus: Int = -1,
        val source: String = "",
        val hottestZone: String? = null,
    ) {
        val hasReal: Boolean get() = cpuMaxC != null || gpuMaxC != null || skinMaxC != null || batteryC != null
    }

    data class Config(val cpuThrottleC: Double = 70.0, val cpuStopC: Double = 80.0,
                      val batteryThrottleC: Double = 42.0, val batteryStopC: Double = 46.0)

    data class Verdict(val level: Level, val reason: String, val hottestC: Double?, val basis: String)

    /** Hard ceilings: no configuration can move a stop point above these. */
    const val HARD_CPU_STOP_C = 90.0
    const val HARD_BATTERY_STOP_C = 50.0
    /** Skin / board zones are gated with the battery thresholds plus this margin. */
    const val SKIN_MARGIN_C = 2.0

    fun clamp(c: Config): Config {
        val cpuStop = c.cpuStopC.coerceIn(40.0, HARD_CPU_STOP_C)
        val batStop = c.batteryStopC.coerceIn(30.0, HARD_BATTERY_STOP_C)
        return Config(c.cpuThrottleC.coerceIn(35.0, cpuStop), cpuStop, c.batteryThrottleC.coerceIn(25.0, batStop), batStop)
    }

    fun evaluate(r: Readings, cfg: Config = Config()): Verdict {
        val c = clamp(cfg)
        if (!r.hasReal) {
            return when {
                r.androidStatus >= 3 -> Verdict(Level.STOP, "no real sensor; Android status ${r.androidStatus} (fallback)", null, "android_fallback")
                r.androidStatus >= 2 -> Verdict(Level.THROTTLE, "no real sensor; Android status ${r.androidStatus} (fallback)", null, "android_fallback")
                r.androidStatus < 0 -> Verdict(Level.THROTTLE, "no thermal data at all; conservative throttle", null, "none")
                else -> Verdict(Level.OK, "no real sensor; Android status ${r.androidStatus} (fallback)", null, "android_fallback")
            }
        }
        val chip = listOfNotNull(r.cpuMaxC, r.gpuMaxC).maxOrNull()
        val bat = r.batteryC
        val skin = r.skinMaxC
        val hottest = listOfNotNull(chip, bat, skin).maxOrNull()
        fun v(l: Level, why: String) = Verdict(l, why, hottest, "real:${r.source}")
        if (chip != null && chip >= c.cpuStopC) return v(Level.STOP, "CPU/GPU %.1f °C >= stop %.0f".format(chip, c.cpuStopC))
        if (bat != null && bat >= c.batteryStopC) return v(Level.STOP, "battery %.1f °C >= stop %.0f".format(bat, c.batteryStopC))
        if (skin != null && skin >= c.batteryStopC + SKIN_MARGIN_C) return v(Level.STOP, "skin %.1f °C >= stop %.0f".format(skin, c.batteryStopC + SKIN_MARGIN_C))
        if (chip != null && chip >= c.cpuThrottleC) return v(Level.THROTTLE, "CPU/GPU %.1f °C >= throttle %.0f".format(chip, c.cpuThrottleC))
        if (bat != null && bat >= c.batteryThrottleC) return v(Level.THROTTLE, "battery %.1f °C >= throttle %.0f".format(bat, c.batteryThrottleC))
        if (skin != null && skin >= c.batteryThrottleC + SKIN_MARGIN_C) return v(Level.THROTTLE, "skin %.1f °C >= throttle %.0f".format(skin, c.batteryThrottleC + SKIN_MARGIN_C))
        return v(Level.OK, "hottest %.1f °C under limits".format(hottest ?: 0.0))
    }

    enum class Kind { CPU, GPU, SKIN, BATTERY, IGNORE }

    /** Classify a /sys/class/thermal zone type string. */
    fun classify(type: String): Kind {
        val t = type.lowercase()
        return when {
            t.startsWith("cpu") || t.contains("cpuss") || t.startsWith("apc") || t.contains("-cpu") -> Kind.CPU
            t.startsWith("gpu") || t.contains("gpuss") -> Kind.GPU
            t == "battery" || t.startsWith("batt") -> Kind.BATTERY
            t.contains("skin") || t.contains("xo-therm") || t.contains("quiet-therm") || t.contains("sys-therm") ||
                t.contains("msm-therm") || t.contains("pa-therm") -> Kind.SKIN
            else -> Kind.IGNORE
        }
    }

    /** Parse a zone temp: millidegrees normally; plain °C if small. Null if implausible. */
    fun parseTemp(raw: String): Double? {
        val v = raw.trim().toLongOrNull() ?: return null
        val c = if (v > 1000 || v < -1000) v / 1000.0 else v.toDouble()
        return if (c in -30.0..150.0 && c != 0.0) c else null
    }

    /** Build readings from "type temp" pairs (e.g. from the priv server or a direct sysfs walk). */
    fun fromZones(zones: List<Pair<String, String>>, batteryC: Double?, androidStatus: Int, source: String): Readings {
        var cpu: Double? = null; var gpu: Double? = null; var skin: Double? = null; var bat: Double? = null
        var hottestZone: String? = null; var hottestVal = Double.NEGATIVE_INFINITY
        for ((type, raw) in zones) {
            val c = parseTemp(raw) ?: continue
            when (classify(type)) {
                Kind.CPU -> cpu = maxOf(cpu ?: c, c)
                Kind.GPU -> gpu = maxOf(gpu ?: c, c)
                Kind.SKIN -> skin = maxOf(skin ?: c, c)
                Kind.BATTERY -> bat = maxOf(bat ?: c, c)
                Kind.IGNORE -> continue
            }
            if (c > hottestVal) { hottestVal = c; hottestZone = type }
        }
        val b = batteryC ?: bat
        return Readings(cpu, gpu, skin, b, androidStatus, source, hottestZone)
    }

    fun toJson(r: Readings, v: Verdict, cfg: Config): JSONObject = JSONObject()
        .put("level", v.level.name).put("reason", v.reason).put("basis", v.basis).put("hottest_c", v.hottestC)
        .put("cpu_max_c", r.cpuMaxC).put("gpu_max_c", r.gpuMaxC).put("skin_max_c", r.skinMaxC).put("battery_c", r.batteryC)
        .put("hottest_zone", r.hottestZone).put("source", r.source)
        .put("android_status_advisory", r.androidStatus)
        .put("config", JSONObject().put("cpu_throttle_c", cfg.cpuThrottleC).put("cpu_stop_c", cfg.cpuStopC)
            .put("battery_throttle_c", cfg.batteryThrottleC).put("battery_stop_c", cfg.batteryStopC)
            .put("hard_cpu_stop_c", HARD_CPU_STOP_C).put("hard_battery_stop_c", HARD_BATTERY_STOP_C))
}
