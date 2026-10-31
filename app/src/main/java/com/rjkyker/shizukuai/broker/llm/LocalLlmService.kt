package com.rjkyker.shizukuai.broker.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.rjkyker.shizukuai.broker.thermal.ThermalPolicy
import com.rjkyker.shizukuai.broker.thermal.ThermalReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.9.0 on-device LLM host. Runs in the isolated ":llm" process so a native crash or OOM kill never takes
 * down the MCP server / accessibility / Dhizuku client in the main process.
 *
 * Governor: refuses work at thermal >= MODERATE, cancels + unloads at >= SEVERE, unloads on memory trim and
 * after [IDLE_UNLOAD_MS] idle, honors BrokerPause through Preflight. Hard caps: n_ctx<=2048, max_new<=256, 45 s.
 */
class LocalLlmService : Service() {

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var handle = 0L
    @Volatile private var loadedId: String? = null
    @Volatile private var loadMs = 0L
    private val generating = AtomicBoolean(false)
    @Volatile private var lastResult: JSONObject? = null
    @Volatile private var lastError: String? = null

    // download state
    @Volatile private var dlId: String? = null
    @Volatile private var dlBytes = 0L
    @Volatile private var dlTotal = 0L
    @Volatile private var dlState = "idle"
    @Volatile private var dlError: String? = null
    private val dlCancel = AtomicBoolean(false)

    private var thermalListener: Any? = null
    private val idleUnload = Runnable { if (!generating.get()) unloadInternal("idle") }

    override fun onCreate() {
        super.onCreate()
        // 1.9.1: no PowerManager listener (TC22 reports SEVERE at ~47 °C). A watcher polls REAL temperatures
        // during generation and cancels at ThermalPolicy STOP.
    }

    override fun onDestroy() {
        cancelInternal(); dlCancel.set(true)
        unloadInternal("destroy")
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !generating.get()) unloadInternal("trim_$level")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : ILocalLlm.Stub() {
        override fun status(): String = statusJson().toString()
        override fun preflight(modelId: String?): String = preflightJson(modelId).toString()
        override fun ensureModel(modelId: String?, allowLowMargin: Boolean): String = startDownload(modelId).toString()
        override fun generate(requestJson: String?): String = generateInternal(JSONObject(requestJson ?: "{}")).toString()
        override fun cancel() { cancelInternal(); dlCancel.set(true) }
        override fun unload(): String { unloadInternal("request"); return statusJson().toString() }
        override fun deleteModel(modelId: String?): String {
            val spec = ModelCatalog.byId(modelId) ?: return err("unknown model $modelId").toString()
            if (loadedId == spec.id) unloadInternal("delete")
            return JSONObject().put("ok", ModelStore.delete(this@LocalLlmService, spec)).put("model", spec.id).toString()
        }
    }

    // ---------------- status / preflight ----------------

    private fun statusJson(): JSONObject {
        val models = JSONArray()
        for (s in ModelCatalog.ALL) {
            models.put(JSONObject().put("id", s.id).put("size_mb", s.sizeMb).put("license", s.license)
                .put("revision", s.revision).put("sha256", s.sha256)
                .put("downloaded", ModelStore.isVerifiedPresent(this, s))
                .put("partial_bytes", ModelStore.partialBytes(this, s)))
        }
        return JSONObject().put("ok", true).put("process", "llm").put("pid", Process.myPid())
            .put("native_loaded", LlamaNative.ensureLoaded()).put("native_error", LlamaNative.loadError())
            .put("loaded_model", loadedId).put("load_ms", loadMs).put("generating", generating.get())
            .put("models", models)
            .put("download", JSONObject().put("model", dlId).put("state", dlState).put("bytes", dlBytes)
                .put("total", dlTotal).put("error", dlError))
            .put("rss_kb", procStatusKb("VmRSS")).put("peak_rss_kb", procStatusKb("VmHWM"))
            .put("thermal", ThermalReader.statusJson(this, allowPriv = false))
            .put("free_storage_mb", Preflight.freeStorageBytes(this) / PreflightResult.MB)
            .put("last_result", lastResult?.let { JSONObject(it.toString()).apply { remove("text") } })
            .put("last_error", lastError)
            .put("caps", JSONObject().put("n_ctx_max", MAX_CTX).put("max_new_max", MAX_NEW).put("timeout_ms", TIMEOUT_MS))
    }

