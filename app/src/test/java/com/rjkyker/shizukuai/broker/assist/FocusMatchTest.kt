package com.rjkyker.shizukuai.broker.assist

import com.rjkyker.shizukuai.broker.assist.FocusMatch.Sig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMatchTest {
    private val a = Sig("com.app", "com.app:id/compose", 30, 1500, 900, 7)

    @Test fun same_node_or_same_view_id_matches_even_if_node_object_changed() {
        assertTrue(FocusMatch.same(a, a.copy(), nodeEqual = false))
        assertTrue(FocusMatch.same(a, a.copy(top = 1380, right = 1000), nodeEqual = false)) // grew as text was typed
        assertTrue(FocusMatch.same(a, a.copy(viewId = "x", top = 200), nodeEqual = true))
    }
    @Test fun no_view_id_falls_back_to_window_and_position() {
        val b = a.copy(viewId = null)
        assertTrue(FocusMatch.same(b, b.copy(top = 1470), nodeEqual = false))
        assertFalse(FocusMatch.same(b, b.copy(top = 400), nodeEqual = false))   // a different field
        assertFalse(FocusMatch.same(b, b.copy(windowId = 9), nodeEqual = false))
    }
    @Test fun app_change_never_matches() {
        assertFalse(FocusMatch.same(a, a.copy(pkg = "com.other"), nodeEqual = true))
    }
    @Test fun own_overlay_and_ime_events_ignored_but_own_fields_kept() {
        val own = "com.rjkyker.shizukuai.broker"
        assertTrue(FocusMatch.ignoreEvent(own, own, "com.google.android.inputmethod.latin", false, "android.widget.LinearLayout"))
        assertTrue(FocusMatch.ignoreEvent(own, own, null, false, "android.widget.TextView"))
        assertTrue(FocusMatch.ignoreEvent("com.google.android.inputmethod.latin", own, "com.google.android.inputmethod.latin", true, "x"))
        assertFalse(FocusMatch.ignoreEvent(own, own, null, true, "android.widget.EditText"))           // Writing Assistant field
        assertFalse(FocusMatch.ignoreEvent(own, own, null, false, "$own.ui.WritingAssistantActivity"))  // our activity
        assertFalse(FocusMatch.ignoreEvent("com.facebook.orca", own, null, false, "android.widget.FrameLayout"))
        assertTrue(FocusMatch.ignoreEvent(null, own, null, false, null))
    }
}
