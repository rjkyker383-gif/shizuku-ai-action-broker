package com.rjkyker.shizukuai.broker.perf

import com.rjkyker.shizukuai.broker.mcp.McpToolExecutor
import com.rjkyker.shizukuai.broker.sensing.WifiPresenceEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.6 performance hardening contracts. */
class PerfHardening186Test {

    @Test
    fun pauseToolsAreRegistered() {
        val names = McpToolExecutor.TOOL_NAMES
        assertTrue(names.contains(McpToolExecutor.TOOL_BROKER_PAUSE))
        assertTrue(names.contains(McpToolExecutor.TOOL_BROKER_RESUME))
        assertTrue(names.contains(McpToolExecutor.TOOL_BROKER_PAUSE_STATUS))
    }

    @Test
    fun toolDefinitionsIncludePause() {
        val defs = McpToolExecutor.toolDefinitions()
        val found = mutableSetOf<String>()
        for (i in 0 until defs.length()) {
            found.add(defs.getJSONObject(i).getString("name"))
        }
        assertTrue(found.contains("broker_pause"))
        assertTrue(found.contains("broker_resume"))
        assertTrue(found.contains("broker_pause_status"))
    }

    @Test
    fun wifiPresenceNoLongerUsesDumpsysWifi() {
        assertTrue(WifiPresenceEngine.DUMPSYS_WIFI_DISABLED)
    }

    @Test
    fun wifiDetectionAndPresenceToolsStillPresent() {
        assertTrue(McpToolExecutor.TOOL_NAMES.contains(McpToolExecutor.TOOL_WIFI_PRESENCE))
        assertTrue(McpToolExecutor.TOOL_NAMES.contains(McpToolExecutor.TOOL_WIFI_DETECTION))
    }

    @Test
    fun unknownToolStillRejected() {
        assertFalse(McpToolExecutor.TOOL_NAMES.contains("not_a_real_tool"))
    }
}
