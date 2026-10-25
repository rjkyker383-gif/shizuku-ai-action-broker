package com.rjkyker.shizukuai.broker.datawedge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataWedgeRepairTest {
    private val pkg = "com.rjkyker.shizukuai.broker"

    @Test fun planShape() {
        val steps = DataWedgeRepair.plan(pkg)
        assertEquals(4, steps.length())
        assertEquals(DataWedgeRepair.EXTRA_ENABLE, steps.getJSONObject(0).getString("extra"))
        assertEquals(DataWedgeApi.EXTRA_CREATE_PROFILE, steps.getJSONObject(1).getString("extra"))
        val cfg = steps.getJSONObject(2).getJSONObject("value")
        assertEquals("ShizukuAiBroker", cfg.getString("PROFILE_NAME"))
        assertEquals("UPDATE", cfg.getString("CONFIG_MODE"))
        val app = cfg.getJSONArray("APP_LIST").getJSONObject(0)
        assertEquals(pkg, app.getString("PACKAGE_NAME"))
        assertEquals("*", app.getJSONArray("ACTIVITY_LIST").getString(0))
        val plugins = (0 until cfg.getJSONArray("PLUGIN_CONFIG").length()).associate {
            val p = cfg.getJSONArray("PLUGIN_CONFIG").getJSONObject(it); p.getString("PLUGIN_NAME") to p
        }
        val intent = plugins.getValue("INTENT").getJSONObject("PARAM_LIST")
        assertEquals("true", intent.getString("intent_output_enabled"))
        assertEquals(DataWedgeApi.SCAN_ACTION, intent.getString("intent_action"))
        assertEquals("android.intent.category.DEFAULT", intent.getString("intent_category"))
        assertEquals("2", intent.getString("intent_delivery"))
        assertEquals("false", plugins.getValue("BDF").getJSONObject("PARAM_LIST").getString("bdf_enabled"))
        assertEquals("false", plugins.getValue("KEYSTROKE").getJSONObject("PARAM_LIST").getString("keystroke_output_enabled"))
        assertEquals("2", plugins.getValue("BARCODE").getJSONObject("PARAM_LIST").getString("code_id_type"))
    }

    @Test fun defaultProfileOnlyAddsIntentAndKeepsKeystroke() {
        val p0 = DataWedgeRepair.plan(pkg).getJSONObject(3).getJSONObject("value")
        assertEquals("Profile0 (default)", p0.getString("PROFILE_NAME"))
        assertEquals("UPDATE", p0.getString("CONFIG_MODE"))
        assertFalse(p0.has("APP_LIST"))
        val plugins = p0.getJSONArray("PLUGIN_CONFIG")
        assertEquals(1, plugins.length())
        assertEquals("INTENT", plugins.getJSONObject(0).getString("PLUGIN_NAME"))
        assertEquals("false", plugins.getJSONObject(0).getString("RESET_CONFIG"))
        assertEquals(3, DataWedgeRepair.plan(pkg, includeDefaultProfile = false).length())
    }

    @Test fun onlyFreeDecodersNoLicenceKeys() {
        val s = DataWedgeRepair.plan(pkg).toString().lowercase()
        for (bad in listOf("license", "licence", "activation", "dotcode", "ocr", "digimarc")) assertFalse(bad, s.contains(bad))
        assertTrue(DataWedgeRepair.FREE_DECODERS.all { it.startsWith("decoder_") })
        JSONObject(DataWedgeRepair.plan(pkg).getJSONObject(2).getJSONObject("value").toString()) // valid JSON
    }
}
