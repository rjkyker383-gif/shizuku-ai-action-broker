package com.rjkyker.shizukuai.broker.assist

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.rjkyker.shizukuai.broker.priv.PrivServiceClient
import org.json.JSONObject

/**
 * The focused editable field in whatever app is in front. Reads text + cursor, inserts at the cursor.
 * Never performs IME/Enter/Send actions or clicks anything: the owner always sends.
 */
object FocusedInput {
    data class Info(
        val node: AccessibilityNodeInfo, val pkg: String, val className: String, val viewId: String?, val hint: String?,
        val text: String, val selStart: Int, val selEnd: Int, val bounds: Rect, val blockReason: String?,
    ) {
        val before: String get() = text.substring(0, selStart.coerceIn(0, text.length))
        val after: String get() = text.substring(selEnd.coerceIn(selStart.coerceIn(0, text.length), text.length))
        fun meta(): JSONObject = JSONObject().put("package", pkg).put("class", className.substringAfterLast('.'))
            .put("view_id", viewId?.substringAfter(":id/")).put("hint", hint?.take(60)).put("length", text.length)
            .put("sel_start", selStart).put("sel_end", selEnd).put("blocked", blockReason != null).put("block_reason", blockReason)
            .put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
    }

    fun guardField(n: AccessibilityNodeInfo) = FieldGuard.Field(
        isPassword = n.isPassword, inputType = n.inputType, viewId = n.viewIdResourceName,
        hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null,
        contentDescription = n.contentDescription?.toString(),
    )

    fun findNode(svc: AccessibilityService): AccessibilityNodeInfo? {
        val f = runCatching { svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: runCatching { svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        return f?.takeIf { it.isEditable }
    }

    fun read(svc: AccessibilityService): Info? {
        val n = findNode(svc) ?: return null
        val showingHint = Build.VERSION.SDK_INT >= 26 && n.isShowingHintText
        val txt = if (showingHint) "" else n.text?.toString() ?: ""
        var s = n.textSelectionStart; var e = n.textSelectionEnd
        if (s < 0 || s > txt.length || e < 0 || e > txt.length) { s = txt.length; e = txt.length }
        if (e < s) { val t = s; s = e; e = t }
        val r = Rect(); n.getBoundsInScreen(r)
        return Info(n, n.packageName?.toString() ?: "", n.className?.toString() ?: "", n.viewIdResourceName,
            if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null, txt, s, e, r, FieldGuard.blockReason(guardField(n)))
    }

    private val SHELL_SAFE = Regex("^[A-Za-z0-9 .,!?-]{1,200}$")

    /** @param mode "append" inserts at the cursor (replacing any selection); "replace" replaces the whole field. */
    fun insert(svc: AccessibilityService, text: String, mode: String = "append", allowShell: Boolean = true): JSONObject {
        val info = read(svc) ?: return JSONObject().put("ok", false).put("error", "no focused editable field")
        val meta = JSONObject().put("package", info.pkg).put("len", text.length).put("mode", mode)
        if (info.blockReason != null) return meta.put("ok", false).put("error", "refused: ${info.blockReason}")
        if (text.isEmpty()) return meta.put("ok", false).put("error", "empty text")
        if (text.length > 2000) return meta.put("ok", false).put("error", "text too long (max 2000)")
        val n = info.node
        val replace = mode == "replace"
        val newText = if (replace) text else info.before + text + info.after
        val caret = if (replace) text.length else info.before.length + text.length
        // 1) ACTION_SET_TEXT
        val setOk = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText) })
        if (setOk) {
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            })
            n.refresh()
            if ((n.text?.toString() ?: "").contains(text)) return meta.put("ok", true).put("via", "a11y.ACTION_SET_TEXT")
        }
        // 2) clipboard + ACTION_PASTE at the cursor (overwrites the clipboard; noted in docs)
        val pasteOk = runCatching {
            val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("broker-assist", text))
            if (replace) n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, info.text.length)
            })
            n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
        if (pasteOk) return meta.put("ok", true).put("via", "a11y.ACTION_PASTE (clipboard)")
        // 3) last resort: shell `input text` (types only; never sends a key event like ENTER)
        if (allowShell && !replace && SHELL_SAFE.matches(text) && PrivServiceClient.isAlive()) {
            val arg = text.replace(" ", "%s")
            val r = PrivServiceClient.exec("input text '$arg'", 10)
            if (r.optBoolean("ok")) return meta.put("ok", true).put("via", "priv_shell input text")
        }
        return meta.put("ok", false).put("error", "field rejected SET_TEXT and PASTE").put("set_text", setOk)
    }
}
