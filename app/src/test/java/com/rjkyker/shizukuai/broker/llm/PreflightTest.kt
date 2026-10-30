package com.rjkyker.shizukuai.broker.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {
    private val MB = 1024L * 1024L
    private fun input(free: Long = 5000 * MB, avail: Long = 1400 * MB, thr: Long = 216 * MB, low: Boolean = false,
                      thermal: Int = 0, paused: Boolean = false, bat: Int = 80, chg: Boolean = false, have: Boolean = false) =
        PreflightInput(free, avail, thr, low, thermal, paused, bat, chg, true, have)

    @Test fun tc22StorageToday918MbFailsBothModelsWithExactShortfall() {
        val q = Preflight.evaluate(ModelCatalog.QWEN25_05B_Q4KM, input(free = 918 * MB))
        assertFalse(q.canDownload)
        assertEquals(ModelCatalog.requiredStorageBytes(ModelCatalog.QWEN25_05B_Q4KM) - 918 * MB, q.storageShortfallBytes)
        val s = Preflight.evaluate(ModelCatalog.SMOLLM2_360M_Q4_0, input(free = 918 * MB))
        assertFalse(s.canDownload)
        assertTrue(s.storageShortfallBytes in 1..(100 * MB))
    }

    @Test fun enoughStorageAllowsDownload() {
        assertTrue(Preflight.evaluate(ModelCatalog.QWEN25_05B_Q4KM, input(free = 1300 * MB)).canDownload)
        assertTrue(Preflight.evaluate(ModelCatalog.SMOLLM2_360M_Q4_0, input(free = 1000 * MB)).canDownload)
    }

    @Test fun loadNeedsRamHeadroomAndCoolDevice() {
        val spec = ModelCatalog.QWEN25_05B_Q4KM
        assertTrue(Preflight.evaluate(spec, input(have = true)).canLoad)
        assertFalse(Preflight.evaluate(spec, input(have = true, avail = 900 * MB)).canLoad)
        assertFalse(Preflight.evaluate(spec, input(have = true, low = true)).canLoad)
        assertFalse(Preflight.evaluate(spec, input(have = true, thermal = 2)).canLoad)
        assertFalse(Preflight.evaluate(spec, input(have = true, paused = true)).canLoad)
        assertFalse(Preflight.evaluate(spec, input(have = true, bat = 10)).canLoad)
        assertTrue(Preflight.evaluate(spec, input(have = true, bat = 10, chg = true)).canLoad)
        assertTrue(Preflight.evaluate(ModelCatalog.SMOLLM2_360M_Q4_0, input(have = true, avail = 900 * MB)).canLoad)
    }

    @Test fun catalogIsPinned() {
        for (m in ModelCatalog.ALL) {
            assertEquals(40, m.revision.length); assertEquals(64, m.sha256.length)
            assertTrue(m.url.startsWith("https://huggingface.co/${m.repo}/resolve/${m.revision}/"))
        }
    }
}
