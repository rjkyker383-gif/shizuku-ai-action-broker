package com.rjkyker.shizukuai.broker.mcp

import com.rjkyker.shizukuai.broker.action.ActionAllowList
import com.rjkyker.shizukuai.broker.action.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yToolsTest {
    @Test fun allA11yToolsRegisteredDefinedAndRiskRated() {
        val defs = McpToolExecutor.toolDefinitions()
        val names = (0 until defs.length()).map { defs.getJSONObject(it).getString("name") }.toSet()
        for (t in McpToolExecutor.A11Y_TOOLS) {
            assertTrue("$t in TOOL_NAMES", t in McpToolExecutor.TOOL_NAMES)
            assertTrue("$t defined", t in names)
            assertTrue("$t rated", ActionAllowList.riskLevelFor(t) != null)
        }
        assertEquals(7, McpToolExecutor.A11Y_TOOLS.size)
        assertEquals(RiskLevel.LOW, ActionAllowList.riskLevelFor("a11y_screenshot"))
        assertEquals(RiskLevel.LOW, ActionAllowList.riskLevelFor("a11y_ui_tree"))
        assertEquals(RiskLevel.MEDIUM, ActionAllowList.riskLevelFor("a11y_tap"))
    }
}
