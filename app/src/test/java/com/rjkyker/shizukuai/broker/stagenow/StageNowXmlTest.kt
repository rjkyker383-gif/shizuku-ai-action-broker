package com.rjkyker.shizukuai.broker.stagenow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageNowXmlTest {
    @Test fun unlock_xml_has_accessmgr_full() {
        val p = StageNowXml.combinedUnlock()
        assertFalse(p.needsLicense)
        assertTrue(p.xml.contains("AccessMgr"))
        assertTrue(p.xml.contains("SystemSettings") && p.xml.contains("value=\"1\""))
        assertTrue(p.xml.contains("wap-provisioningdoc"))
        assertTrue(p.xml.contains("SettingsMgr"))
    }
    @Test fun appmgr_disable_escapes_only_safe_packages() {
        val p = StageNowXml.appMgrDisable("com.symbol.osx.osxprovisionservice")
        assertTrue(p.xml.contains("DisableApplication"))
        assertTrue(p.xml.contains("com.symbol.osx.osxprovisionservice"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun appmgr_rejects_injection() {
        StageNowXml.appMgrDisable("com.x\"/><characteristic")
    }
    @Test fun wifi_open_escapes_xml_specials() {
        val p = StageNowXml.wifiMgrAddOpen("Cafe & Bar <1>")
        assertTrue(p.xml, p.xml.contains("Cafe &amp; Bar &lt;1&gt;") || p.xml.contains("&amp;") && p.xml.contains("&lt;"))
        assertFalse(p.needsLicense)
    }
    @Test fun omitted_licensed_is_honest() {
        assertTrue(StageNowXml.omittedLicensed.any { it.contains("Mobility DNA", ignoreCase = true) || it.contains("OEMConfig") })
    }
}
