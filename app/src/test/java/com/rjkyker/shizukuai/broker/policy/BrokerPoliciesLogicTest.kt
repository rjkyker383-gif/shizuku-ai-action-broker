package com.rjkyker.shizukuai.broker.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks that don't need Android Context (locked list + host parsing helpers mirrored). */
class BrokerPoliciesLogicTest {
    @Test fun locked_denylist_covers_hard_rules() {
        val joined = BrokerPolicies.LOCKED_DENYLIST.joinToString(" ").lowercase()
        for (must in listOf("uninstall", "pm clear", "wipe", "dumpsys", "sms", "zebra_license", "factory")) {
            assertTrue(must, must in joined)
        }
    }
    @Test fun hardening_keys_are_documented() {
        assertTrue(BrokerPolicies.SKIP_SENSITIVE in BrokerPolicies.OWNER_TOGGLEABLE)
        assertTrue(BrokerPolicies.PREVIEW_FIRST in BrokerPolicies.OWNER_TOGGLEABLE)
        assertEquals(true, BrokerPolicies.TOGGLE_DEFAULTS[BrokerPolicies.SKIP_SENSITIVE])
        assertEquals(true, BrokerPolicies.TOGGLE_DEFAULTS[BrokerPolicies.PREVIEW_FIRST])
    }
    @Test fun host_cleaner_rejects_traversal_shapes() {
        // Mirror of setHostAllowlist cleaning (unit-level)
        fun clean(hosts: Set<String>): Set<String> = hosts.map {
            it.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        }.filter { it.isNotEmpty() && !it.contains(' ') && !it.contains("..") && !it.startsWith(".") }.toSet()
        assertEquals(setOf("example.com", "mcp.local"), clean(setOf("https://Example.com/mcp", "mcp.local", "../etc", ".hidden", "")))
        assertTrue(clean(setOf("ok.com")).isNotEmpty())
        assertFalse(".." in clean(setOf("a..b")).joinToString()) // "a..b" contains .. so filtered
    }
}
