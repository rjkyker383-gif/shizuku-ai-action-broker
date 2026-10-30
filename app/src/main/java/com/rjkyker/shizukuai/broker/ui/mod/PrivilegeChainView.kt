package com.rjkyker.shizukuai.broker.ui.mod

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator

/** Shizuku -> Dhizuku -> priv server (uid 2000) -> broker. A packet travels the live part of the chain. */
class PrivilegeChainView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    val labels = listOf("SHIZUKU", "DHIZUKU", "PRIV 2000", "BROKER")
    private var states: List<Boolean?> = listOf(null, null, null, null)
    private val node = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val link = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * d; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var anim: ValueAnimator? = null

    fun setStates(s: List<Boolean?>) { states = s; invalidate() }

    fun start() {
        if (anim != null || !ModFx.animationsOn(context)) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start()
        }
    }

    fun stop() { anim?.cancel(); anim = null; invalidate() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (84 * d).toInt())

    private fun colorFor(s: Boolean?) = when (s) { true -> ModFx.GREEN; false -> ModFx.RED; null -> ModFx.DIM }

    override fun onDraw(c: Canvas) {
        val n = labels.size
        val r = 13 * d
        val cy = 30 * d
        val gap = (width - 2 * 28 * d) / (n - 1)
        val xs = (0 until n).map { 28 * d + it * gap }
        // live prefix length: packet travels until the first non-green node
        val live = states.indexOfFirst { it != true }.let { if (it < 0) n else it }
        for (i in 0 until n - 1) {
            val ok = i + 1 < live
            link.color = if (ok) ModFx.withAlpha(ModFx.GREEN, 170) else ModFx.withAlpha(ModFx.DIM, 120)
            c.drawLine(xs[i] + r, cy, xs[i + 1] - r, cy, link)
        }
        for (i in 0 until n) {
            val col = colorFor(states[i])
            glow.color = ModFx.withAlpha(col, 40); c.drawCircle(xs[i], cy, r * 1.6f, glow)
            glow.color = ModFx.withAlpha(col, 70); c.drawCircle(xs[i], cy, r * 1.2f, glow)
            node.color = col; c.drawCircle(xs[i], cy, r, node)
            text.color = col; c.drawText(labels[i], xs[i], cy + r + 18 * d, text)
            val glyph = when (states[i]) { true -> "✓"; false -> "✕"; null -> "…" }
            c.drawText(glyph, xs[i], cy + 4 * d, text)
        }
        if (anim != null && live >= 2) {
            val span = xs[live - 1] - xs[0]
            val px = xs[0] + span * phase
            dot.color = ModFx.CYAN
            c.drawCircle(px, cy, 4 * d, dot)
            dot.color = ModFx.withAlpha(ModFx.CYAN, 60); c.drawCircle(px, cy, 8 * d, dot)
        }
    }
}
