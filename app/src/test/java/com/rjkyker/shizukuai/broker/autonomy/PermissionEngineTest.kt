package com.rjkyker.shizukuai.broker.autonomy

import com.rjkyker.shizukuai.broker.action.ActionAllowList
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.PermissionEngine
import com.rjkyker.shizukuai.broker.autonomy.registry.Tier
import com.rjkyker.shizukuai.broker.mcp.McpToolExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionEngineTest {
    private val autonomous = listOf(Actor.PLANNER, Actor.SCHEDULER, Actor.WATCHDOG)

    @Test fun autonomousActorsNeverGetShellPolicyOrUiControl() {
        val dangerous = listOf("exec_shell", "priv_service", "owner_policy", "manage_dhizuku_policy", "a11y_tap",
            "a11y_text", "a11y_click_text", "perform_gesture", "zebra_wifi_config", "hotspot_start", "upstream_mcp_relay",
            "client_delegation", "llm_model", "autonomy_control", "task_submit")
        for (a in autonomous) for (t in dangerous) assertFalse("$a $t", PermissionEngine.check(a, t, confirmed = true).allowed)
    }

    @Test fun autonomousActorsGetOnlyReadAndDraftTools() {
        for (a in autonomous) {
            assertTrue(PermissionEngine.check(a, "llm_draft").allowed)
            assertTrue(PermissionEngine.check(a, "status_probe").allowed)
            assertTrue(PermissionEngine.check(a, "noop").allowed)
        }
    }

    @Test fun nobodyButTheDeviceOwnerCanApproveSendsOrAcceptMemory() {
        for (t in PermissionEngine.DEVICE_ONLY) {
            for (a in Actor.values().filter { it != Actor.USER_DEVICE }) {
                assertFalse("$a $t", PermissionEngine.check(a, t, confirmed = true).allowed)
            }
            assertTrue(PermissionEngine.check(Actor.USER_DEVICE, t).allowed)
        }
    }

    @Test fun sendRequestIsAlwaysPreviewNeedingUser() {
        for (a in Actor.values()) {
            val v = PermissionEngine.check(a, "send_request", confirmed = true)
            assertTrue("$a", v.allowed); assertTrue("$a", v.needsUser)
            assertEquals("ALLOW_PENDING_USER", v.decision)
        }
    }

    @Test fun mcpHighNeedsConfirm() {
        val v = PermissionEngine.check(Actor.MCP_AI, "exec_shell")
        assertFalse(v.allowed); assertTrue(v.needsConfirm)
        assertTrue(PermissionEngine.check(Actor.MCP_AI, "exec_shell", confirmed = true).allowed)
    }

    @Test fun unknownAndDestructiveToolsAreForbidden() {
        for (t in listOf("totally_unknown", "wipe_data", "factory_reset", "reset_password", "dhizuku_remove_admin", "zebra_license_apply")) {
            assertEquals(t, Tier.FORBIDDEN, PermissionEngine.tierOf(t))
            for (a in Actor.values()) assertFalse("$a $t", PermissionEngine.check(a, t, true).allowed)
        }
    }

    @Test fun every190McpToolIsRegisteredAllowListedAndExposed() {
        for (t in AutonomyMcp.TOOLS) {
            assertTrue(t, ActionAllowList.isAllowed(t))
            assertTrue(t, t in McpToolExecutor.TOOL_NAMES)
            assertTrue(t, t in PermissionEngine.REGISTRY)
        }
        assertEquals(AutonomyMcp.TOOLS, AutonomyMcp.definitions().map { it.getString("name") }.toSet())
        assertEquals(McpToolExecutor.TOOL_NAMES.size, McpToolExecutor.toolDefinitions().length())
    }
}
