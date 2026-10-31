package com.rjkyker.shizukuai.broker.assist

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.rjkyker.shizukuai.broker.autonomy.audit.AuditLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Type-anywhere: watches focus/text events from the AccessibilityService, debounces, asks [CompletionRouter] and shows
 * the chip. Tap inserts at the cursor. Never submits. Audit stores package + lengths only.
 */
object AssistController {
    private const val TAG = "Assist"
    private const val DEBOUNCE_MS = 900L
    private const val SELF_INSERT_QUIET_MS = 1200L
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "assist").apply { isDaemon = true } }
    private val inFlight = AtomicBoolean(false)
    private var overlay: AssistOverlay? = null
    private var svcRef: AccessibilityService? = null
    @Volatile private var quietUntil = 0L
    @Volatile private var seq = 0
    @Volatile var last: CompletionRouter.Result? = null; private set

    private val trigger = Runnable { request(manual = false) }

    fun attach(svc: AccessibilityService) { svcRef = svc; overlay = AssistOverlay(svc); worker.execute { Predictors.get(svc) } }
    fun detach() { main.removeCallbacks(trigger); overlay?.hideAll(); overlay = null; svcRef = null }

    fun onEvent(svc: AccessibilityService, e: AccessibilityEvent) {
        if (!AssistSettings.enabled(svc)) { overlay?.hideAll(); return }
        val pkg = e.packageName?.toString()
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                overlay?.hideChip(); main.removeCallbacks(trigger)
                val editable = e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && e.source?.isEditable == true
                if (editable && AssistSettings.floatingButton(svc) && !AssistSettings.isExcluded(svc, pkg)) overlay?.showBubble { requestNow() }
                else if (!editable && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) overlay?.hideBubble()
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                overlay?.hideChip(); seq++
                main.removeCallbacks(trigger)
                if (System.currentTimeMillis() < quietUntil) return
                if (AssistSettings.autoSuggest(svc) && !AssistSettings.isExcluded(svc, pkg)) main.postDelayed(trigger, DEBOUNCE_MS)
            }
        }
    }

    /** Manual Assist (tile / bubble / MCP show_chip): suggest now even if auto-suggest is off. */
    fun requestNow(delayMs: Long = 0) { main.removeCallbacks(trigger); main.postDelayed({ request(manual = true) }, delayMs) }

    private fun request(manual: Boolean) {
        val svc = svcRef ?: return
        val info = FocusedInput.read(svc) ?: return
        if (info.blockReason != null) return
        if (!manual && AssistSettings.isExcluded(svc, info.pkg)) return
        if (!inFlight.compareAndSet(false, true)) return
        val mySeq = seq
        val before = info.before
        worker.execute {
            val r = runCatching { CompletionRouter.complete(svc, before) }.getOrNull()
            inFlight.set(false)
            last = r
            if (r == null) return@execute
            Log.i(TAG, "suggest src=${r.source} ok=${r.ok} len=${r.text.length} ms=${r.latencyMs} pkg=${info.pkg} in=${before.length}")
            if (!r.ok) return@execute
            main.post {
                if (seq != mySeq) return@post // user kept typing; stale
                overlay?.showChip(info.bounds, r.text, r.label, r.remote, onAccept = { accept(r, before) }, onDismiss = { overlay?.hideChip() })
            }
        }
    }

    private fun accept(r: CompletionRouter.Result, before: String) {
        val svc = svcRef ?: return
        overlay?.hideChip()
        quietUntil = System.currentTimeMillis() + SELF_INSERT_QUIET_MS
        val res = FocusedInput.insert(svc, r.text, "append")
        AuditLog.record(svc, "USER_DEVICE", "input_focused_insert", "HIGH", if (res.optBoolean("ok")) "ALLOW" else "ERROR",
            "chip tap (never submits)", null,
            outcome = "pkg=${res.optString("package")} len=${r.text.length} src=${r.source} via=${res.optString("via", res.optString("error"))}")
        if (res.optBoolean("ok")) worker.execute { Predictors.learnAccepted(svc, before, r.text) }
    }

    /** Show a given suggestion as a chip (MCP input_suggest show_chip=true). */
    fun showChipFor(r: CompletionRouter.Result) {
        val svc = svcRef ?: return
        val info = FocusedInput.read(svc) ?: return
        val before = info.before
        main.post { overlay?.showChip(info.bounds, r.text, r.label, r.remote, onAccept = { accept(r, before) }, onDismiss = { overlay?.hideChip() }) }
    }
}
