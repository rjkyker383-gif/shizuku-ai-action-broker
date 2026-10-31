package com.rjkyker.shizukuai.broker.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionTextTest {
    @Test fun single_line_and_sentence() {
        assertEquals(" a little late.", CompletionText.clean("I'm running", " a little late. See you\nsoon", rawContinuation = true))
    }
    @Test fun instruct_output_gets_space_and_quotes_stripped() {
        assertEquals(" a little late", CompletionText.clean("I'm running", "\"a little late\"", rawContinuation = false))
    }
    @Test fun echo_removed() {
        assertEquals(" when you get home", CompletionText.clean("Call me", "Call me when you get home", rawContinuation = false))
    }
    @Test fun trailing_space_in_before_means_no_leading_space() {
        assertEquals("a little late", CompletionText.clean("I'm running ", " a little late", rawContinuation = true))
    }
    @Test fun capped() {
        val r = CompletionText.clean("x", " " + (1..40).joinToString(" ") { "word" }, rawContinuation = true)
        assertTrue(r.split(' ').filter { it.isNotEmpty() }.size <= CompletionText.MAX_WORDS)
        assertTrue(r.length <= CompletionText.MAX_CHARS)
    }
}