    private fun preflightJson(modelId: String?): JSONObject {
        val specs = ModelCatalog.byId(modelId)?.let { listOf(it) } ?: ModelCatalog.ALL
        val arr = JSONArray()
        for (s in specs) { val i = Preflight.gather(this, s); arr.put(Preflight.evaluate(s, i).toJson(s, i)) }
        return JSONObject().put("ok", true).put("results", arr)
    }

    // ---------------- download ----------------

    private fun startDownload(modelId: String?): JSONObject {
        val spec = ModelCatalog.byId(modelId) ?: return err("unknown model '$modelId'; allowed: ${ModelCatalog.ALL.map { it.id }}")
        if (ModelStore.isVerifiedPresent(this, spec)) return JSONObject().put("ok", true).put("state", "present").put("model", spec.id)
        synchronized(lock) {
            if (dlState == "running") return err("download already running for $dlId")
            val input = Preflight.gather(this, spec)
            val pf = Preflight.evaluate(spec, input)
            if (!pf.canDownload) return pf.toJson(spec, input).put("ok", false).put("error", "preflight failed; nothing downloaded or deleted")
            dlId = spec.id; dlBytes = ModelStore.partialBytes(this, spec); dlTotal = spec.sizeBytes
            dlState = "running"; dlError = null; dlCancel.set(false)
        }
        foreground(true, "Downloading ${spec.id} (${spec.sizeMb} MB, pinned + SHA-256)")
        Thread({
            try {
                ModelStore.download(ModelStore.fileFor(this, spec), spec, ModelStore.httpSource(spec.url),
                    cancelled = {
                        dlCancel.get() || Preflight.freeStorageBytes(this) < 200L * PreflightResult.MB
                    },
                    onProgress = { dlBytes = it.bytes })
                dlState = "verified"
            } catch (e: Throwable) {
                dlError = "${e.javaClass.simpleName}: ${e.message}"; dlState = if (dlCancel.get()) "cancelled" else "failed"
                Log.w(TAG, "download failed", e)
            } finally {
                if (!generating.get()) foreground(false, null)
            }
        }, "llm-download").start()
        return JSONObject().put("ok", true).put("state", "started").put("model", spec.id).put("url", spec.url)
            .put("size_bytes", spec.sizeBytes).put("sha256", spec.sha256)
    }

    // ---------------- generation ----------------

    private fun pickModel(req: JSONObject): ModelSpec? {
        ModelCatalog.byId(req.optString("model", ""))?.let { return if (ModelStore.isVerifiedPresent(this, it)) it else null }
        return ModelCatalog.ALL.firstOrNull { ModelStore.isVerifiedPresent(this, it) }
    }

