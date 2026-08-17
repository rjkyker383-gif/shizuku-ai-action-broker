package com.rjkyker.shizukuai.broker

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
