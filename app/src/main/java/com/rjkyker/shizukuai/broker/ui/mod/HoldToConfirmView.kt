package com.rjkyker.shizukuai.broker.ui.mod

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Hold-to-confirm ring for HIGH ops. Progress is wall-clock based (SystemClock), NOT animator based, so the
 * "remove animations" setting can never shorten the hold. Obscured touches and accessibility clicks are refused.
 */
class HoldToConfirmView(ctx: Context, private val color: Int, var holdMs: Long = 1600L) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var downAt = 0L
    private var progress = 0f
    private var done = false
    var onConfirmed: (() -> Unit)? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7 * d; strokeCap = Paint.Cap.ROUND }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7 * d; color = ModFx.withAlpha(color, 50) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * d; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val box = RectF()

    init { filterTouchesWhenObscured = true; contentDescription = "Press and hold with your finger to confirm" }

    override fun onMeasure(w: Int, h: Int) { val s = (150 * d).toInt(); setMeasuredDimension(s, s) }

    private val tick = object : Runnable {
        override fun run() {
            if (downAt == 0L || done) return
            progress = ((SystemClock.uptimeMillis() - downAt).toFloat() / holdMs).coerceAtMost(1f)
            invalidate()
            if (progress >= 1f) { done = true; performHapticFeedback(HAPTIC_FEEDBACK_ENABLED); onConfirmed?.invoke() }
            else postOnAnimation(this)
        }
    }

    override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean {
        val obscured = (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
            (event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        return !obscured && super.onFilterTouchEventForSecurity(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (done) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downAt = SystemClock.uptimeMillis(); postOnAnimation(tick) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { downAt = 0L; removeCallbacks(tick); progress = 0f; invalidate() }
        }
        return true
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) return false
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDraw(c: Canvas) {
        val pad = 12 * d
        box.set(pad, pad, width - pad, height - pad)
        fill.color = ModFx.withAlpha(color, (30 + 90 * progress).toInt())
        c.drawCircle(width / 2f, height / 2f, (width / 2f - pad) * (0.55f + 0.35f * progress), fill)
        c.drawArc(box, 0f, 360f, false, track)
        ring.color = color; c.drawArc(box, -90f, 360f * progress, false, ring)
        txt.color = if (done) ModFx.GREEN else ModFx.TEXT
        c.drawText(if (done) "ARMED" else "HOLD", width / 2f, height / 2f + 4 * d, txt)
    }
}
