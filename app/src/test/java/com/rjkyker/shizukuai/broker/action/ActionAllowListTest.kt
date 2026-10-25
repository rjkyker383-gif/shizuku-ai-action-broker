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
            "datawedge_active_profile", "datawedge_soft_scan", "datawedge_switch_profile",
            "barcode_inspect", "barcode_transform_preview", "barcode_command_preview",
            "barcode_plan_instruction",
            "barcode_catalog_lookup", "barcode_catalog_update", "barcode_label_generate"
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
        assertTrue(snap.contains("barcode_inspect"))
        assertTrue(snap.contains("barcode_catalog_update"))
        assertTrue(snap.size >= 30)
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
