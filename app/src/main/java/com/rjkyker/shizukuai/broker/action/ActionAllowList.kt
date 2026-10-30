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
        "scan_history" to RiskLevel.LOW,
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
        // MCP tool names — parity with POST /mcp tools/call; risk also enforced in McpToolExecutor
        "exec_shell" to RiskLevel.HIGH,
        "dump_ui_hierarchy" to RiskLevel.LOW,
        "a11y_screenshot" to RiskLevel.LOW,
        "a11y_ui_tree" to RiskLevel.LOW,
        "a11y_tap" to RiskLevel.MEDIUM,
        "a11y_swipe" to RiskLevel.MEDIUM,
        "a11y_text" to RiskLevel.MEDIUM,
        "a11y_global" to RiskLevel.MEDIUM,
        "a11y_click_text" to RiskLevel.MEDIUM,
        "perform_gesture" to RiskLevel.MEDIUM,
        "manage_dhizuku_policy" to RiskLevel.MEDIUM,
        "get_system_status" to RiskLevel.LOW,
        "upstream_mcp_relay" to RiskLevel.MEDIUM,
        "wedge_sync_control" to RiskLevel.MEDIUM,
        // 1.7.0 mesh / telemetry / sensing / no-license Zebra tools
        "bridge_relay" to RiskLevel.MEDIUM,
        "airtable_log" to RiskLevel.MEDIUM,
        "wifi_presence_scan" to RiskLevel.LOW,
        // 1.8.5 Wi-Fi detection (default on) + Hotspot / Extender
        "wifi_detection" to RiskLevel.LOW,
        "broker_pause" to RiskLevel.LOW,
        "broker_resume" to RiskLevel.LOW,
        "broker_pause_status" to RiskLevel.LOW,
        "hotspot_status" to RiskLevel.LOW,
        "hotspot_start" to RiskLevel.MEDIUM,
        "hotspot_stop" to RiskLevel.MEDIUM,
        "zebra_wifi_config" to RiskLevel.HIGH,
        "zebra_datawedge_profile" to RiskLevel.MEDIUM,
        "zebra_scanner_control" to RiskLevel.MEDIUM,
        // 1.8.0 AI discovery manifest, read-only
        "discovery_info" to RiskLevel.LOW,
        // 1.8.8 OwnDroid parity + privileged server + delegation; per-op tiers in OwnerPolicyCatalog
        "owner_policy" to RiskLevel.MEDIUM,
        "priv_service" to RiskLevel.MEDIUM,
        "client_delegation" to RiskLevel.MEDIUM,
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
