package com.rjkyker.shizukuai.broker.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TypeCommandTest {
    @Test fun spaces_and_metachars_escaped() {
        assertEquals("input text hi%sthere", TypeCommand.command("hi there"))
        assertEquals("input text a\\;b", TypeCommand.command("a;b"))
        assertEquals("input text \\\$\\(x\\)", TypeCommand.command("\$(x)"))
        assertEquals("input text it\\'s", TypeCommand.command("it's"))
        assertEquals("input text \\\"q\\\"\\`\\|\\&\\*\\~\\<\\>\\\\", TypeCommand.command("\"q\"`|&*~<>\\"))
    }
    @Test fun every_printable_ascii_char_produces_allowed_command() {
        for (c in 0x20..0x7e) {
            val cmd = TypeCommand.command(c.toChar().toString())
            assertTrue("char ${c.toChar()}", cmd != null && TypeCommand.isAllowed(cmd))
        }
    }
    @Test fun non_ascii_and_control_refused() {
        assertNull(TypeCommand.command("é")); assertNull(TypeCommand.command("😀")); assertNull(TypeCommand.command("a\nb"))
    }
    @Test fun allowlist_is_narrow() {
        listOf("input keyevent 66", "input tap 1 2", "input swipe 1 2 3 4", "input text a; reboot", "input text a b",
            "input text \$(id)", "sh -c 'input text a'", "input text a\nkeyevent 66", "input text ").forEach { assertFalse(it, TypeCommand.isAllowed(it)) }
    }
    @Test fun sanitize_never_keeps_newlines() {
        assertEquals("line one line two", TypeCommand.sanitize("line one\nline two"))
        assertFalse(TypeCommand.sanitize("a\r\n\tb\u2028c").any { it == '\n' || it == '\r' })
    }
    @Test fun chunks_cover_text_in_1_to_3_chars_and_isolate_emoji() {
        val t = "Thanks so much, really! 😀 ok"
        repeat(20) { seed ->
            val ch = TypeCommand.chunks(t, Random(seed))
            assertEquals(t, ch.joinToString(""))
            ch.filter { it.all(TypeCommand::isTypeable) }.forEach { assertTrue(it, it.length in 1..3) }
            assertTrue(ch.any { !it.all(TypeCommand::isTypeable) })
        }
    }
    @Test fun rhythm_in_range_and_pauses_on_punctuation() {
        val r = Random(1)
        repeat(200) { val d = TypeCommand.delayMs("ab", 1.0, r); assertTrue("$d", d in 35..90) }
        repeat(50) { assertTrue(TypeCommand.delayMs("a.", 1.0, r) >= 215) }
        assertTrue(TypeCommand.delayMs("ab", 3.0, Random(2)) < TypeCommand.delayMs("ab", 0.25, Random(2)))
    }
}
