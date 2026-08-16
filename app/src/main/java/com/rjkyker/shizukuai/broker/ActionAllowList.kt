package com.rjkyker.shizukuai.broker

object ActionAllowList {

    private val allowedActions = setOf(
        "GET_DEVICE_INFO",
        "GET_PACKAGE_LIST",
        "GET_CURRENT_APP",
        "OPEN_APP",
        "OPEN_SETTINGS",
        "GET_BATTERY_INFO",
        "GET_DISPLAY_INFO"
    )

    fun isAllowed(action: String): Boolean {
        return action in allowedActions
    }

    fun snapshot(): Set<String> {
        return allowedActions.toSet()
    }
}
