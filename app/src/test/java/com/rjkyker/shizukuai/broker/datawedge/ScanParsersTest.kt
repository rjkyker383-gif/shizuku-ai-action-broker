package com.rjkyker.shizukuai.broker.datawedge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanParsersTest {

    @Test
    fun controlCharRendering() {
        val raw = "A\u001DB\r\nC\u0000D"
        val v = ScanParsers.renderVisible(raw)
        assertTrue(v.contains("<GS>"))
        assertTrue(v.contains("<CR>"))
        assertTrue(v.contains("<LF>"))
        assertTrue(v.contains("<NUL>"))
        assertTrue(v.contains("A"))
        assertTrue(v.contains("B"))
    }

    @Test
    fun gs1Sample() {
        // AI 01 GTIN-14 + AI 17 expiry + AI 10 lot, GS separator before lot
        val gtin = "00123456789012" // 14 digits w/ placeholder check — use valid:
        // Valid GTIN-14: 00012345678905 (check digit computed)
        val data = "01000123456789051725010110LOT42"
        // Better known-valid: 01095011010209171719050810ABCD1234 with GS before variable AI 10 if needed
        val sample = "0109501101020917\u001D1725071010LOT99"
        val parsed = ScanParsers.parseGs1(sample)
        assertNotNull(parsed)
        assertEquals("GS1", parsed!!.getString("format"))
        val ais = parsed.getJSONArray("application_identifiers")
        assertTrue(ais.length() >= 1)
        val first = ais.getJSONObject(0)
        assertEquals("01", first.getString("ai"))
    }

    @Test
    fun gtinCheckDigit() {
        // 9501101020917 is a well-known GTIN-13; as GTIN-14 padded: 09501101020917
        assertTrue(ScanParsers.gtinCheckDigitOk("09501101020917") || ScanParsers.gtinCheckDigitOk("9501101020917"))
        assertFalse(ScanParsers.gtinCheckDigitOk("123"))
    }

    @Test
    fun iso15434Sample() {
        val sample = "[)>\u001E06\u001D12ABC\u001D21SERIAL\u001E\u0004"
        val p = ScanParsers.parseIso15434(sample)
        assertNotNull(p)
        assertEquals("ISO/IEC 15434", p!!.getString("format"))
        assertEquals("06", p.getString("envelope_format"))
    }

    @Test
    fun wifiQr() {
        val p = ScanParsers.parseWifiQr("WIFI:T:WPA;S:MyNet;P:secret;;")
        assertNotNull(p)
        assertEquals("WIFI", p!!.getString("format"))
        assertEquals("MyNet", p.getJSONObject("fields").getString("ssid"))
        assertTrue(p.getString("note").contains("never auto-connect"))
    }

    @Test
    fun vcard() {
        val p = ScanParsers.parseVcard("BEGIN:VCARD\nFN:RJ\nTEL:555\nEND:VCARD")
        assertNotNull(p)
        assertEquals("vCard", p!!.getString("format"))
        assertEquals("RJ", p.getJSONObject("fields").getString("FN"))
    }

    @Test
    fun enrichIncludesParsersAndHex() {
        val bytes = "HELLO\u001DWORLD".toByteArray(Charsets.ISO_8859_1)
        val j = ScanParsers.enrich(
            dataString = "HELLO\u001DWORLD",
            labelType = "LABEL-TYPE-CODE128",
            source = "scanner",
            decodeSegments = listOf(bytes),
            aimId = "]C1",
            decodedMode = "single_decode"
        )
        assertEquals("scanner", j.getString("source"))
        assertTrue(j.getString("visible_text").contains("<GS>"))
        assertTrue(j.getJSONArray("decode_data").length() >= 1)
        assertTrue(j.getJSONArray("decode_data").getJSONObject(0).getString("hex_dump").contains("48"))
        assertTrue(j.getJSONArray("parsers").length() >= 1)
    }
}
