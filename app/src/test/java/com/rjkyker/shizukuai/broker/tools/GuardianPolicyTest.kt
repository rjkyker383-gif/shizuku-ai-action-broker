package com.rjkyker.shizukuai.broker.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardianPolicyTest {
    @Test fun acceptsOrdinaryUserSetting() {
        assertNull(GuardianPolicy.validate("system", "screen_brightness", "120"))
    }
    @Test fun blocksSecuritySensitiveSettings() {
        assertEquals("This key is security-sensitive and cannot be guarded",
            GuardianPolicy.validate("global", "adb_enabled", "1"))
        assertEquals("This key is security-sensitive and cannot be guarded",
            GuardianPolicy.validate("secure", "enabled_accessibility_services", "pkg/.Service"))
    }
    @Test fun limitsPropertiesToOwnerNamespace() {
        assertEquals("Only app-owned persist.rjkyker.* properties are allowed",
            GuardianPolicy.validate("property", "persist.sys.locale", "en-US"))
        assertNull(GuardianPolicy.validate("property", "persist.rjkyker.mode", "safe"))
    }
    @Test fun rejectsUnsafeKeyAndControlCharacters() {
        assertEquals("Key format is invalid", GuardianPolicy.validate("system", "x;rm", "1"))
        assertEquals("Value is too long or contains control characters", GuardianPolicy.validate("system", "screen_brightness", "bad\nvalue"))
    }
}