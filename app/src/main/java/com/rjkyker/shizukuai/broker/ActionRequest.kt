package com.rjkyker.shizukuai.broker

data class ActionRequest(
    val action: String,
    val reason: String,
    val userVisibleSummary: String,
    val riskLevel: RiskLevel,
    val params: Map<String, String> = emptyMap()
)

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
