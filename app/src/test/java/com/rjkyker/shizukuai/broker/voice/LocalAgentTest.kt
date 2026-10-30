package com.rjkyker.shizukuai.broker.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentTest {
    private val f = LocalAgent.Facts(true, listOf("127.0.0.1", "100.95.242.123"), true, true,
        listOf("UPC-A retail product barcode", "A retail product number (GTIN)."), "5:30 PM")

    @Test fun statusAnsweredLocally() {
        val a = LocalAgent.answer("what's the broker status", f)!!
        assertTrue(a.contains("running")); assertTrue(a.contains("100.95.242.123"))
    }

    @Test fun lastScanMeaning() {
        assertTrue(LocalAgent.answer("what does my last scan mean", f)!!.contains("GTIN"))
        assertTrue(LocalAgent.answer("what did I scan", f.copy(lastScanEnglish = emptyList()))!!.contains("don't have any scans"))
    }

    @Test fun otherQuestionsGoToMuse() {
        assertNull(LocalAgent.answer("write me a plan to upgrade the app", f))
        assertNull(LocalAgent.answer("", f))
    }

    @Test fun stopPhrases() {
        assertTrue(LocalAgent.isStopPhrase("Stop."))
        assertTrue(LocalAgent.isStopPhrase("goodbye"))
        assertFalse(LocalAgent.isStopPhrase("stop the scanner profile from resetting"))
    }

    @Test fun museClientOnlyAllowsOwnHttpsHost() {
        val c = MuseVoiceClient { "" }
        assertTrue(c.isAllowed("https://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse/voice"))
        assertFalse(c.isAllowed("http://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse/voice"))
        assertFalse(c.isAllowed("https://evil.example.com/muse/voice"))
        assertFalse(c.isAllowed("https://presocial-endocrinopathic-fumiko.ngrok-free.dev.evil.com/x"))
    }
}
