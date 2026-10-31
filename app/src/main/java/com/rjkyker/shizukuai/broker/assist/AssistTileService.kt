package com.rjkyker.shizukuai.broker.assist

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService

/** Quick-settings "Assist" tile: closes the shade, then suggests a completion for the focused field. Never inserts by itself. */
class AssistTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (AgentAccessibilityService.instance != null && AssistSettings.enabled(this@AssistTileService)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (AgentAccessibilityService.instance == null) "a11y off" else "finish my sentence"
            updateTile()
        }
    }

    override fun onClick() {
        val svc = AgentAccessibilityService.instance
        if (svc == null) { Toast.makeText(this, "Enable the broker accessibility service first", Toast.LENGTH_LONG).show(); return }
        if (!AssistSettings.enabled(this)) { Toast.makeText(this, "Type-anywhere assist is off (broker → Assist settings)", Toast.LENGTH_LONG).show(); return }
        if (Build.VERSION.SDK_INT >= 31) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        AssistController.requestNow(delayMs = 700)
    }
}
