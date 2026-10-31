package com.rjkyker.shizukuai.broker.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class WordPredictorTest {
    companion object {
        lateinit var wp: WordPredictor
        @BeforeClass @JvmStatic fun load() {
            val f = listOf("src/main/assets/predict/en_model.tsv.gz", "app/src/main/assets/predict/en_model.tsv.gz").map(::File).first { it.exists() }
            assertTrue("asset under 3 MB", f.length() < 3_000_000)
            wp = f.inputStream().use { WordPredictor.load(it) }
        }
    }

    @Test fun completes_current_word() {
        val p = wp.predict("I'll see you tomo")!!
        assertEquals("rrow", p.wordCompletion)
        assertTrue(p.insert.startsWith("rrow"))
    }
    @Test fun next_words_after_space() {
        val p = wp.predict("I'm on my ")
        assertNotNull(p); assertTrue(p!!.insert, p.insert.startsWith("way"))
        assertTrue(p.nextWords.size in 1..3)
    }
    @Test fun phrase_prediction() {
        val p = wp.predict("thanks for letting me ")!!
        assertTrue(p.insert, p.insert.startsWith("know"))
    }
    @Test fun adds_space_when_needed() {
        val p = wp.predict("see you")!!
        // "you" is a complete word -> continuation starts with a space
        assertTrue(p.insert, p.insert.startsWith(" ") || p.wordCompletion.isNotEmpty())
    }
    @Test fun i_is_capitalised() {
        val p = wp.predict("yes ")
        if (p != null) assertTrue(p.insert, !p.insert.split(' ').contains("i"))
    }
    @Test fun blank_returns_null() { assertNull(wp.predict("   ")) }
    @Test fun learning_boosts_user_phrases() {
        wp.learn("zebra scanner calibration")
        val p = wp.predict("zebra ")!!
        assertTrue(p.insert, p.insert.startsWith("scanner"))
        wp.clearUser()
    }
}
