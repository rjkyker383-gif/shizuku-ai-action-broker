package com.rjkyker.shizukuai.broker.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuseVoiceClientTest {
    private val base = "https://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse"

    @Test fun pendingReplyIsNull() {
        assertNull(MuseVoiceClient.parseReply(JSONObject("""{"ok":true,"id":"abc","reply":"","pending":true}""")))
    }

    @Test fun replyWithMuseAudioAndVoice() {
        val r = MuseVoiceClient.parseReply(JSONObject("""{"ok":true,"id":"abc123","reply":"Hey RJ.","from":"muse",
            "pending":false,"audio_url":"$base/voice/audio/abc123.mp3","voice":"Jordan (avocado_v2:MAI_03)"}"""))!!
        assertEquals("abc123", r.id)
        assertEquals("Hey RJ.", r.text)
        assertEquals("$base/voice/audio/abc123.mp3", r.audioUrl)
        assertEquals("Muse voice — Jordan", r.voiceLabel)
    }

    @Test fun textOnlyReplyDefaultsToStaticLabelAndNoAudio() {
        val r = MuseVoiceClient.parseReply(JSONObject("""{"id":"x","reply":"hi"}"""))!!
        assertNull(r.audioUrl); assertNull(r.voice)
        assertEquals("Muse voice — Static", r.voiceLabel)
        assertEquals("muse", r.from)
    }

    @Test fun foreignOrPlainHttpAudioIsDropped() {
        val evil = MuseVoiceClient.parseReply(JSONObject("""{"reply":"hi","audio_url":"https://evil.example.com/a.mp3"}"""))!!
        assertNull(evil.audioUrl)
        val http = MuseVoiceClient.parseReply(JSONObject("""{"reply":"hi","audio_url":"http://presocial-endocrinopathic-fumiko.ngrok-free.dev/muse/voice/audio/a.mp3"}"""))!!
        assertNull(http.audioUrl)
        val userinfo = MuseVoiceClient.parseReply(JSONObject("""{"reply":"hi","audio_url":"https://u:p@presocial-endocrinopathic-fumiko.ngrok-free.dev/a.mp3"}"""))!!
        assertNull(userinfo.audioUrl)
    }

    @Test fun mp3Sniffing() {
        assertTrue(MuseVoiceClient.looksLikeMp3("ID3\u0004".toByteArray(Charsets.ISO_8859_1)))
        assertTrue(MuseVoiceClient.looksLikeMp3(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)))
        assertFalse(MuseVoiceClient.looksLikeMp3("<htm".toByteArray()))
        assertFalse(MuseVoiceClient.looksLikeMp3(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 0))) // reserved layer
    }

    @Test fun voiceNameShortening() {
        assertEquals("Morgan", MuseVoiceClient.shortVoiceName("Morgan (avocado_v2:casper)"))
        assertEquals("Static", MuseVoiceClient.shortVoiceName(null))
        assertEquals("Ronan", MuseVoiceClient.shortVoiceName("Ronan"))
    }

    @Test fun voicePickerNormalizesToKnownMaleVoices() {
        assertEquals("Static", MuseVoiceClient.DEFAULT_VOICE)
        assertEquals("Static", MuseVoiceClient.normalizeVoice(null))
        assertEquals("Static", MuseVoiceClient.normalizeVoice("avocado_v2:vdc_3944"))
        assertEquals("Morgan", MuseVoiceClient.normalizeVoice("morgan"))
        assertEquals("Jordan", MuseVoiceClient.normalizeVoice("avocado_v2:MAI_03"))
        assertEquals("Static", MuseVoiceClient.normalizeVoice("some-female-voice"))
        assertEquals(6, MuseVoiceClient.MUSE_VOICES.size)
        assertEquals("Static", MuseVoiceClient.MUSE_VOICES.keys.first())
    }
}
