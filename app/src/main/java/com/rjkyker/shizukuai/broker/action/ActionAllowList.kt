package com.rjkyker.shizukuai.broker.action

/**
 * Authoritative allow-list + risk policy covering ActionRouter ops and MVP GET_* / OPEN_* actions.
 * Keep in sync with schemas/action.schema.json (enforced by tests/verify_contract.py).
 * Note: avoid parentheses inside the mapOf body comments; the contract verifier parses it.
 */
object ActionAllowList {

    private val actionRiskLevels = mapOf(
        // ActionRouter ops: lowercase, as used by BrokerServer JSON
        "tap" to RiskLevel.MEDIUM,
        "swipe" to RiskLevel.MEDIUM,
        "text" to RiskLevel.MEDIUM,
        "key" to RiskLevel.MEDIUM,
        "ui_dump" to RiskLevel.LOW,
        "uidump" to RiskLevel.LOW,
        "snapshot" to RiskLevel.LOW,
        "shell" to RiskLevel.HIGH,
        "admin_scopes" to RiskLevel.LOW,
        "scopes" to RiskLevel.LOW,
        "admin_perm" to RiskLevel.HIGH,
        "perm" to RiskLevel.HIGH,
        "admin_suspend" to RiskLevel.HIGH,
        "suspend" to RiskLevel.HIGH,
        "launch" to RiskLevel.MEDIUM,
        // DataWedge for Zebra/Symbol: soft-fail when DW absent
        "datawedge_status" to RiskLevel.LOW,
        "datawedge_version" to RiskLevel.LOW,
        "datawedge_profiles" to RiskLevel.LOW,
        "datawedge_active_profile" to RiskLevel.LOW,
        "datawedge_soft_scan" to RiskLevel.MEDIUM,
        "datawedge_switch_profile" to RiskLevel.MEDIUM,
        "datawedge_create_profile" to RiskLevel.MEDIUM,
        "datawedge_set_config" to RiskLevel.MEDIUM,
        "datawedge_last" to RiskLevel.LOW,
        "datawedge_query" to RiskLevel.LOW,
        "datawedge_results" to RiskLevel.LOW,
        // Dhizuku device-owner ops, 1.5.0: non-destructive and reversible only
        "dhizuku_status" to RiskLevel.LOW,
        "dhizuku_connect" to RiskLevel.LOW,
        "dhizuku_scopes" to RiskLevel.LOW,
        "dhizuku_add_restriction" to RiskLevel.MEDIUM,
        "dhizuku_clear_restriction" to RiskLevel.MEDIUM,
        "dhizuku_hide_app" to RiskLevel.MEDIUM,
        "dhizuku_suspend_app" to RiskLevel.MEDIUM,
        "dhizuku_perm" to RiskLevel.HIGH,
        "dhizuku_set_scopes" to RiskLevel.HIGH,
        // MVP structured GET_* / OPEN_* actions
        "GET_DEVICE_INFO" to RiskLevel.LOW,
        "GET_PACKAGE_LIST" to RiskLevel.LOW,
        "GET_CURRENT_APP" to RiskLevel.LOW,
        "OPEN_APP" to RiskLevel.MEDIUM,
        "OPEN_SETTINGS" to RiskLevel.MEDIUM,
        "GET_BATTERY_INFO" to RiskLevel.LOW,
        "GET_DISPLAY_INFO" to RiskLevel.LOW,
    )

    fun isAllowed(action: String): Boolean {
        val key = action.trim()
        return key in actionRiskLevels || key.lowercase() in actionRiskLevels
    }

    fun riskLevelFor(action: String): RiskLevel? {
        val key = action.trim()
        return actionRiskLevels[key] ?: actionRiskLevels[key.lowercase()]
    }

    /** Back-compat alias for [riskLevelFor]. */
    fun riskLevel(action: String): RiskLevel? = riskLevelFor(action)

    fun snapshot(): Set<String> = actionRiskLevels.keys.toSet()
}
