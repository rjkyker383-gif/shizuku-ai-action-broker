package com.rjkyker.shizukuai.broker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility-backed UI automation: snapshot, tap, swipe, text input, back/home.
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AgentAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event-driven hooks not required for broker snapshot/actions.
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentAccessibilityService interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
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

    private fun dispatchGestureSync(gesture: GestureDescription, timeoutMs: Long = 3000L): Boolean {
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

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
