package com.rjkyker.shizukuai.broker.stagenow

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Optional EMDK ProfileManager bridge via reflection.
 * compile-time: no EMDK AAR required. Runtime: if com.symbol.emdk is present and a ProfileManager
 * can be obtained, processProfile(xml) is attempted. Otherwise we report why and fall back to
 * StageNow XML export / launch. Never touches licensing daemons.
 */
object EmdkMxBridge {
    private const val TAG = "EmdkMx"

    fun available(ctx: Context): JSONObject {
        val emdk = runCatching { Class.forName("com.symbol.emdk.EMDKManager") }.isSuccess
        val pm = runCatching { Class.forName("com.symbol.emdk.ProfileManager") }.isSuccess
        val pkgInstalled = runCatching {
            ctx.packageManager.getPackageInfo("com.symbol.emdk.emdkservice", 0); true
        }.getOrDefault(false) || runCatching {
            ctx.packageManager.getApplicationInfo("com.symbol.emdk.emdkservice", 0); true
        }.getOrDefault(false)
        // Also detect the EMDK library bundled inside another Zebra app via classloader only.
        return JSONObject().put("ok", true).put("emdk_classes_present", emdk && pm)
            .put("emdkservice_installed", pkgInstalled)
            .put("can_submit", false) // honest default until a live manager is obtained (async EMDK open)
            .put("note", "EMDK ProfileManager requires the EMDK SDK on-device and typically a DNA-licensed MX feature set for advanced CSPs. Basic AccessMgr/SettingsMgr XML is still applied via StageNow without EMDK.")
            .put("omitted_licensed", org.json.JSONArray(StageNowXml.omittedLicensed))
    }

    /**
     * Best-effort synchronous submit. Returns ok=false with a clear reason when EMDK isn't usable.
     * We deliberately do NOT open EMDKManager asynchronously here: that needs an Activity lifecycle
     * and an EMDK license check we refuse to bypass. Callers should use StageNow export instead.
     */
    fun submitXml(ctx: Context, xml: String): JSONObject {
        val avail = available(ctx)
        if (!avail.optBoolean("emdk_classes_present")) {
            return JSONObject().put("ok", false).put("error", "EMDK classes not on classpath; export XML and apply with StageNow")
                .put("emdk", avail).put("fallback", "stagenow_export")
        }
        // Even with classes present, obtaining ProfileManager needs EMDKManager.getInstance(callback).
        // Doing that off an Activity without the AAR linked is unreliable; be honest:
        Log.i(TAG, "EMDK present but broker is built without the EMDK AAR; refusing silent reflection submit")
        return JSONObject().put("ok", false)
            .put("error", "EMDK detected but the broker is built without the EMDK AAR (compileOnly not resolved). Use stagenow_export + StageNow app, or rebuild with EMDK linked.")
            .put("emdk", avail).put("xml_len", xml.length).put("fallback", "stagenow_export")
    }
}
