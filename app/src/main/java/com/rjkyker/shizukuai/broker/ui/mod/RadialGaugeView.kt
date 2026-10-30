package com.rjkyker.shizukuai.broker.ui.mod

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator

/** 270° arc gauge; colour shifts cyan -> amber -> red with load. Animates only when the value changes. */
class RadialGaugeView(ctx: Context, private val label: String) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var frac = 0f
    private var shown = 0f
    private var valueText = "--"
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND; color = ModFx.withAlpha(ModFx.DIM, 90) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 12 * d; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER; color = ModFx.TEXT }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9 * d; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER; color = ModFx.DIM }
    private val box = RectF()
    private var anim: ValueAnimator? = null

    fun set(fraction: Float, text: String) {
        frac = fraction.coerceIn(0f, 1f); valueText = text
        anim?.cancel()
        if (!ModFx.animationsOn(context) || !isShown) { shown = frac; invalidate(); return }
        anim = ValueAnimator.ofFloat(shown, frac).apply {
            duration = 650; interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, (s * 0.9f).toInt().coerceAtMost((120 * d).toInt())) }

    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat() - 16 * d
        val cx = width / 2f; val cy = height / 2f + 4 * d
        box.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        c.drawArc(box, 135f, 270f, false, track)
        val col = when { frac >= 0.9f -> ModFx.RED; frac >= 0.75f -> ModFx.AMBER; else -> ModFx.CYAN }
        halo.color = ModFx.withAlpha(col, 45); c.drawArc(box, 135f, 270f * shown, false, halo)
        arc.color = col; c.drawArc(box, 135f, 270f * shown, false, arc)
        c.drawText(valueText, cx, cy + 5 * d, big)
        c.drawText(label, cx, cy + size / 2 - 2 * d, small)
    }
}
