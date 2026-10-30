package com.rjkyker.shizukuai.broker.ui.mod

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView

/** Terminal-style ticker. Lines type out (~600 chars/s) when animations are on; otherwise appear at once. */
class TerminalLogView(ctx: Context) : TextView(ctx) {
    private val h = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Pair<String, Int>>()
    private var typing = false
    private val buf = SpannableStringBuilder()
    private var lines = 0

    init {
        typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(ModFx.GREEN)
        setBackgroundColor(0xE6000000.toInt()); val p = (8 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
    }

    fun log(line: String, color: Int = ModFx.GREEN) {
        pending.addLast(line.take(400) to color)
        if (!typing) next()
    }

    private fun next() {
        val (line, color) = pending.removeFirstOrNull() ?: run { typing = false; return }
        typing = true
        if (lines >= MAX_LINES) trimFirst()
        val start = buf.length
        if (!ModFx.animationsOn(context) || pending.size > 6) {
            buf.append(line).append('\n'); color(start, color); lines++; text = buf; next(); return
        }
        var i = 0
        val step = object : Runnable {
            override fun run() {
                val n = minOf(line.length - i, 12)
                buf.append(line, i, i + n); i += n
                color(start, color); text = buf
                if (i < line.length) h.postDelayed(this, 20) else { buf.append('\n'); lines++; text = buf; next() }
            }
        }
        h.post(step)
    }

    private fun color(start: Int, c: Int) = buf.setSpan(ForegroundColorSpan(c), start, buf.length, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)

    private fun trimFirst() { val nl = buf.indexOf('\n'); if (nl >= 0) { buf.delete(0, nl + 1); lines-- } }

    override fun onDetachedFromWindow() { h.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }

    companion object { const val MAX_LINES = 120 }
}
