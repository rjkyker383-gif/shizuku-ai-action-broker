package com.rjkyker.shizukuai.broker.llm

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.rjkyker.shizukuai.broker.service.BrokerPause
import com.rjkyker.shizukuai.broker.thermal.ThermalPolicy
import com.rjkyker.shizukuai.broker.thermal.ThermalReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Snapshot of everything the preflight needs; pure so it is unit-testable. */
data class PreflightInput(
    val freeStorageBytes: Long,
    val availRamBytes: Long,
    val lowMemThresholdBytes: Long,
    val lowMemory: Boolean,
    /** 1.9.1: ThermalPolicy level from REAL temperatures (0 OK, 1 THROTTLE, 2 STOP). */
    val thermalLevel: Int,
    val paused: Boolean,
    val batteryPct: Int,
    val charging: Boolean,
    val abi64: Boolean,
    val alreadyDownloaded: Boolean,
    val thermalReason: String = "",
    /** PowerManager status, advisory only (the TC22 reports SEVERE at ~47 °C). */
    val androidThermalAdvisory: Int = -1,
)

data class PreflightResult(
    val ok: Boolean,
    val canDownload: Boolean,
    val canLoad: Boolean,
    val reasons: List<String>,
    val storageShortfallBytes: Long,
) {
    fun toJson(spec: ModelSpec, input: PreflightInput): JSONObject = JSONObject()
        .put("ok", ok).put("model", spec.id).put("can_download", canDownload).put("can_load", canLoad)
        .put("reasons", JSONArray(reasons))
        .put("free_storage_mb", input.freeStorageBytes / MB)
        .put("required_storage_mb", ModelCatalog.requiredStorageBytes(spec) / MB)
        .put("storage_shortfall_mb", (storageShortfallBytes + MB - 1) / MB)
        .put("avail_ram_mb", input.availRamBytes / MB)
        .put("ram_headroom_mb", (input.availRamBytes - input.lowMemThresholdBytes) / MB)
        .put("required_ram_headroom_mb", spec.minFreeRamMb)
        .put("thermal_level", input.thermalLevel).put("thermal_reason", input.thermalReason)
        .put("android_thermal_advisory", input.androidThermalAdvisory).put("paused", input.paused)
        .put("battery_pct", input.batteryPct).put("charging", input.charging)
        .put("downloaded", input.alreadyDownloaded)

    companion object { const val MB = 1024L * 1024L }
}

object Preflight {
    const val THERMAL_STOP = 2
    const val MIN_BATTERY_PCT = 15

    fun evaluate(spec: ModelSpec, i: PreflightInput): PreflightResult {
        val reasons = mutableListOf<String>()
        if (!i.abi64) reasons += "arm64-v8a required"
        if (i.paused) reasons += "broker paused"
        if (i.thermalLevel >= THERMAL_STOP) reasons += "thermal STOP: ${i.thermalReason}"
        if (!i.charging && i.batteryPct in 0 until MIN_BATTERY_PCT) reasons += "battery ${i.batteryPct}% < $MIN_BATTERY_PCT% and not charging"
        val common = reasons.isEmpty()

        val need = ModelCatalog.requiredStorageBytes(spec)
        val shortfall = if (i.alreadyDownloaded) 0L else (need - i.freeStorageBytes).coerceAtLeast(0L)
        val storageOk = i.alreadyDownloaded || shortfall == 0L
        if (!storageOk) reasons += "storage: need ${need / PreflightResult.MB} MB free (model + 750 MB margin), have ${i.freeStorageBytes / PreflightResult.MB} MB"

        val headroomMb = (i.availRamBytes - i.lowMemThresholdBytes) / PreflightResult.MB
        val ramOk = !i.lowMemory && headroomMb >= spec.minFreeRamMb
        if (!ramOk) reasons += "ram: headroom ${headroomMb} MB < ${spec.minFreeRamMb} MB" + if (i.lowMemory) " (lowMemory)" else ""

        val canDownload = common && storageOk && !i.alreadyDownloaded
        val canLoad = common && i.alreadyDownloaded && ramOk
        return PreflightResult(
            ok = common && storageOk && ramOk,
            canDownload = canDownload, canLoad = canLoad, reasons = reasons, storageShortfallBytes = shortfall,
        )
    }

    /** Gather a real snapshot from the device (no shell, no dumpsys). */
    fun gather(ctx: Context, spec: ModelSpec): PreflightInput {
        val dir = ModelStore.modelsDir(ctx)
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val tr = ThermalReader.read(ctx, allowPriv = !isLlmProcess())
        val tv = ThermalPolicy.evaluate(tr, ThermalReader.config(ctx))
        val (pct, charging) = battery(ctx)
        return PreflightInput(
            freeStorageBytes = free, availRamBytes = mi.availMem, lowMemThresholdBytes = mi.threshold,
            lowMemory = mi.lowMemory, thermalLevel = tv.level.ordinal, paused = pausedFresh(ctx),
            batteryPct = pct, charging = charging,
            abi64 = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"),
            alreadyDownloaded = ModelStore.isVerifiedPresent(ctx, spec),
            thermalReason = tv.reason, androidThermalAdvisory = tr.androidStatus,
        )
    }

    /** Cross-process safe read of BrokerPause (the :llm process must not trust a cached copy). */
    @Suppress("DEPRECATION")
    fun pausedFresh(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences("broker_pause", Context.MODE_MULTI_PROCESS).getBoolean("paused", false)
    }.getOrDefault(BrokerPause.isPaused(ctx))

    fun isLlmProcess(): Boolean = runCatching { android.app.Application.getProcessName().endsWith(":llm") }.getOrDefault(false)

    fun battery(ctx: Context): Pair<Int, Boolean> {
        val s = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = s?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = s?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (s?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return (if (level >= 0 && scale > 0) level * 100 / scale else -1) to plugged
    }

    fun freeStorageBytes(ctx: Context): Long =
        runCatching { StatFs(ModelStore.modelsDir(ctx).path).availableBytes }.getOrDefault(0L)

    @Suppress("unused")
    private fun File.ensure(): File = apply { mkdirs() }
}
