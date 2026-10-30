package com.rjkyker.shizukuai.broker.priv

import com.rjkyker.shizukuai.broker.shell.ShellDenylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivProtocolTest {
    private val apk = "/data/app/~~abc==/com.rjkyker.shizukuai.broker-xyz==/base.apk"

    @Test fun startCommandIsDetachedAppProcess() {
        val c = PrivProtocol.startCommand(apk, "com.rjkyker.shizukuai.broker", 10250, 18)
        assertTrue(c.contains("setsid app_process /system/bin"))
        assertTrue(c.contains("--nice-name=${PrivProtocol.NICE_NAME}"))
        assertTrue(c.contains(PrivProtocol.SERVER_CLASS))
        assertTrue(c.contains("--broker-uid=10250"))
        assertTrue(c.contains("CLASSPATH='$apk'"))
        assertTrue(c.contains("</dev/null"))
        assertTrue(c.trimEnd().endsWith("&"))
        assertFalse(c.lowercase().contains("token"))
    }

    @Test fun startCommandPassesShellDenylist() {
        val c = PrivProtocol.startCommand(apk, "com.rjkyker.shizukuai.broker", 10250, 18)
        assertTrue(ShellDenylist.check(c).allowed)
    }

    @Test fun startCommandQuotesHostilePath() {
        val c = PrivProtocol.startCommand("/data/app/x'; reboot;'.apk", "p.q", 10001, 1)
        assertTrue(c.contains("'/data/app/x'\\''; reboot;'\\''.apk'"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsRelativeApk() {
        PrivProtocol.startCommand("base.apk", "p.q", 10001, 1)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsBadUid() {
        PrivProtocol.startCommand(apk, "p.q", 0, 1)
    }

    @Test fun serverOnlyServesBrokerUid() {
        assertTrue(PrivProtocol.isServerCallerAllowed(10250, 10250))
        assertFalse(PrivProtocol.isServerCallerAllowed(10251, 10250))
        assertFalse(PrivProtocol.isServerCallerAllowed(2000, 10250))
        assertFalse(PrivProtocol.isServerCallerAllowed(0, -1))
    }

    @Test fun providerOnlyAcceptsShellOrRoot() {
        assertTrue(PrivProtocol.isProviderCallerAllowed(2000))
        assertTrue(PrivProtocol.isProviderCallerAllowed(0))
        assertFalse(PrivProtocol.isProviderCallerAllowed(10123))
        assertFalse(PrivProtocol.isProviderCallerAllowed(1000))
    }

    @Test fun parseArgsRoundTrip() {
        val a = PrivProtocol.parseArgs(arrayOf("--broker-uid=10250", "--pkg=com.x.y", "--vc=18", "--apk=$apk"))
        assertEquals(10250, a.brokerUid); assertEquals("com.x.y", a.pkg); assertEquals(18, a.versionCode); assertEquals(apk, a.apk)
        assertEquals(-1, PrivProtocol.parseArgs(arrayOf("--broker-uid=nope")).brokerUid)
    }

    @Test fun propKeyValidation() {
        assertTrue(PrivProtocol.isValidPropKey("ro.product.model"))
        assertFalse(PrivProtocol.isValidPropKey("ro.x; reboot"))
        assertFalse(PrivProtocol.isValidPropKey(""))
    }

    @Test fun scriptAndAdbCommand() {
        val s = PrivProtocol.startScript(apk, "com.rjkyker.shizukuai.broker", 10250, 18)
        assertTrue(s.startsWith("#!/system/bin/sh"))
        assertTrue(s.contains("after every reboot"))
        assertEquals("adb shell sh /sdcard/x.sh", PrivProtocol.adbCommand("/sdcard/x.sh"))
    }

    @Test fun outputCapped() {
        val big = "a".repeat(PrivProtocol.MAX_OUTPUT_CHARS + 10)
        assertTrue(PrivProtocol.cap(big).endsWith("[truncated]"))
        assertEquals("ok", PrivProtocol.cap("ok"))
    }
}
