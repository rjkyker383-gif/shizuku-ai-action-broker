package com.rjkyker.shizukuai.broker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.AppCompatButton

/**
 * Approve button that only a human finger can press:
 *  - touches delivered while another window overlaps (tapjacking) are dropped;
 *  - accessibility ACTION_CLICK (any a11y service, including the broker's own) is refused;
 *  - programmatic performClick() without a fresh genuine touch is ignored;
 *  - disabled for [armDelayMs] after being shown.
 */
class GuardedButton(context: Context) : AppCompatButton(context) {
    var armDelayMs = 1500L
    private var shownAt = SystemClock.uptimeMillis()
    private var lastGenuineUp = 0L
    var onGenuineClick: (() -> Unit)? = null

    init {
        filterTouchesWhenObscured = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun rearm() { shownAt = SystemClock.uptimeMillis() }

    override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean {
        val obscured = (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
            (event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        return !obscured && super.onFilterTouchEventForSecurity(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && event.getToolType(0) != MotionEvent.TOOL_TYPE_UNKNOWN) {
            lastGenuineUp = SystemClock.uptimeMillis()
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        val now = SystemClock.uptimeMillis()
        val genuine = now - lastGenuineUp < 1000 && now - shownAt >= armDelayMs
        lastGenuineUp = 0
        super.performClick()
        if (genuine) onGenuineClick?.invoke()
        return genuine
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) return false
        return super.performAccessibilityAction(action, arguments)
    }
}
