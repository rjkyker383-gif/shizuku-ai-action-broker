package com.rjkyker.shizukuai.broker.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheCleanerLogicTest {
    @Test fun trim_size_pattern() {
        val ok = Regex("""^\d+[KMG]?$""", RegexOption.IGNORE_CASE)
        assertTrue(ok.matches("2G")); assertTrue(ok.matches("500M")); assertTrue(ok.matches("1024"))
        assertFalse(ok.matches("2G; rm -rf /")); assertFalse(ok.matches("")); assertFalse(ok.matches("-2G"))
    }
}
