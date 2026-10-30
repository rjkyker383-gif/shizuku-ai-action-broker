package com.rjkyker.shizukuai.broker.delegation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.rjkyker.shizukuai.broker.R
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyCatalog
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyEngine
import com.rjkyker.shizukuai.broker.priv.IBrokerDelegation
import com.rjkyker.shizukuai.broker.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Dhizuku-style server: approved client apps bind here and get a narrow, API-level slice of the
 * device-owner rights the broker borrows from Dhizuku. No raw binder transact proxy (that would hand
 * out the whole IDevicePolicyManager). HIGH / LOCKOUT / FORBIDDEN ops are never delegated.
 */
class BrokerDelegationService : Service() {

    private val binder = object : IBrokerDelegation.Stub() {
        override fun getVersion(): Int = 1

        override fun status(): String {
            val (pkg, sig) = caller() ?: return err("unknown caller")
            return JSONObject().put("ok", true).put("client", pkg)
                .put("state", registry(this@BrokerDelegationService).state(pkg, sig).name)
                .put("allowed_ops", JSONArray(OwnerPolicyCatalog.CLIENT_OPS.sorted())).toString()
        }

        override fun requestConsent(): String {
            val (pkg, sig) = caller() ?: return err("unknown caller")
            val st = registry(this@BrokerDelegationService).request(pkg, sig, System.currentTimeMillis())
            if (st == DelegationRegistry.State.PENDING) notifyPending(this@BrokerDelegationService, pkg)
            return JSONObject().put("ok", true).put("client", pkg).put("state", st.name).toString()
        }

        override fun call(op: String?, argsJson: String?): String {
            val (pkg, sig) = caller() ?: return err("unknown caller")
            val o = (op ?: "").trim().lowercase()
            val auth = registry(this@BrokerDelegationService).authorize(pkg, sig, o)
            if (!auth.allowed) return err(auth.reason)
            val args = runCatching { JSONObject(argsJson ?: "{}") }.getOrElse { return err("bad args json") }
            val id = Binder.clearCallingIdentity()
            return try {
                OwnerPolicyEngine.run(this@BrokerDelegationService, o, args, OwnerPolicyCatalog.Caller.DELEGATED_CLIENT, false)
                    .put("client", pkg).toString()
            } finally { Binder.restoreCallingIdentity(id) }
        }

        private fun caller(): Pair<String, String>? {
            val uid = Binder.getCallingUid()
            if (uid == Process.myUid()) return null
            val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return null
            return pkg to signatureSha256(this@BrokerDelegationService, pkg)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val ACTION = "com.rjkyker.shizukuai.broker.DELEGATION"
        private const val CHANNEL = "delegation"
        @Volatile private var reg: DelegationRegistry? = null

        fun registry(ctx: Context): DelegationRegistry = reg ?: synchronized(this) {
            reg ?: DelegationRegistry(PrefsStore(ctx.applicationContext)).also { reg = it }
        }

        fun signatureSha256(ctx: Context, pkg: String): String = runCatching {
            val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners ?: return ""
            val md = MessageDigest.getInstance("SHA-256").digest(signers.first().toByteArray())
            md.joinToString("") { "%02x".format(it) }
        }.getOrDefault("")

        private fun notifyPending(ctx: Context, pkg: String) = runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Delegation requests", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("open_delegation", true), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(pkg.hashCode(), NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Device-owner access request")
                .setContentText("$pkg wants delegated device-owner access. Review in the broker.")
                .setContentIntent(pi).setAutoCancel(true).build())
        }

        private fun err(m: String) = JSONObject().put("ok", false).put("error", m).toString()
    }

    private class PrefsStore(ctx: Context) : DelegationRegistry.Store {
        private val p = ctx.getSharedPreferences("delegation_clients", Context.MODE_PRIVATE)
        override fun get(key: String): String? = p.getString(key, null)
        override fun put(key: String, value: String?) { p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
        override fun keys(): Set<String> = p.all.keys.toSet()
    }
}
