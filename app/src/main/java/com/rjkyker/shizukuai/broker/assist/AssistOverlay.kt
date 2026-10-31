package com.rjkyker.shizukuai.broker.assist

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.rjkyker.shizukuai.broker.ui.mod.ModFx

/**
 * Mod-style floating suggestion chip + optional Assist bubble. TYPE_ACCESSIBILITY_OVERLAY, never focusable, so the
 * app keeps input focus and the keyboard stays up. Main thread only.
 */
class AssistOverlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val dp = svc.resources.displayMetrics.density
    private var chip: View? = null
    private var bubble: View? = null

    private fun params(w: Int = WindowManager.LayoutParams.WRAP_CONTENT) = WindowManager.LayoutParams(
        w, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun frame(color: Int) = GradientDrawable().apply {
        cornerRadius = 10 * dp; setColor(0xF005070D.toInt()); setStroke((1.5f * dp).toInt(), color)
    }

    private fun label(t: String, color: Int, sizeSp: Float, bold: Boolean = false) = TextView(svc).apply {
        text = t; setTextColor(color); textSize = sizeSp; typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    fun showChip(anchor: Rect, suggestion: String, badge: String, remote: Boolean, onAccept: () -> Unit, onDismiss: () -> Unit) {
        hideChip()
        val accent = when { remote -> ModFx.AMBER; badge.startsWith("word") -> ModFx.GREEN; else -> ModFx.CYAN }
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = frame(accent); setPadding((10 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        }
        val badgeView = label(if (remote) "REMOTE" else if (badge.startsWith("word")) "WORD" else "AI", 0xFF05070D.toInt(), 10f, true).apply {
            background = GradientDrawable().apply { cornerRadius = 4 * dp; setColor(accent) }
            setPadding((5 * dp).toInt(), (1 * dp).toInt(), (5 * dp).toInt(), (1 * dp).toInt())
            contentDescription = badge
        }
        val text = label("…" + suggestion.trimStart(), ModFx.TEXT, 15f).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            maxWidth = (svc.resources.displayMetrics.widthPixels * 0.7f).toInt()
        }
        val insert = label("⏎ ins", accent, 12f, true).apply { setPadding((6 * dp).toInt(), 0, (8 * dp).toInt(), 0); contentDescription = "insert suggestion" }
        val close = label("✕", ModFx.DIM, 15f, true).apply { setPadding((6 * dp).toInt(), 0, (4 * dp).toInt(), 0); contentDescription = "dismiss" }
        row.addView(badgeView); row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); row.addView(insert); row.addView(close)
        row.setOnClickListener { onAccept() }
        insert.setOnClickListener { onAccept() }
        close.setOnClickListener { onDismiss() }
        row.contentDescription = "Suggestion ($badge): $suggestion. Tap to insert. Never sends."
        val p = params().apply {
            x = (anchor.left).coerceAtLeast((8 * dp).toInt())
            val h = (48 * dp).toInt()
            y = if (anchor.top - h - (6 * dp).toInt() > (24 * dp).toInt()) anchor.top - h - (6 * dp).toInt() else anchor.bottom + (6 * dp).toInt()
        }
        runCatching { wm.addView(row, p); chip = row; if (ModFx.animationsOn(svc)) { row.alpha = 0f; row.animate().alpha(1f).setDuration(140).start() } }
    }

    fun hideChip() { chip?.let { runCatching { wm.removeView(it) } }; chip = null }
    val chipShown get() = chip != null

    /** Persistent draggable neon bubble (TYPE_ACCESSIBILITY_OVERLAY: needs no overlay permission). */
    fun showBubble(onTap: () -> Unit) {
        bubble?.let { it.visibility = View.VISIBLE; return }
        val b = label("\u2726", ModFx.CYAN, 20f, true).apply {
            gravity = Gravity.CENTER; background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xE005070D.toInt()); setStroke((2 * dp).toInt(), ModFx.CYAN)
            }
            contentDescription = "Assist: suggest a completion for the focused field"
            elevation = 6 * dp
        }
        val size = (46 * dp).toInt()
        val dm = svc.resources.displayMetrics
        val saved = AssistSettings.bubblePos(svc)
        val p = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved?.first ?: (dm.widthPixels - size - (6 * dp).toInt())
            y = saved?.second ?: (dm.heightPixels * 0.30f).toInt()
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && dx * dx + dy * dy > (8 * dp) * (8 * dp)) moved = true
                    if (moved) {
                        p.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - size)
                        p.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - size)
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (moved) AssistSettings.setBubblePos(svc, p.x, p.y) else { v.performClick(); onTap() }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(b, p); bubble = b }
    }

    /** Hide (not remove) the bubble, e.g. while a password/OTP/card field is focused. */
    fun suspendBubble(hidden: Boolean) { bubble?.visibility = if (hidden) View.GONE else View.VISIBLE }
    val bubbleShown get() = bubble?.visibility == View.VISIBLE

    /** Short status chip (e.g. "no text field focused"); auto-hides. */
    fun flash(anchorY: Int, msg: String) {
        hideChip()
        val t = label(msg, ModFx.DIM, 13f).apply { background = frame(ModFx.DIM); setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt()) }
        val p = params().apply { x = (12 * dp).toInt(); y = anchorY }
        runCatching { wm.addView(t, p); chip = t; t.postDelayed({ if (chip === t) hideChip() }, 2200) }
    }

    fun hideBubble() { bubble?.let { runCatching { wm.removeView(it) } }; bubble = null }
    fun hideAll() { hideChip(); hideBubble() }
}
