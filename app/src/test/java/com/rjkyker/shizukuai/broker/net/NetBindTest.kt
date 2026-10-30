package com.rjkyker.shizukuai.broker.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBindTest {
    private val ifaces = listOf(
        NetBind.Iface("wlan0", true, listOf("192.168.1.44")),
        NetBind.Iface("tun0", true, listOf("100.95.242.123")),
        NetBind.Iface("rmnet0", true, listOf("10.20.30.40")),
        NetBind.Iface("tun1", false, listOf("100.70.1.1")),
        NetBind.Iface("lo", true, listOf("127.0.0.1")),
    )

    @Test fun picksOnlyUpTailscaleCgnat() {
        assertEquals(listOf("100.95.242.123"), NetBind.tailscaleIps(ifaces))
    }

    @Test fun bindSetIsLoopbackPlusTailscaleNeverWildcardOrLan() {
        val b = NetBind.bindAddresses(NetBind.tailscaleIps(ifaces))
        assertEquals(listOf("127.0.0.1", "100.95.242.123"), b)
        assertFalse(b.contains("0.0.0.0")); assertFalse(b.contains("192.168.1.44")); assertFalse(b.contains("10.20.30.40"))
        assertEquals(listOf("127.0.0.1"), NetBind.bindAddresses(emptyList()))
        assertEquals(listOf("127.0.0.1"), NetBind.bindAddresses(listOf("192.168.1.5", "0.0.0.0")))
    }

    @Test fun allowedBind() {
        assertTrue(NetBind.isAllowedBind("127.0.0.1"))
        assertTrue(NetBind.isAllowedBind("100.64.0.1"))
        assertTrue(NetBind.isAllowedBind("100.127.255.254"))
        assertFalse(NetBind.isAllowedBind("100.128.0.1"))
        assertFalse(NetBind.isAllowedBind("0.0.0.0"))
        assertFalse(NetBind.isAllowedBind("192.168.0.2"))
    }

    @Test fun rebindDetection() {
        assertFalse(NetBind.needsRebind(listOf("127.0.0.1", "100.95.242.123"), listOf("100.95.242.123", "127.0.0.1")))
        assertTrue(NetBind.needsRebind(listOf("127.0.0.1"), listOf("127.0.0.1", "100.95.242.123")))
        assertTrue(NetBind.needsRebind(listOf("127.0.0.1", "100.95.242.123"), listOf("127.0.0.1", "100.95.1.9")))
        assertEquals("http://100.95.242.123:17999", NetBind.baseUrl("100.95.242.123", 17999))
    }
}
