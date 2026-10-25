package com.rjkyker.shizukuai.broker.ui

import android.content.Context
import com.rjkyker.shizukuai.broker.R

/**
 * Non-secret dashboard preferences (panel visibility + accent). Plain SharedPreferences on purpose:
 * nothing here is sensitive, and secrets stay in SecurePrefs.
 */
object UiPrefs {
    private const val NAME = "broker_ui_prefs"
    private const val KEY_ACCENT = "accent"
    private const val PANEL_PREFIX = "panel_visible_"

    data class Accent(val key: String, val label: String, val colorRes: Int)

    val ACCENTS = listOf(
        Accent("cyan", "Cyan", R.color.accent_cyan),
        Accent("amber", "Amber", R.color.accent_amber),
        Accent("magenta", "Magenta", R.color.accent_magenta),
        Accent("green", "Green", R.color.accent_green),
        Accent("red", "Red", R.color.accent_red)
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun accent(ctx: Context): Accent {
        val k = p(ctx).getString(KEY_ACCENT, "cyan")
        return ACCENTS.firstOrNull { it.key == k } ?: ACCENTS.first()
    }

    fun setAccent(ctx: Context, key: String) {
        p(ctx).edit().putString(KEY_ACCENT, key).apply()
    }

    fun isPanelVisible(ctx: Context, panel: String): Boolean = p(ctx).getBoolean(PANEL_PREFIX + panel, true)

    fun setPanelVisible(ctx: Context, panel: String, visible: Boolean) {
        p(ctx).edit().putBoolean(PANEL_PREFIX + panel, visible).apply()
    }
}
