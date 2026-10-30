package com.rjkyker.shizukuai.broker.priv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log

/** Receives the privileged server's binder (Shizuku-style handoff). Only uid 0/2000 accepted. */
class PrivBinderProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        val out = Bundle()
        if (!PrivProtocol.isProviderCallerAllowed(uid)) {
            Log.w(TAG, "rejected sendBinder from uid=$uid")
            return out.apply { putBoolean("ok", false); putString("error", "uid not allowed") }
        }
        if (method != PrivProtocol.METHOD_SEND_BINDER) return out.apply { putBoolean("ok", false) }
        val b = extras?.getBinder(PrivProtocol.EXTRA_BINDER)
            ?: return out.apply { putBoolean("ok", false); putString("error", "no binder") }
        PrivServiceClient.onBinderReceived(b, extras.getInt("uid", -1), extras.getInt("pid", -1))
        return out.apply { putBoolean("ok", true) }
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object { private const val TAG = "PrivBinderProvider" }
}
