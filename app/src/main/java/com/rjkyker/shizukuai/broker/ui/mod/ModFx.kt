package com.rjkyker.shizukuai.broker.ui.mod

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Shared HUD palette + cheap View-property animations. Every effect is skipped when animations are off. */
object ModFx {
    const val BG = 0xFF05070D.toInt()
    const val PANEL = 0xCC0A1020.toInt()
    const val CYAN = 0xFF22E5FF.toInt()
    const val GREEN = 0xFF39FF88.toInt()
    const val AMBER = 0xFFFFB020.toInt()
    const val RED = 0xFFFF3B5C.toInt()
    const val DIM = 0xFF5A6B85.toInt()
    const val TEXT = 0xFFD8F6FF.toInt()

    /** Honors Settings > Accessibility > Remove animations (animator scale 0) and developer scales. */
    fun animationsOn(ctx: Context): Boolean = ValueAnimator.areAnimatorsEnabled() &&
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f

    fun tierColor(tier: String): Int = when (tier.uppercase()) {
        "READ", "LOW" -> CYAN
        "MEDIUM", "NORMAL" -> GREEN
        "HIGH" -> AMBER
        "LOCKOUT" -> RED
        else -> DIM
    }

    fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    fun pulse(v: View): Animator? {
        if (!animationsOn(v.context)) { v.alpha = 0.7f; return null }
        return ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.45f).apply {
            duration = 420; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
    }

    fun settle(v: View, a: Animator?) { a?.cancel(); v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f }

    fun burst(v: View) {
        if (!animationsOn(v.context)) return
        AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.08f, 1f), ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 1.08f, 1f))
            duration = 320; interpolator = DecelerateInterpolator(); start()
        }
    }

    fun shake(v: View) {
        if (!animationsOn(v.context)) return
        val d = 10f * v.resources.displayMetrics.density
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -d, d, -d * 0.6f, d * 0.6f, -d * 0.3f, 0f).apply { duration = 380; start() }
    }
}
