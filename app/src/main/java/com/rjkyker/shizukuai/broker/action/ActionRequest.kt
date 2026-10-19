package com.rjkyker.shizukuai.broker.action

/**
 * Structured action request. [riskLevel] is NOT caller-controlled: it is derived
 * from the authoritative [ActionAllowList] policy, and unknown actions fail closed to HIGH.
 */
data class ActionRequest(
    val action: String,
    val reason: String,
    val userVisibleSummary: String,
    val params: Map<String, String> = emptyMap()
) {
    val riskLevel: RiskLevel
        get() = ActionAllowList.riskLevelFor(action) ?: RiskLevel.HIGH
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
