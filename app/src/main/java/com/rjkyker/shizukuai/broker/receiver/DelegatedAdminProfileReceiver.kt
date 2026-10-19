package com.rjkyker.shizukuai.broker.receiver

import android.app.admin.DelegatedAdminReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class DelegatedAdminProfileReceiver : DelegatedAdminReceiver() {

    override fun onNetworkLogsAvailable(context: Context, intent: Intent, batchToken: Long, networkLogsCount: Int) {
        Log.i(TAG, "Network logs batch available. Token: $batchToken, Count: $networkLogsCount")
    }

    override fun onSecurityLogsAvailable(context: Context, intent: Intent) {
        Log.i(TAG, "Security logs available for extraction.")
    }

    override fun onChoosePrivateKeyAlias(
        context: Context,
        intent: Intent,
        uid: Int,
        uri: Uri?,
        alias: String?
    ): String? {
        Log.i(TAG, "Private key requested by UID: $uid, URI: $uri, Proposed Alias: $alias")
        return alias
    }

    companion object {
        private const val TAG = "DelegatedAdminReceiver"
    }
}
