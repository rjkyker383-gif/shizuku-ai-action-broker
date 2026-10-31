package com.rjkyker.shizukuai.broker.assist

import com.rjkyker.shizukuai.broker.assist.FieldGuard.Field
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldGuardTest {
    @Test fun plain_text_allowed() {
        assertTrue(FieldGuard.allowed(Field(inputType = 0x00000001 or 0x00020000, viewId = "com.x:id/message_input", hint = "Message")))
    }
    @Test fun password_flags_blocked() {
        assertFalse(FieldGuard.allowed(Field(isPassword = true)))
        assertFalse(FieldGuard.allowed(Field(inputType = 0x81)))   // text|password
        assertFalse(FieldGuard.allowed(Field(inputType = 0x91)))   // visible password
        assertFalse(FieldGuard.allowed(Field(inputType = 0xe1)))   // web password
        assertFalse(FieldGuard.allowed(Field(inputType = 0x12)))   // number|password (PIN)
    }
    @Test fun sensitive_labels_blocked() {
        listOf("com.bank:id/otp_input", "Enter verification code", "Card number", "CVV", "pin_entry", "2FA code",
            "One-time passcode", "Security code").forEach { assertNotNull(it, FieldGuard.blockReason(Field(hint = it))) }
        assertFalse(FieldGuard.allowed(Field(autofillHints = listOf("smsOTPCode"))))
        assertFalse(FieldGuard.allowed(Field(autofillHints = listOf("creditCardNumber"))))
    }
    @Test fun ordinary_words_not_false_positive() {
        listOf("Spinning class notes", "Type a message", "Search", "Compass", "Shopping list").forEach {
            assertTrue(it, FieldGuard.allowed(Field(hint = it)))
        }
    }
}
