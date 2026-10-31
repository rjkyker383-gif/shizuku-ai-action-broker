package com.rjkyker.shizukuai.broker.thermal

import com.rjkyker.shizukuai.broker.thermal.ThermalPolicy.Config
import com.rjkyker.shizukuai.broker.thermal.ThermalPolicy.Kind
import com.rjkyker.shizukuai.broker.thermal.ThermalPolicy.Level
import com.rjkyker.shizukuai.broker.thermal.ThermalPolicy.Readings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {
    @Test fun tc22_47C_with_false_SEVERE_is_OK() {
        val v = ThermalPolicy.evaluate(Readings(cpuMaxC = 47.2, batteryC = 36.5, androidStatus = 3, source = "sysfs"))
        assertEquals(Level.OK, v.level)
        assertEquals(47.2, v.hottestC!!, 0.01)
    }

    @Test fun defaults() {
        val c = Config()
        assertEquals(70.0, c.cpuThrottleC, 0.0); assertEquals(80.0, c.cpuStopC, 0.0)
        assertEquals(42.0, c.batteryThrottleC, 0.0); assertEquals(46.0, c.batteryStopC, 0.0)
    }

    @Test fun cpu_thresholds() {
        assertEquals(Level.THROTTLE, ThermalPolicy.evaluate(Readings(cpuMaxC = 72.0)).level)
        assertEquals(Level.STOP, ThermalPolicy.evaluate(Readings(cpuMaxC = 80.0)).level)
        assertEquals(Level.STOP, ThermalPolicy.evaluate(Readings(cpuMaxC = 50.0, gpuMaxC = 85.0)).level)
    }

    @Test fun battery_and_skin_thresholds() {
        assertEquals(Level.THROTTLE, ThermalPolicy.evaluate(Readings(cpuMaxC = 50.0, batteryC = 43.0)).level)
        assertEquals(Level.STOP, ThermalPolicy.evaluate(Readings(cpuMaxC = 50.0, batteryC = 46.5)).level)
        assertEquals(Level.OK, ThermalPolicy.evaluate(Readings(skinMaxC = 43.5)).level)
        assertEquals(Level.THROTTLE, ThermalPolicy.evaluate(Readings(skinMaxC = 44.0)).level)
        assertEquals(Level.STOP, ThermalPolicy.evaluate(Readings(skinMaxC = 48.0)).level)
    }

    @Test fun hard_ceiling_cannot_be_raised() {
        val c = ThermalPolicy.clamp(Config(cpuThrottleC = 120.0, cpuStopC = 200.0, batteryThrottleC = 70.0, batteryStopC = 80.0))
        assertEquals(ThermalPolicy.HARD_CPU_STOP_C, c.cpuStopC, 0.0)
        assertEquals(ThermalPolicy.HARD_BATTERY_STOP_C, c.batteryStopC, 0.0)
        assertTrue(c.cpuThrottleC <= c.cpuStopC); assertTrue(c.batteryThrottleC <= c.batteryStopC)
        val v = ThermalPolicy.evaluate(Readings(cpuMaxC = 91.0), Config(cpuStopC = 500.0, cpuThrottleC = 400.0))
        assertEquals(Level.STOP, v.level)
        assertEquals(Level.STOP, ThermalPolicy.evaluate(Readings(batteryC = 50.5), Config(batteryStopC = 99.0, batteryThrottleC = 98.0)).level)
    }

    @Test fun android_status_only_when_no_sensor() {
        assertEquals(Level.STOP, ThermalPolicy.evaluate(Readings(androidStatus = 3)).level)
        assertEquals(Level.THROTTLE, ThermalPolicy.evaluate(Readings(androidStatus = 2)).level)
        assertEquals(Level.OK, ThermalPolicy.evaluate(Readings(androidStatus = 0)).level)
        assertEquals(Level.THROTTLE, ThermalPolicy.evaluate(Readings(androidStatus = -1)).level)
        assertEquals(Level.OK, ThermalPolicy.evaluate(Readings(batteryC = 30.0, androidStatus = 6)).level)
    }

    @Test fun classify_and_parse() {
        assertEquals(Kind.CPU, ThermalPolicy.classify("cpu-0-0-usr"))
        assertEquals(Kind.CPU, ThermalPolicy.classify("cpuss-1"))
        assertEquals(Kind.GPU, ThermalPolicy.classify("gpuss-0"))
        assertEquals(Kind.BATTERY, ThermalPolicy.classify("battery"))
        assertEquals(Kind.SKIN, ThermalPolicy.classify("xo-therm"))
        assertEquals(Kind.IGNORE, ThermalPolicy.classify("pm7250b-ibat-lvl0"))
        assertEquals(47.3, ThermalPolicy.parseTemp("47300")!!, 0.001)
        assertEquals(36.0, ThermalPolicy.parseTemp("36")!!, 0.001)
        assertNull(ThermalPolicy.parseTemp("0")); assertNull(ThermalPolicy.parseTemp("-273000")); assertNull(ThermalPolicy.parseTemp("x"))
    }

    @Test fun fromZones_takes_max_per_kind() {
        val r = ThermalPolicy.fromZones(listOf("cpu-0-0" to "45000", "cpu-1-2" to "48500", "gpuss-0" to "44000",
            "battery" to "36000", "aoss0" to "99000"), null, 3, "sysfs")
        assertEquals(48.5, r.cpuMaxC!!, 0.001); assertEquals(36.0, r.batteryC!!, 0.001); assertEquals("cpu-1-2", r.hottestZone)
        assertEquals(Level.OK, ThermalPolicy.evaluate(r).level)
    }
}
