package com.rjkyker.shizukuai.broker.stagenow

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Export StageNow XML to app-private files and launch / share into the StageNow client. */
object StageNowLauncher {
    private const val TAG = "StageNow"
    private val STAGENOW_PKGS = listOf(
        "com.symbol.stagenow", "com.symbol.mdna.stagenow", "com.zebra.mdna.stagenow", "com.symbol.mxconfig",
    )

    fun installedClients(ctx: Context): List<String> = STAGENOW_PKGS.filter {
        runCatching { ctx.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false)
    }

    fun export(ctx: Context, profile: StageNowXml.Profile): JSONObject {
        val dir = File(ctx.applicationContext.getExternalFilesDir(null), "stagenow").apply { mkdirs() }
        val f = File(dir, "${profile.name}.xml")
        f.writeText(profile.xml)
        return JSONObject().put("ok", true).put("name", profile.name).put("path", f.absolutePath)
            .put("bytes", f.length()).put("needs_license", profile.needsLicense).put("note", profile.note)
            .put("stagenow_clients", JSONArray(installedClients(ctx)))
            .put("emdk", EmdkMxBridge.available(ctx))
    }

    fun exportNamed(ctx: Context, name: String, ssid: String? = null, pkg: String? = null): JSONObject {
        val profile = when (name) {
            "accessmgr_full" -> StageNowXml.accessMgrFullSettings()
            "settingsmgr_enables" -> StageNowXml.settingsMgrEnables()
            "unlock_combined" -> StageNowXml.combinedUnlock()
            "appmgr_disable_osx" -> StageNowXml.appMgrDisable(pkg ?: "com.symbol.osx.osxprovisionservice")
            "wifi_add_open" -> StageNowXml.wifiMgrAddOpen(ssid ?: return JSONObject().put("ok", false).put("error", "ssid required"))
            else -> return JSONObject().put("ok", false).put("error", "unknown profile '$name'")
                .put("known", JSONArray(listOf("accessmgr_full", "settingsmgr_enables", "unlock_combined", "appmgr_disable_osx", "wifi_add_open")))
        }
        return export(ctx, profile)
    }

    /** Build an ACTION_SEND / VIEW intent for the last exported (or given) XML. Caller must startActivity. */
    fun shareIntent(ctx: Context, path: String): Intent? {
        val f = File(path)
        if (!f.isFile) return null
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider failed: ${e.message}; falling back to file:// (may be blocked)")
            Uri.fromFile(f)
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clients = installedClients(ctx)
            if (clients.isNotEmpty()) setPackage(clients.first())
        }
    }

    fun launchStageNow(ctx: Context): JSONObject {
        val clients = installedClients(ctx)
        if (clients.isEmpty()) return JSONObject().put("ok", false).put("error", "no StageNow client installed")
            .put("looked_for", JSONArray(STAGENOW_PKGS))
        val pkg = clients.first()
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return JSONObject().put("ok", false).put("error", "no launcher activity for $pkg")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(launch)
            JSONObject().put("ok", true).put("package", pkg)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).put("package", pkg)
        }
    }

    fun status(ctx: Context): JSONObject = JSONObject().put("ok", true)
        .put("clients", JSONArray(installedClients(ctx)))
        .put("profiles", JSONArray(StageNowXml.catalog().map {
            JSONObject().put("name", it.name).put("needs_license", it.needsLicense).put("note", it.note)
        }))
        .put("emdk", EmdkMxBridge.available(ctx))
        .put("omitted_licensed", JSONArray(StageNowXml.omittedLicensed))
        .put("honest", "Without a Mobility DNA / MX license the broker can export AccessMgr/SettingsMgr/AppMgr/basic Wi-Fi XML and open StageNow. EMDK ProfileManager submit and premium MX CSPs need the EMDK SDK + license and are not bypassed.")
}
