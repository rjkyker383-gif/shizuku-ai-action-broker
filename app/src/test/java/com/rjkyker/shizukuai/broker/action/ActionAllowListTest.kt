package com.rjkyker.shizukuai.broker.action

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionAllowListTest {

    @Test
    fun routerOpsAreAllowed() {
        listOf(
            "tap", "swipe", "text", "key", "ui_dump", "shell",
            "admin_scopes", "admin_perm", "admin_suspend", "launch",
            "datawedge_status", "datawedge_version", "datawedge_profiles",
            "datawedge_active_profile", "datawedge_soft_scan", "datawedge_switch_profile"
        ).forEach { op ->
            assertTrue("$op should be allowed", ActionAllowList.isAllowed(op))
            assertNotNull(ActionAllowList.riskLevelFor(op))
        }
    }

    @Test
    fun mvpGetActionsAreAllowed() {
        assertTrue(ActionAllowList.isAllowed("GET_DEVICE_INFO"))
        assertTrue(ActionAllowList.isAllowed("OPEN_SETTINGS"))
        assertTrue(ActionAllowList.isAllowed("GET_BATTERY_INFO"))
    }

    @Test
    fun arbitraryExecutionAliasesRejected() {
        assertFalse(ActionAllowList.isAllowed("EXEC"))
        assertFalse(ActionAllowList.isAllowed("RUN_COMMAND"))
        assertFalse(ActionAllowList.isAllowed("rm -rf /"))
    }

    @Test
    fun snapshotMatchesRiskMap() {
        val snap = ActionAllowList.snapshot()
        assertTrue(snap.contains("tap"))
        assertTrue(snap.contains("shell"))
        assertTrue(snap.contains("GET_DEVICE_INFO"))
        assertTrue(snap.size >= 24)
    }

    @Test
    fun mcpToolNamesAreAllowed() {
        listOf(
            "exec_shell", "dump_ui_hierarchy", "perform_gesture",
            "manage_dhizuku_policy", "get_system_status",
            "upstream_mcp_relay", "wedge_sync_control"
        ).forEach { op ->
            assertTrue("$op should be allowed", ActionAllowList.isAllowed(op))
            assertNotNull(ActionAllowList.riskLevelFor(op))
        }
        assertTrue(ActionAllowList.riskLevelFor("exec_shell") == RiskLevel.HIGH)
    }
}

class DhizukuPolicyTest {
    @Test
    fun dhizukuOpsAllowedWithExpectedRisk() {
        assertTrue(ActionAllowList.riskLevelFor("dhizuku_status") == RiskLevel.LOW)
        assertTrue(ActionAllowList.riskLevelFor("dhizuku_connect") == RiskLevel.LOW)
        assertTrue(ActionAllowList.riskLevelFor("dhizuku_add_restriction") == RiskLevel.MEDIUM)
        assertTrue(ActionAllowList.riskLevelFor("dhizuku_hide_app") == RiskLevel.MEDIUM)
        assertTrue(ActionAllowList.riskLevelFor("dhizuku_suspend_app") == RiskLevel.MEDIUM)
        assertTrue(ActionAllowList.riskLevelFor("dhizuku_perm") == RiskLevel.HIGH)
        assertTrue(ActionAllowList.riskLevelFor("dhizuku_set_scopes") == RiskLevel.HIGH)
    }

    @Test
    fun destructiveDeviceOwnerOpsAreNotAllowed() {
        listOf(
            "dhizuku_wipe", "wipe_data", "dhizuku_reset_password", "reset_password",
            "dhizuku_remove_admin", "remove_active_admin", "clear_device_owner", "factory_reset"
        ).forEach { assertFalse("$it must not be allowed", ActionAllowList.isAllowed(it)) }
    }
}

class ActionRequestRiskTest {
    @Test
    fun riskIsDerivedFromPolicy() {
        assertTrue(ActionRequest("shell", "r", "s").riskLevel == RiskLevel.HIGH)
        assertTrue(ActionRequest("datawedge_query", "r", "s").riskLevel == RiskLevel.LOW)
    }

    @Test
    fun unknownActionsFailClosedHigh() {
        assertTrue(ActionRequest("NOT_A_REAL_ACTION", "r", "s").riskLevel == RiskLevel.HIGH)
    }
}

class MeshSensingZebraToolTest {
    @Test
    fun newToolsAllowedWithExpectedRisk() {
        assertTrue(ActionAllowList.riskLevelFor("bridge_relay") == RiskLevel.MEDIUM)
        assertTrue(ActionAllowList.riskLevelFor("airtable_log") == RiskLevel.MEDIUM)
        assertTrue(ActionAllowList.riskLevelFor("wifi_presence_scan") == RiskLevel.LOW)
        assertTrue(ActionAllowList.riskLevelFor("zebra_wifi_config") == RiskLevel.HIGH)
        assertTrue(ActionAllowList.riskLevelFor("zebra_datawedge_profile") == RiskLevel.MEDIUM)
        assertTrue(ActionAllowList.riskLevelFor("zebra_scanner_control") == RiskLevel.MEDIUM)
    }

    @Test
    fun newToolsAreAllowListed() {
        listOf(
            "bridge_relay", "airtable_log", "wifi_presence_scan",
            "zebra_wifi_config", "zebra_datawedge_profile", "zebra_scanner_control"
        ).forEach { assertTrue("$it should be allowed", ActionAllowList.isAllowed(it)) }
    }
}
