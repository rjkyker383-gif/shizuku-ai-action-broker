package com.rjkyker.shizukuai.broker.autonomy.scheduler

import android.content.Context
import android.os.PowerManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.rjkyker.shizukuai.broker.autonomy.AutonomyFlags
import com.rjkyker.shizukuai.broker.autonomy.watchdog.Watchdog
import com.rjkyker.shizukuai.broker.llm.Preflight
import com.rjkyker.shizukuai.broker.service.BrokerPause
import java.util.concurrent.TimeUnit

/** WorkManager wiring: one-shot runner on submit, plus a 15-min periodic runner+watchdog. No exact alarms. */
object AutonomyScheduler {
    const val ONE_SHOT = "autonomy_runner"
    const val PERIODIC = "autonomy_periodic"

    fun ensurePeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PeriodicWorker>(15, TimeUnit.MINUTES).build())
    }

    fun kick(ctx: Context, delayMs: Long = 0) {
        val req = OneTimeWorkRequestBuilder<RunnerWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONE_SHOT,
            if (delayMs > 0) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, req)
    }

    fun snapshot(ctx: Context): Conditions.Snapshot {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val (pct, charging) = Preflight.battery(ctx)
        val tv = com.rjkyker.shizukuai.broker.thermal.ThermalReader.verdict(ctx)
        return Conditions.Snapshot(
            autonomyEnabled = AutonomyFlags.get(ctx, AutonomyFlags.AUTONOMY),
            paused = BrokerPause.isPaused(ctx),
            thermalLevel = tv.level.ordinal, thermalReason = tv.reason,
            batteryPct = pct, charging = charging, powerSave = pm.isPowerSaveMode,
        )
    }

    class RunnerWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result { runCatching { TaskRunner.runBatch(applicationContext) }; return Result.success() }
    }

    class PeriodicWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            runCatching { Watchdog.run(applicationContext) }
            runCatching { TaskRunner.runBatch(applicationContext) }
            return Result.success()
        }
    }
}