    private fun generateInternal(req: JSONObject): JSONObject {
        if (!LlamaNative.ensureLoaded()) return err("native runtime unavailable: ${LlamaNative.loadError()}")
        val spec = pickModel(req) ?: return err("no verified model on device; run preflight + download first", "model_missing")
        if (!generating.compareAndSet(false, true)) return err("busy: one generation at a time", "busy")
        main.removeCallbacks(idleUnload)
        try {
            synchronized(lock) {
                if (loadedId != spec.id || handle == 0L) {
                    val input = Preflight.gather(this, spec)
                    val pf = Preflight.evaluate(spec, input)
                    if (!pf.canLoad) return pf.toJson(spec, input).put("ok", false).put("error", "preflight refused load").put("code", "preflight")
                    unloadLocked()
                    runCatching { File("/proc/self/clear_refs").writeText("5") } // reset VmHWM so peak reflects this model
                    val t0 = System.currentTimeMillis()
                    handle = LlamaNative.nativeLoad(ModelStore.fileFor(this, spec).path, spec.nCtx.coerceAtMost(MAX_CTX), 2, 4)
                    loadMs = System.currentTimeMillis() - t0
                    if (handle == 0L) return err("model load failed", "load_failed")
                    loadedId = spec.id
                } else {
                    val g = Preflight.gather(this, spec)
                    if (g.thermalLevel >= Preflight.THERMAL_STOP) return err("thermal STOP: ${g.thermalReason}", "thermal")
                }
            }
            foreground(true, "Generating locally with ${spec.id}")
            val system = req.optString("system", DEFAULT_SYSTEM)
            val user = req.optString("prompt", "")
            if (user.isBlank()) return err("prompt required")
            val prompt = if (req.optBoolean("raw", false)) user else LlamaNative.nativeApplyTemplate(handle, system, user)
            val maxNew = req.optInt("max_new_tokens", 128).coerceIn(1, MAX_NEW)
            val temp = req.optDouble("temperature", 0.7).toFloat().coerceIn(0f, 1.5f)
            val topP = req.optDouble("top_p", 0.9).toFloat().coerceIn(0.05f, 1f)
            val timeout = req.optLong("timeout_ms", TIMEOUT_MS).coerceIn(1_000, TIMEOUT_MS)
            val t0 = System.currentTimeMillis()
            val watcher = Thread({
                try {
                    while (generating.get()) {
                        Thread.sleep(2000)
                        val v = ThermalPolicy.evaluate(ThermalReader.read(this, allowPriv = false, force = true), ThermalReader.config(this))
                        if (v.level == ThermalPolicy.Level.STOP) { Log.w(TAG, "thermal STOP mid-generation: ${v.reason}"); cancelInternal(); break }
                    }
                } catch (_: InterruptedException) {}
            }, "llm-thermal").apply { isDaemon = true; start() }
            val out = JSONObject(LlamaNative.nativeGenerate(handle, prompt, maxNew, temp, topP,
                req.optInt("seed", 42), timeout, null))
            watcher.interrupt()
            out.put("model", spec.id).put("backend", "llama.cpp-cpu").put("load_ms", loadMs)
                .put("wall_ms", System.currentTimeMillis() - t0)
                .put("rss_kb", procStatusKb("VmRSS")).put("peak_rss_kb", procStatusKb("VmHWM"))
                .put("threads", "2 decode / 4 prefill")
            lastResult = out; lastError = if (out.optBoolean("ok")) null else out.optString("error")
            return out
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            return err(lastError!!)
        } finally {
            generating.set(false)
            if (dlState != "running") foreground(false, null)
            main.postDelayed(idleUnload, IDLE_UNLOAD_MS)
        }
    }

    private fun cancelInternal() { val h = handle; if (h != 0L && generating.get()) LlamaNative.nativeCancel(h) }

    private fun unloadInternal(reason: String) {
        if (generating.get()) return
        synchronized(lock) { if (handle != 0L) { Log.i(TAG, "unload ($reason)"); unloadLocked() } }
    }

    private fun unloadLocked() {
        val h = handle
        handle = 0L; loadedId = null
        if (h != 0L) runCatching { LlamaNative.nativeFree(h) }
    }

    // ---------------- helpers ----------------

    private fun foreground(on: Boolean, text: String?) = main.post {
        runCatching {
            if (on) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null)
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "On-device AI", NotificationManager.IMPORTANCE_LOW))
                val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Broker on-device AI").setContentText(text).setOngoing(true).build()
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIF_ID, n)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }.onFailure { Log.w(TAG, "foreground($on) failed: ${it.message}") }
    }

    private fun procStatusKb(key: String): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("$key:") }
            .substringAfter(':').trim().substringBefore(' ').toLong()
    }.getOrDefault(-1L)

    private fun err(msg: String, code: String? = null) =
        JSONObject().put("ok", false).put("error", msg).also { if (code != null) it.put("code", code) }

    companion object {
        private const val TAG = "LocalLlmService"
        private const val CHANNEL = "broker_llm"
        private const val NOTIF_ID = 1907
        const val MAX_CTX = 2048
        const val MAX_NEW = 256
        const val TIMEOUT_MS = 45_000L
        const val IDLE_UNLOAD_MS = 120_000L
        const val DEFAULT_SYSTEM = "You are a concise writing assistant running fully on the owner's phone. " +
            "Write only the requested text. Never include passwords, codes or links unless the user supplied them."
    }
}
