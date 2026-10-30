package com.rjkyker.shizukuai.broker.ui.mod

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/** Dark grid + slow scanline sweep. Static grid when animations are off, paused, or the screen is off. */
class HudBackgroundView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val grid = Paint().apply { color = ModFx.withAlpha(ModFx.CYAN, 26); strokeWidth = 1f }
    private val major = Paint().apply { color = ModFx.withAlpha(ModFx.CYAN, 48); strokeWidth = 1f }
    private val scan = Paint()
    private var phase = 0f
    private var anim: ValueAnimator? = null

    init { setBackgroundColor(ModFx.BG) }

    fun start() {
        if (anim != null || !ModFx.animationsOn(context)) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 7000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun stop() { anim?.cancel(); anim = null }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val step = 22 * d
        var i = 0
        var x = 0f
        while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), if (i++ % 4 == 0) major else grid); x += step }
        i = 0; var y = 0f
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, if (i++ % 4 == 0) major else grid); y += step }
        if (anim != null) {
            val band = 90 * d
            val sy = phase * (height + band) - band
            scan.shader = LinearGradient(0f, sy, 0f, sy + band, intArrayOf(0, ModFx.withAlpha(ModFx.CYAN, 38), 0), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, sy, width.toFloat(), sy + band, scan)
        }
    }
}
