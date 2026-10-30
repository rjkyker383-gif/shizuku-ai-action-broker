package com.rjkyker.shizukuai.broker.hotspot

import com.rjkyker.shizukuai.broker.mcp.McpToolExecutor
import com.rjkyker.shizukuai.broker.sensing.WifiDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotAndWifiTest {
    @Test fun passphraseIsLongAndRandom() {
        val a = HotspotManager.generatePassphrase(); val b = HotspotManager.generatePassphrase()
        assertTrue(a.length >= 12); assertTrue(a != b)
        assertEquals(a.length, HotspotManager.mask(a).length)
        assertFalse(HotspotManager.mask(a).contains(a.substring(2, a.length - 2)))
    }

    @Test fun softApCommandShape() {
        assertEquals("cmd wifi start-softap 'TC22-Extender' wpa2 'abcdEFGH2345' -b 2",
            HotspotManager.softApCommand("TC22-Extender", "wpa2", "abcdEFGH2345", "2"))
        assertTrue(HotspotManager.softApCommand("X", "wpa3", "abcdEFGH2345", "any").endsWith("'abcdEFGH2345'"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInjectionInSsid() {
        HotspotManager.softApCommand("a'; reboot; '", "wpa2", "abcdEFGH2345", "2")
    }

    @Test fun parsers() {
        assertEquals(2, HotspotManager.parseClientCount("mConnectedClients size=2"))
        assertEquals(0, HotspotManager.parseClientCount("mConnectedClients=[]"))
        assertEquals(2437, HotspotManager.parseApFrequency("mCurrentSoftApInfo: SoftApInfo{bandwidth= 2, frequency= 2437,"))
        assertEquals("wlan0", HotspotManager.parseTetherUpstream("Current upstream interface(s): [wlan0]"))
        assertEquals("", HotspotManager.parseTetherUpstream("nothing here"))
    }

    @Test fun channels() {
        assertEquals(6, WifiDetection.channelOf(2437)); assertEquals(36, WifiDetection.channelOf(5180))
        assertEquals("5GHz", WifiDetection.bandOf(5180)); assertEquals("good", WifiDetection.strengthLabel(-60))
        assertTrue(WifiDetection.DEFAULT_ENABLED)
    }

    @Test fun toolsRegistered() {
        for (t in listOf("wifi_detection", "hotspot_start", "hotspot_stop", "hotspot_status"))
            assertTrue(t, t in McpToolExecutor.TOOL_NAMES)
    }
}
