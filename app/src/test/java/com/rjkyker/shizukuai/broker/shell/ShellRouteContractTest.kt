package com.rjkyker.shizukuai.broker.shell

import com.rjkyker.shizukuai.broker.mcp.McpToolExecutor
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract: dual-route tool schema + denylist stay registered after 1.8.7 merge. */
class ShellRouteContractTest {

    @Test
    fun execShellToolListsAllRoutes() {
        val defs = McpToolExecutor.toolDefinitions()
        var found = false
        for (i in 0 until defs.length()) {
            val o = defs.getJSONObject(i)
            if (o.getString("name") != McpToolExecutor.TOOL_EXEC_SHELL) continue
            found = true
            val schema = o.getJSONObject("inputSchema")
            val props = schema.getJSONObject("properties")
            val route = props.getJSONObject("route")
            val values = route.getJSONArray("enum")
            val set = (0 until values.length()).map { values.getString(it) }.toSet()
            assertTrue("missing auto", "auto" in set)
            assertTrue("missing shizuku", "shizuku" in set)
            assertTrue("missing termux", "termux" in set)
            assertTrue("missing local", "local" in set)
            assertTrue("missing upstream", "upstream" in set)
            assertTrue(props.has("confirm"))
            assertTrue(o.getString("description").contains("confirm=true") ||
                o.getString("description").contains("confirm"))
        }
        assertTrue("exec_shell tool missing", found)
    }

    @Test
    fun denylistIsSharedObject() {
        // Same rules used by Termux + Shizuku paths
        assertTrue(ShellDenylist.check("echo ok").allowed)
        assertTrue(!ShellDenylist.check("pm uninstall com.x").allowed)
    }
}
