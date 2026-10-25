package com.rjkyker.shizukuai.broker.datawedge

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanExplainerTest {

    private fun enrich(data: String, label: String = "LABEL-TYPE-QRCODE", aim: String? = null) =
        ScanParsers.enrich(data, label, "scanner", emptyList(), aim, null, ts = 1L)

    private fun meanings(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getString("meaning") }
    private fun row(a: JSONArray, field: String) = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("field") == field }

    @Test fun gs1AisGetPlainEnglish() {
        val e = enrich("]C101095011010209171727063010ABC123\u001D21SN42", "LABEL-TYPE-GS1-128", "]C1").getJSONArray("english")
        val gtin = row(e, "(01)")!!
        assertTrue(gtin.getString("meaning").contains("GTIN (global trade item number), like a UPC"))
        assertTrue(gtin.getString("meaning").contains("Check digit valid"))
        val exp = row(e, "(17)")!!
        assertEquals("270630", exp.getString("value"))
        assertTrue(exp.getString("meaning").startsWith("Expiration date"))
        assertTrue(exp.getString("meaning").contains("June 30, 2027"))
        assertTrue(row(e, "(10)")!!.getString("meaning").contains("lot"))
        assertEquals("SN42", row(e, "(21)")!!.getString("value"))
        assertTrue(meanings(e).any { it.contains("Group-separator") })
    }

    @Test fun gs1DateHelpers() {
        assertEquals("June 30, 2027", ScanExplainer.gs1Date("270630"))
        assertEquals("end of February 2026", ScanExplainer.gs1Date("260200"))
        assertNull(ScanExplainer.gs1Date("271330"))
        assertEquals("January 15, 1990", ScanExplainer.aamvaDate("01151990"))
        assertEquals("January 15, 1990", ScanExplainer.aamvaDate("19900115"))
    }

    @Test fun weightAiShowsDecimals() {
        val e = enrich("]C13102000150", "LABEL-TYPE-GS1-128", "]C1").getJSONArray("english")
        assertTrue(row(e, "(3102)")!!.getString("meaning").contains("Net weight in kilograms: 1.50"))
    }

    @Test fun plainUpcIsRetailNotGs1() {
        val j = enrich("036000291452", "LABEL-TYPE-UPCA")
        val e = j.getJSONArray("english")
        assertTrue(row(e, "Barcode type")!!.getString("meaning").contains("UPC-A"))
        assertTrue(row(e, "Product code (UPC-A)") != null)
        assertTrue(row(e, "Check digit")!!.getString("meaning").startsWith("Valid"))
        assertTrue(row(e, "GS1 prefix")!!.getString("meaning").contains("GS1 US"))
        // not mis-parsed as a GS1 element string
        val parsers = j.getJSONArray("parsers")
        assertFalse((0 until parsers.length()).any { parsers.getJSONObject(it).optString("format") == "GS1" })
    }

    @Test fun isbnPrefix() {
        val e = enrich("9780306406157", "LABEL-TYPE-EAN13").getJSONArray("english")
        assertTrue(row(e, "GS1 prefix")!!.getString("meaning").contains("book"))
    }

    @Test fun wifiQrExplainedWithoutLeakingPassword() {
        val e = enrich("WIFI:T:WPA;S:HomeNet;P:hunter22;;").getJSONArray("english")
        val m = row(e, "Wi-Fi")!!.getString("meaning")
        assertTrue(m.contains("Wi-Fi network credentials for \"HomeNet\""))
        assertTrue(m.contains("WPA/WPA2"))
        assertTrue(m.contains("8 characters"))
        assertFalse(m.contains("hunter22"))
    }

    @Test fun aamvaHumanLabels() {
        val data = "@\n\u001e\rANSI 636000100002DL00410278ZV03190008DLDAQT64235789\nDCSSAMPLE\nDACMICHAEL\nDBB01151990\nDBC1\nDAYBRO\n"
        val e = enrich(data, "LABEL-TYPE-PDF417").getJSONArray("english")
        assertEquals("SAMPLE", row(e, "DCS")!!.getString("value"))
        assertTrue(row(e, "DCS")!!.getString("meaning").startsWith("Last name"))
        assertTrue(row(e, "DBB")!!.getString("meaning").contains("January 15, 1990"))
        assertTrue(row(e, "DBC")!!.getString("meaning").contains("male"))
        assertTrue(row(e, "DAY")!!.getString("meaning").contains("brown"))
    }

    @Test fun urlAndUnknownNeverInvent() {
        val u = enrich("https://example.com/p?id=7").getJSONArray("english")
        assertTrue(row(u, "Web link")!!.getString("meaning").contains("example.com"))
        assertTrue(row(u, "Web link")!!.getString("meaning").contains("Not opened automatically"))
        val t = enrich("HELLO WORLD", "LABEL-TYPE-CODE128").getJSONArray("english")
        val c = row(t, "Content")!!
        assertEquals("HELLO WORLD", c.getString("value"))
        assertTrue(c.getString("meaning").contains("No standard structure detected"))
    }

    @Test fun emvPaymentQr() {
        val e = enrich("00020101021253038405802US5904Acme6004Reno6304ABCD").getJSONArray("english")
        assertTrue(row(e, "EMV 53")!!.getString("meaning").contains("US dollar"))
        assertTrue(row(e, "EMV 01")!!.getString("meaning").contains("dynamic"))
        assertTrue(row(e, "Payment QR")!!.getString("meaning").contains("nothing is paid"))
    }

    @Test fun everyAiInTableHasEnglish() {
        for ((ai, m) in ScanExplainer.GS1_AI_ENGLISH) assertTrue("AI $ai", m.length > 15)
        for ((c, m) in ScanExplainer.AAMVA_ENGLISH) assertTrue("AAMVA $c", m.isNotBlank())
    }
}
