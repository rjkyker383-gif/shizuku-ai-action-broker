package com.rjkyker.shizukuai.broker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Accessibility-backed UI automation: snapshot, tap, swipe, text input, back/home.
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        com.rjkyker.shizukuai.broker.assist.AssistController.attach(this)
        Log.i(TAG, "AgentAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 1.9.1 type-anywhere: focus/text events drive the suggestion chip (metadata only; nothing logged).
        if (event != null) runCatching { com.rjkyker.shizukuai.broker.assist.AssistController.onEvent(this, event) }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentAccessibilityService interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        com.rjkyker.shizukuai.broker.assist.AssistController.detach()
        super.onDestroy()
        Log.i(TAG, "AgentAccessibilityService destroyed")
    }

    /** Simplified node list from rootInActiveWindow. */
    fun snapshot(maxNodes: Int = 200): JSONObject {
        val root = rootInActiveWindow
        val nodes = JSONArray()
        if (root != null) {
            collectNodes(root, nodes, maxNodes)
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("nodeCount", nodes.length())
            .put("nodes", nodes)
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val matches = root.findAccessibilityNodeInfosByText(text)
            for (node in matches) {
                var target: AccessibilityNodeInfo? = node
                while (target != null) {
                    if (target.isClickable) {
                        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        recycleQuietly(node)
                        if (ok) return true
                        break
                    }
                    target = target.parent
                }
                recycleQuietly(node)
            }
            false
        } finally {
            recycleQuietly(root)
        }
    }

    fun tap(x: Float, y: Float, durationMs: Long = 50L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditable(root)
            if (focused == null) return false
            // 1.9.1: never type into password / OTP / PIN / card fields
            if (com.rjkyker.shizukuai.broker.assist.FieldGuard.blockReason(
                    com.rjkyker.shizukuai.broker.assist.FocusedInput.guardField(focused)) != null) { recycleQuietly(focused); return false }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            recycleQuietly(focused)
            ok
        } finally {
            recycleQuietly(root)
        }
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun notifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---------------- a11y_* tools (no ADB) ----------------

    /**
     * Screenshot through AccessibilityService.takeScreenshot (API 30+, needs canTakeScreenshot in the
     * service XML). Returns {ok, mime, width, height, image_base64}. Secure (FLAG_SECURE) windows come back black.
     */
    fun screenshot(maxWidth: Int = 720, format: String = "png", quality: Int = 80, timeoutMs: Long = 6000L): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return JSONObject().put("ok", false).put("error", "takeScreenshot needs Android 11+ (API 30); this device is API ${Build.VERSION.SDK_INT}")
                .put("hint", "use the ADB/Shizuku screencap fallback")
        }
        synchronized(shotLock) {
            // Platform limit: one screenshot per ~333 ms per service.
            val wait = 400L - (System.currentTimeMillis() - lastShotAt)
            if (wait > 0) Thread.sleep(wait)
            val latch = CountDownLatch(1)
            val bmp = AtomicReference<Bitmap?>(null)
            val errCode = AtomicReference<Int?>(null)
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        bmp.set(hw?.copy(Bitmap.Config.ARGB_8888, false))
                        hw?.recycle()
                    } catch (e: Exception) {
                        Log.w(TAG, "screenshot convert failed: ${e.message}")
                    } finally {
                        runCatching { result.hardwareBuffer.close() }
                        latch.countDown()
                    }
                }
                override fun onFailure(errorCode: Int) { errCode.set(errorCode); latch.countDown() }
            })
            val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            lastShotAt = System.currentTimeMillis()
            val full = bmp.get()
            if (!done || full == null) {
                return JSONObject().put("ok", false)
                    .put("error", if (!done) "screenshot timeout" else "takeScreenshot failed (code ${errCode.get()})")
                    .put("hint", "code 1=internal 2=no capability (re-enable the accessibility service) 3=interval too short 4=invalid display")
            }
            val mw = maxWidth.coerceIn(160, 1440)
            val scaled = if (full.width > mw) {
                Bitmap.createScaledBitmap(full, mw, (full.height.toLong() * mw / full.width).toInt(), true)
            } else full
            val jpeg = format.equals("jpeg", true) || format.equals("jpg", true)
            val out = ByteArrayOutputStream()
            scaled.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                quality.coerceIn(10, 100), out)
            val res = JSONObject().put("ok", true).put("via", "a11y.takeScreenshot")
                .put("mime", if (jpeg) "image/jpeg" else "image/png")
                .put("width", scaled.width).put("height", scaled.height)
                .put("source_width", full.width).put("source_height", full.height)
                .put("bytes", out.size())
                .put("image_base64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            if (scaled !== full) scaled.recycle()
            full.recycle()
            return res
        }
    }

    /** Window node tree as nested JSON (text, desc, id, class, bounds, clickable, focused…). */
    fun uiTree(maxNodes: Int = 400, allWindows: Boolean = false): JSONObject {
        val counter = intArrayOf(0)
        val limit = maxNodes.coerceIn(1, 3000)
        val out = JSONArray()
        val roots = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        if (allWindows) {
            runCatching { windows }.getOrNull()?.forEach { w ->
                w.root?.let { roots.add("window:${w.id}:${w.title ?: ""}:type${w.type}" to it) }
            }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add("active" to it) }
        for ((label, root) in roots) {
            if (counter[0] >= limit) break
            out.put(JSONObject().put("window", label).put("package", root.packageName?.toString().orEmpty())
                .put("root", treeNode(root, counter, limit, 0)))
            recycleQuietly(root)
        }
        return JSONObject().put("ok", roots.isNotEmpty()).put("via", "a11y")
            .put("node_count", counter[0]).put("truncated", counter[0] >= limit)
            .put("windows", out)
            .also { if (roots.isEmpty()) it.put("error", "no active window root (screen off or secure?)") }
    }

    private fun treeNode(n: AccessibilityNodeInfo, counter: IntArray, limit: Int, depth: Int): JSONObject {
        counter[0]++
        val b = Rect(); n.getBoundsInScreen(b)
        val o = JSONObject()
            .put("text", n.text?.toString().orEmpty())
            .put("desc", n.contentDescription?.toString().orEmpty())
            .put("id", n.viewIdResourceName.orEmpty())
            .put("class", n.className?.toString()?.substringAfterLast('.').orEmpty())
            .put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
            .put("clickable", n.isClickable)
            .put("focused", n.isFocused || n.isAccessibilityFocused)
        if (n.isEditable) o.put("editable", true)
        if (n.isScrollable) o.put("scrollable", true)
        if (n.isCheckable) o.put("checked", n.isChecked)
        if (!n.isEnabled) o.put("enabled", false)
        if (n.childCount > 0 && depth < 60) {
            val kids = JSONArray()
            for (i in 0 until n.childCount) {
                if (counter[0] >= limit) break
                val c = n.getChild(i) ?: continue
                try { kids.put(treeNode(c, counter, limit, depth + 1)) } finally { recycleQuietly(c) }
            }
            if (kids.length() > 0) o.put("children", kids)
        }
        return o
    }

    /** BACK / HOME / RECENTS / NOTIFICATIONS / QUICK_SETTINGS. */
    fun globalAction(name: String): JSONObject {
        val code = when (name.trim().uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS", "APP_SWITCH" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return JSONObject().put("ok", false).put("error", "action must be BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS")
        }
        return JSONObject().put("ok", performGlobalAction(code)).put("via", "a11y.performGlobalAction").put("action", name.uppercase())
    }

    /** ACTION_SET_TEXT on the focused input (or first editable). Replaces the field's text. */
    fun setTextFocused(text: String): JSONObject {
        val ok = inputText(text)
        return JSONObject().put("ok", ok).put("via", "a11y.ACTION_SET_TEXT").put("len", text.length)
            .also { if (!ok) it.put("error", "no focused/editable field on screen, or it is a password/OTP/card field (refused)") }
    }

    /**
     * Find a node whose text or content description matches [text] (contains, case-insensitive; or exact) and click
     * it: ACTION_CLICK on the nearest clickable ancestor, else a tap gesture at the node's centre.
     */
    fun clickText(text: String, exact: Boolean = false, index: Int = 0): JSONObject {
        val q = text.trim()
        if (q.isEmpty()) return JSONObject().put("ok", false).put("error", "text required")
        val root = rootInActiveWindow ?: return JSONObject().put("ok", false).put("error", "no active window")
        val matches = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectMatches(root, q, exact, matches, 50)
            if (matches.isEmpty()) return JSONObject().put("ok", false).put("error", "no node matching \"$q\"")
            val node = matches[index.coerceIn(0, matches.size - 1)]
            val label = (node.text ?: node.contentDescription)?.toString().orEmpty()
            val b = Rect(); node.getBoundsInScreen(b)
            var target: AccessibilityNodeInfo? = node
            var depth = 0
            while (target != null && depth < 8) {
                if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return JSONObject().put("ok", true).put("via", "a11y.ACTION_CLICK").put("matched", label)
                        .put("matches", matches.size).put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
                }
                target = target.parent; depth++
            }
            val ok = tap(b.exactCenterX(), b.exactCenterY())
            return JSONObject().put("ok", ok).put("via", "a11y.dispatchGesture(center)").put("matched", label)
                .put("matches", matches.size).put("x", b.centerX()).put("y", b.centerY())
        } finally {
            matches.forEach { recycleQuietly(it) }
            recycleQuietly(root)
        }
    }

    private fun collectMatches(n: AccessibilityNodeInfo, q: String, exact: Boolean, out: MutableList<AccessibilityNodeInfo>, max: Int) {
        if (out.size >= max) return
        val t = n.text?.toString().orEmpty(); val d = n.contentDescription?.toString().orEmpty()
        val hit = if (exact) t.equals(q, true) || d.equals(q, true) else t.contains(q, true) || d.contains(q, true)
        if (hit && n.isVisibleToUser) out.add(AccessibilityNodeInfo.obtain(n))
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            try { collectMatches(c, q, exact, out, max) } finally { recycleQuietly(c) }
        }
    }

    private fun dispatchGestureSync(gesture: GestureDescription, timeoutMs: Long = 3000L): Boolean {
        // 1.8.6: never block the main/UI thread waiting on a gesture — MCP callers already
        // run on BrokerServer worker threads; if somehow invoked on main, refuse the wait.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "dispatchGestureSync refused on main thread")
            return false
        }
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    ok.set(false)
                    latch.countDown()
                }
            },
            null
        )
        if (!accepted) return false
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) && ok.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: JSONArray, maxNodes: Int) {
        if (out.length() >= maxNodes) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val obj = JSONObject()
            .put("text", node.text?.toString().orEmpty())
            .put("desc", node.contentDescription?.toString().orEmpty())
            .put("class", node.className?.toString().orEmpty())
            .put("viewId", node.viewIdResourceName.orEmpty())
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("bounds", JSONObject()
                .put("l", bounds.left)
                .put("t", bounds.top)
                .put("r", bounds.right)
                .put("b", bounds.bottom))
        out.put(obj)
        for (i in 0 until node.childCount) {
            if (out.length() >= maxNodes) break
            val child = node.getChild(i) ?: continue
            try {
                collectNodes(child, out, maxNodes)
            } finally {
                recycleQuietly(child)
            }
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            recycleQuietly(child)
            if (found != null) return found
        }
        return null
    }

    private fun recycleQuietly(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "AgentA11y"

        private val shotLock = Any()
        @Volatile private var lastShotAt = 0L
        private val shotExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "A11yShot").apply { isDaemon = true } }

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
