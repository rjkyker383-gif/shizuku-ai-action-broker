package com.rjkyker.shizukuai.broker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionAllowListTest {

    @Test
    fun knownActionsAreAllowed() {
        assertTrue(ActionAllowList.isAllowed("GET_DEVICE_INFO"))
        assertTrue(ActionAllowList.isAllowed("OPEN_SETTINGS"))
    }

    @Test
    fun arbitraryExecutionIsNotAllowed() {
        assertFalse(ActionAllowList.isAllowed("SHELL"))
        assertFalse(ActionAllowList.isAllowed("EXEC"))
        assertFalse(ActionAllowList.isAllowed("RUN_COMMAND"))
    }

    @Test
    fun initialAllowListRemainsNarrow() {
        assertEquals(7, ActionAllowList.snapshot().size)
    }
}
