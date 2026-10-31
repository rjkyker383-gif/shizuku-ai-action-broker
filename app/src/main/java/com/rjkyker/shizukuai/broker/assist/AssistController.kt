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

    @Volatile private var imeCache: Pair<Long, String?> = 0L to null
    private fun imePackage(ctx: android.content.Context): String? {
        val now = System.currentTimeMillis()
        if (now - imeCache.first < 30_000) return imeCache.second
        val id = runCatching { android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) }.getOrNull()
        val p = id?.substringBefore('/')
        imeCache = now to p
        return p
    }

    private fun status(svc: AccessibilityService, msg: String) {
        Log.i(TAG, "status: $msg")
        main.post { overlay?.flash((svc.resources.displayMetrics.heightPixels * 0.22f).toInt(), msg) }
    }

    fun attach(svc: AccessibilityService) {
        svcRef = svc; overlay = AssistOverlay(svc); worker.execute { Predictors.get(svc) }
        main.post { refreshBubble() }
    }

    /** Persistent bubble: shown whenever assist + floating button are on (re-attached on every service (re)bind). */
    fun refreshBubble() {
        val svc = svcRef ?: return
        val o = overlay ?: return
        if (AssistSettings.enabled(svc) && AssistSettings.floatingButton(svc)) o.showBubble { requestNow() } else o.hideBubble()
    }
    /** MCP path: runs on the caller's thread (not main). */
    fun typewriteBlocking(svc: AccessibilityService, text: String): Typewriter.Outcome = typewrite(svc, text)

    fun bubbleShown(): Boolean = overlay?.bubbleShown == true

    fun refreshBubbleAsync() { main.post { refreshBubble() } }

    fun detach() { main.removeCallbacks(trigger); overlay?.hideAll(); overlay = null; svcRef = null }

    fun onEvent(svc: AccessibilityService, e: AccessibilityEvent) {
        if (!AssistSettings.enabled(svc)) { overlay?.hideAll(); return }
        val pkg = e.packageName?.toString()
        val src = runCatching { e.source }.getOrNull()
        val editable = src?.isEditable == true
        if (FocusMatch.ignoreEvent(pkg, svc.packageName, imePackage(svc), editable, e.className?.toString())) return
        if (editable && src != null && e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) FocusedInput.remember(src, svc.packageName)
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                overlay?.hideChip(); main.removeCallbacks(trigger)
                refreshBubble()
                // never show any overlay over a password / OTP / PIN / card field
                if (e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && src != null && src.isEditable) {
                    overlay?.suspendBubble(FieldGuard.blockReason(FocusedInput.guardField(src)) != null)
                } else if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    val f = FocusedInput.findNode(svc)
                    overlay?.suspendBubble(f != null && FieldGuard.blockReason(FocusedInput.guardField(f)) != null)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (Typewriter.isRunning()) return // our own typing; no suggestions mid-type
                overlay?.hideChip(); seq++
                main.removeCallbacks(trigger)
                if (System.currentTimeMillis() < quietUntil) return
                if (AssistSettings.autoSuggest(svc) && !AssistSettings.isExcluded(svc, pkg)) main.postDelayed(trigger, DEBOUNCE_MS)
            }
        }
    }

    /** Manual Assist (tile / bubble / MCP show_chip): suggest now even if auto-suggest is off. */
    fun requestNow(delayMs: Long = 0) {
        if (Typewriter.isRunning()) { Typewriter.stop(); return } // bubble tap while typing = stop
        main.removeCallbacks(trigger); main.postDelayed({ request(manual = true) }, delayMs)
    }

    private fun request(manual: Boolean) {
        val svc = svcRef ?: return
        val info = FocusedInput.read(svc)
        if (info == null) { Log.i(TAG, "request: no focused editable (manual=$manual)"); if (manual) status(svc, "Assist: tap into a text field first"); return }
        if (info.blockReason != null) { overlay?.suspendBubble(true); if (manual) status(svc, "Assist: off for password/OTP/card fields"); return }
        if (!manual && AssistSettings.isExcluded(svc, info.pkg)) return
        if (!inFlight.compareAndSet(false, true)) { if (manual) status(svc, "Assist: still thinking…"); return }
        if (manual) main.post { overlay?.flash((svc.resources.displayMetrics.heightPixels * 0.22f).toInt(), "Assist: thinking…") }
        val mySeq = seq
        val before = info.before
        worker.execute {
            val r = runCatching { CompletionRouter.complete(svc, before) }.getOrNull()
            inFlight.set(false)
            if (r == null && manual) status(svc, "Assist: completion error")
            last = r
            if (r == null) return@execute
            Log.i(TAG, "suggest src=${r.source} ok=${r.ok} len=${r.text.length} ms=${r.latencyMs} pkg=${info.pkg} in=${before.length}")
            if (!r.ok) { if (manual) main.post { overlay?.flash(info.bounds.top.coerceAtLeast(80), "Assist: no suggestion (" + r.reasons.lastOrNull().orEmpty().take(40) + ")") }; return@execute }
            main.post {
                if (seq != mySeq && !manual) return@post // user kept typing; stale
                overlay?.showChip(info.bounds, r.text, r.label, r.remote, onAccept = { accept(r, before) }, onDismiss = { overlay?.hideChip() })
            }
        }
    }

    private fun accept(r: CompletionRouter.Result, before: String) {
        val svc = svcRef ?: return
        overlay?.hideChip()
        quietUntil = System.currentTimeMillis() + SELF_INSERT_QUIET_MS
        if (AssistSettings.insertMode(svc) == "typewriter") {
            worker.execute {
                val o = typewrite(svc, r.text)
                quietUntil = System.currentTimeMillis() + SELF_INSERT_QUIET_MS
                if (o.ok) Predictors.learnAccepted(svc, before, r.text)
            }
            return
        }
        val res = FocusedInput.insert(svc, r.text, "append")
        AuditLog.record(svc, "USER_DEVICE", "input_focused_insert", "HIGH", if (res.optBoolean("ok")) "ALLOW" else "ERROR",
            "chip tap (never submits)", null,
            outcome = "pkg=${res.optString("package")} len=${r.text.length} src=${r.source} via=${res.optString("via", res.optString("error"))}")
        if (res.optBoolean("ok")) worker.execute { Predictors.learnAccepted(svc, before, r.text) }
    }

    /** Typewriter with the bubble showing "typing…" (tap = stop). Blocking: worker thread only. */
    fun typewrite(svc: AccessibilityService, text: String): Typewriter.Outcome {
        val o = Typewriter.type(svc, text, AssistSettings.typeSpeed(svc)) { on -> main.post { overlay?.setTyping(on) } }
        if (!o.ok && o.stopped != "already typing")
            main.post { overlay?.flash((svc.resources.displayMetrics.heightPixels * 0.22f).toInt(), "typing stopped: ${o.stopped}") }
        return o
    }

    /** Show a given suggestion as a chip (MCP input_suggest show_chip=true). */
    fun showChipFor(r: CompletionRouter.Result) {
        val svc = svcRef ?: return
        val info = FocusedInput.read(svc) ?: return
        val before = info.before
        main.post { overlay?.showChip(info.bounds, r.text, r.label, r.remote, onAccept = { accept(r, before) }, onDismiss = { overlay?.hideChip() }) }
    }
}
