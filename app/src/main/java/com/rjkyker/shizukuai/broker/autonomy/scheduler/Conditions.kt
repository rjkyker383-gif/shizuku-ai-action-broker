package com.rjkyker.shizukuai.broker.autonomy.scheduler

/** Battery/thermal/pause gate for running queued work. Pure for tests. */
object Conditions {
    data class Snapshot(
        val autonomyEnabled: Boolean,
        val paused: Boolean,
        /** ThermalPolicy level from real temperatures: 0 OK, 1 THROTTLE, 2 STOP. */
        val thermalLevel: Int,
        val thermalReason: String = "",
        val batteryPct: Int,
        val charging: Boolean,
        val powerSave: Boolean,
    )

    sealed class Decision { object Run : Decision(); data class Wait(val reason: String, val retryMs: Long) : Decision() }

    const val THERMAL_STOP = 2
    const val THERMAL_THROTTLE = 1
    const val MIN_BATTERY = 20
    const val MIN_BATTERY_LLM = 30

    fun evaluate(s: Snapshot, needsLlm: Boolean): Decision = when {
        !s.autonomyEnabled -> Decision.Wait("autonomy disabled", 15 * 60_000L)
        s.paused -> Decision.Wait("broker paused", 15 * 60_000L)
        s.thermalLevel >= THERMAL_STOP -> Decision.Wait("thermal STOP: ${s.thermalReason}", 10 * 60_000L)
        needsLlm && s.thermalLevel >= THERMAL_THROTTLE && !s.charging -> Decision.Wait("thermal THROTTLE (${s.thermalReason}); LLM waits for cool/charging", 5 * 60_000L)
        !s.charging && s.batteryPct in 0 until MIN_BATTERY -> Decision.Wait("battery ${s.batteryPct}%", 30 * 60_000L)
        needsLlm && !s.charging && s.batteryPct in 0 until MIN_BATTERY_LLM -> Decision.Wait("battery ${s.batteryPct}% < $MIN_BATTERY_LLM% for LLM", 30 * 60_000L)
        needsLlm && s.powerSave && !s.charging -> Decision.Wait("battery saver on", 30 * 60_000L)
        else -> Decision.Run
    }
}
