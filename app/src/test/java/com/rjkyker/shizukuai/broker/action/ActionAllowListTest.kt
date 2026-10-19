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
