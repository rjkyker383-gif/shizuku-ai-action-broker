package com.rjkyker.shizukuai.broker.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDenylistTest {

    @Test
    fun allowsBenignCommands() {
        assertTrue(ShellDenylist.check("id; pwd; echo SHELL_BRIDGE_OK").allowed)
        assertTrue(ShellDenylist.check("getprop ro.build.version.release").allowed)
        assertTrue(ShellDenylist.check("dumpsys battery").allowed)
        assertTrue(ShellDenylist.check("dumpsys meminfo com.rjkyker.shizukuai.broker").allowed)
    }

    @Test
    fun refusesUninstallAndClear() {
        assertFalse(ShellDenylist.check("pm uninstall com.foo").allowed)
        assertFalse(ShellDenylist.check("pm clear com.foo").allowed)
        assertFalse(ShellDenylist.check("pm disable-user com.foo").allowed)
    }

    @Test
    fun refusesWipeAndRmRfData() {
        assertFalse(ShellDenylist.check("wipe data").allowed)
        assertFalse(ShellDenylist.check("factory reset").allowed)
        assertFalse(ShellDenylist.check("rm -rf /data/data/com.foo").allowed)
        assertFalse(ShellDenylist.check("rm -rf /sdcard/*").allowed)
    }

    @Test
    fun refusesDialerAndSms() {
        assertFalse(ShellDenylist.check("am start -a android.intent.action.CALL -d tel:123").allowed)
        assertFalse(ShellDenylist.check("am start -a android.intent.action.SENDTO -d sms:123").allowed)
        assertFalse(ShellDenylist.check("service call isms 1").allowed)
    }

    @Test
    fun refusesDangerousDumpsys() {
        assertFalse(ShellDenylist.check("dumpsys").allowed)
        assertFalse(ShellDenylist.check("dumpsys activity").allowed)
        assertFalse(ShellDenylist.check("dumpsys jobscheduler").allowed)
        assertFalse(ShellDenylist.check("dumpsys notification").allowed)
        assertFalse(ShellDenylist.check("dumpsys procstats").allowed)
        assertFalse(ShellDenylist.check("dumpsys meminfo").allowed)
        assertFalse(ShellDenylist.check("bugreport").allowed)
    }
}

class ShellDenylistLockout188Test {
    @org.junit.Test fun lockoutShellFormsDenied() {
        listOf("locksettings set-pin 1234", "locksettings clear --old 1234", "dpm remove-active-admin x/.R",
            "dpm set-device-owner x/.R", "settings put global adb_enabled 0").forEach {
            org.junit.Assert.assertFalse(it, ShellDenylist.check(it).allowed)
        }
        listOf("locksettings get-disabled", "dpm list-owners", "settings get global adb_enabled", "id", "getprop ro.product.model").forEach {
            org.junit.Assert.assertTrue(it, ShellDenylist.check(it).allowed)
        }
    }
}
