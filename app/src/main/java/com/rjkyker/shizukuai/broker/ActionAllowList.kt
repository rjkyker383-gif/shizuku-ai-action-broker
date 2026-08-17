package com.rjkyker.shizukuai.broker

object ActionAllowList {

    private val actionRiskLevels = mapOf(
        "GET_DEVICE_INFO" to RiskLevel.LOW,
        "GET_PACKAGE_LIST" to RiskLevel.LOW,
        "GET_CURRENT_APP" to RiskLevel.LOW,
        "OPEN_APP" to RiskLevel.MEDIUM,
        "OPEN_SETTINGS" to RiskLevel.MEDIUM,
        "GET_BATTERY_INFO" to RiskLevel.LOW,
        "GET_DISPLAY_INFO" to RiskLevel.LOW
    )

    fun isAllowed(action: String): Boolean {
        return action in actionRiskLevels
    }

    fun riskLevelFor(action: String): RiskLevel? {
        return actionRiskLevels[action]
    }
}
