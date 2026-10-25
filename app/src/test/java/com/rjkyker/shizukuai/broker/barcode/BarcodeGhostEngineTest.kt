package com.rjkyker.shizukuai.broker.barcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeGhostEngineTest {
    @Test
    fun inspectionPreservesInputAndChecksGtin() {
        val scan = "036000291452"
        val result = BarcodeGhostEngine.inspect(scan, "UPC-A", "unit-test")
        assertEquals(scan, result.originalValue)
        assertEquals(12, result.characterCount)
        assertEquals("303336303030323931343532", result.rawByteHex)
        assertTrue(result.looksLikeGtin)
        assertEquals(true, result.gtinCheckDigitValid)
    }

    @Test
    fun inspectionShowsGroupSeparatorAndProvidedBytes() {
        val value = "01ABC\u001D10LOT"
        val result = BarcodeGhostEngine.inspect(value, "GS1-128", "test", byteArrayOf(0x01, 0x02))
        assertTrue(result.hasGs1GroupSeparator)
        assertTrue(result.rawBytesProvided)
        assertEquals("0102", result.rawByteHex)
        assertEquals(2, result.utf8ByteCount)
    }

    @Test
    fun transformationsAreLiteralAndDeterministic() {
        assertEquals("RJ-abc", BarcodeGhostEngine.transform("abc", "prefix", "RJ-").output)
        assertEquals("A-9", BarcodeGhostEngine.transform("A-8", "replace_first", "8", "9").output)
        assertEquals("A_B_C", BarcodeGhostEngine.transform("A B C", "replace_all", " ", "_").output)
        assertEquals("MIXED", BarcodeGhostEngine.transform("mixed", "uppercase").output)
    }

    @Test
    fun unsupportedOrUnsafeTransformationsFailClosed() {
        assertFalse(BarcodeGhostEngine.transform("abc", "execute", "anything").success)
        assertFalse(BarcodeGhostEngine.transform("abc", "replace_all", "", "x").success)
        assertFalse(BarcodeGhostEngine.transform("abc", "strip_prefix", "z").success)
        assertNull(BarcodeGhostEngine.transform("abc", "execute").output)
    }

    @Test
    fun naturalLanguagePlannerReturnsAnUnappliedPreview() {
        val result = BarcodeAiPlanner.plan("12345", "Add RJ- to the beginning")
        assertTrue(result.optBoolean("planned"))
        assertFalse(result.optBoolean("applied"))
        assertEquals("RJ-12345", result.optString("preview_output"))
        assertEquals("prefix", result.optJSONObject("proposal")?.optString("operation"))
    }

    @Test
    fun naturalLanguagePlannerDoesNotGuessUnsupportedInstructions() {
        val result = BarcodeAiPlanner.plan("12345", "Synchronize with the warehouse")
        assertFalse(result.optBoolean("ok"))
        assertFalse(result.optBoolean("planned"))
        assertFalse(result.optBoolean("applied"))
    }

    @Test
    fun onlyRegisteredBgxCommandsAreAccepted() {
        val good = BarcodeGhostEngine.parseCommand("BGX:1|TYPE=GAME|ID=NEON_MAZE|VERSION=1")
        assertTrue(good is CommandParseResult.Valid)
        assertEquals("NEON_MAZE", (good as CommandParseResult.Valid).command.id)
        val egg = BarcodeGhostEngine.parseCommand("BGX:1|TYPE=EASTER_EGG|ID=GHOST_MODE|VERSION=1")
        assertTrue(egg is CommandParseResult.Valid)
    }

    @Test
    fun malformedAndUnregisteredCommandsAreRejected() {
        assertTrue(BarcodeGhostEngine.parseCommand("plain text") is CommandParseResult.NotCommand)
        assertTrue(BarcodeGhostEngine.parseCommand("BGX:2|TYPE=GAME|ID=NEON_MAZE|VERSION=2") is CommandParseResult.Invalid)
        assertTrue(BarcodeGhostEngine.parseCommand("BGX:1|TYPE=GAME|ID=SHELL|VERSION=1") is CommandParseResult.Invalid)
        assertTrue(BarcodeGhostEngine.parseCommand("BGX:1|TYPE=GAME|TYPE=GAME|ID=NEON_MAZE|VERSION=1") is CommandParseResult.Invalid)
    }
}
