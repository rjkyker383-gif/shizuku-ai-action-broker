package com.rjkyker.shizukuai.broker.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Main-process client for [LocalLlmService]. Call from a background thread only (blocking binder calls). */
object LlmClient {
    @Volatile private var svc: ILocalLlm? = null
    private var latch = CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())
    private const val IDLE_UNBIND_MS = 5 * 60_000L
    @Volatile private var appCtx: Context? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            svc = ILocalLlm.Stub.asInterface(service); latch.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
        override fun onBindingDied(name: ComponentName?) { svc = null; unbind() }
    }

    private val idle = Runnable { unbind() }

    @Synchronized
    private fun ensure(ctx: Context, timeoutMs: Long = 8_000): ILocalLlm? {
        svc?.let { if (it.asBinder().isBinderAlive) { touch(); return it } }
        val app = ctx.applicationContext
        appCtx = app
        latch = CountDownLatch(1)
        val ok = app.bindService(Intent(app, LocalLlmService::class.java), conn, Context.BIND_AUTO_CREATE)
        if (!ok) return null
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        touch()
        return svc
    }

    private fun touch() { main.removeCallbacks(idle); main.postDelayed(idle, IDLE_UNBIND_MS) }

    @Synchronized
    fun unbind() {
        val app = appCtx ?: return
        runCatching { app.unbindService(conn) }
        svc = null
    }

    fun isBound(): Boolean = svc?.asBinder()?.isBinderAlive == true

    private inline fun call(ctx: Context, f: (ILocalLlm) -> String): JSONObject {
        return try {
            val s = ensure(ctx) ?: return JSONObject().put("ok", false).put("error", "llm service bind failed")
            JSONObject(f(s))
        } catch (e: Exception) {
            svc = null
            JSONObject().put("ok", false).put("error", "llm service: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun status(ctx: Context) = call(ctx) { it.status() }
    fun preflight(ctx: Context, modelId: String?) = call(ctx) { it.preflight(modelId) }
    fun ensureModel(ctx: Context, modelId: String) = call(ctx) { it.ensureModel(modelId, false) }
    fun generate(ctx: Context, req: JSONObject) = call(ctx) { it.generate(req.toString()) }
    fun unload(ctx: Context) = call(ctx) { it.unload() }
    fun deleteModel(ctx: Context, modelId: String) = call(ctx) { it.deleteModel(modelId) }
    fun cancel(ctx: Context) { runCatching { svc?.cancel() } }
}
