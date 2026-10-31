package com.rjkyker.shizukuai.broker.assist

import kotlin.math.abs

/** Pure: is the field focused now "the same field" we started typing into? Tolerant of node-id churn. */
object FocusMatch {
    data class Sig(val pkg: String, val viewId: String?, val left: Int, val top: Int, val right: Int, val windowId: Int)

    fun same(a: Sig, b: Sig, nodeEqual: Boolean): Boolean {
        if (a.pkg != b.pkg) return false
        if (nodeEqual) return true
        if (!a.viewId.isNullOrBlank() && a.viewId == b.viewId) return true
        // same window and roughly the same place (fields grow downward/rightward as text is added)
        return a.windowId == b.windowId && abs(a.left - b.left) <= 24 && abs(a.top - b.top) <= 48 && abs(a.right - b.right) <= 48
    }

    /** Events we must ignore: our own overlay windows (chip/bubble) and the keyboard. */
    fun ignoreEvent(eventPkg: String?, ownPkg: String, imePkg: String?, sourceEditable: Boolean, className: String?): Boolean {
        if (eventPkg == null) return true
        if (imePkg != null && eventPkg == imePkg) return true
        if (eventPkg == ownPkg && !sourceEditable && (className == null || className.startsWith("android.widget.") ||
                className.startsWith("android.view."))) return true
        return false
    }
}
