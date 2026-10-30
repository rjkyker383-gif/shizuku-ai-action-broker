package com.rjkyker.shizukuai.broker

import android.app.Application
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver

class BrokerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 1.9.0: the isolated ":llm" process hosts only LocalLlmService; skip main-process wiring there.
        if (getProcessName().endsWith(":llm")) return
        // Listen for DataWedge RESULT_ACTION as soon as the process starts.
        DataWedgeResultReceiver.ensureRegistered(this)
        // Prefill built-in, non-secret connection URLs (mesh/bridges/upstream) on first run.
        runCatching { com.rjkyker.shizukuai.broker.util.SecurePrefs.seedDefaultUrls(this) }
        // 1.9.0 autonomy: periodic queue runner + watchdog (WorkManager, battery/thermal gated).
        runCatching { com.rjkyker.shizukuai.broker.autonomy.scheduler.AutonomyScheduler.ensurePeriodic(this) }
    }
}
