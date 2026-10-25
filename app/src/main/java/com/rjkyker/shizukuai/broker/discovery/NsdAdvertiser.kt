package com.rjkyker.shizukuai.broker.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.rjkyker.shizukuai.broker.server.BrokerServer
import java.util.concurrent.ConcurrentHashMap

/**
 * mDNS / DNS-SD (Bonjour/zeroconf) advertisement via Android NsdManager.
 * Registers `_mcp._tcp` and `_broker._tcp` with the broker port and a TXT record pointing at
 * /discover and /.well-known/mcp.json (plus the public mesh MCP URL). No secrets in TXT.
 *
 * Honest limit: the HTTP server binds 127.0.0.1, so a LAN peer that resolves this service can read
 * the TXT record (which tells it how to connect — the `mesh` URL, or adb forward) but cannot open
 * the port directly. Registered from BrokerService.onCreate, unregistered in onDestroy.
 */
object NsdAdvertiser {

    private const val TAG = "NsdAdvertiser"
    private val listeners = ConcurrentHashMap<String, NsdManager.RegistrationListener>()
    private val registered = ConcurrentHashMap<String, String>()
    @Volatile private var lastError: String = ""

    fun serviceName(): String {
        val model = (Build.MODEL ?: "android").replace(Regex("[^A-Za-z0-9-]"), "-").take(24)
        return "ShizukuBroker-$model"
    }

    fun isRegistered(): Boolean = registered.isNotEmpty()
    fun registeredTypes(): List<String> = registered.keys.sorted()
    fun lastError(): String = lastError

    @Synchronized
    fun register(context: Context) {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            lastError = "NsdManager unavailable"; return
        }
        val txt = runCatching {
            DiscoveryManifest.mdnsTxt(DiscoveryProvider.inputs(context.applicationContext))
        }.getOrDefault(mapOf("path" to "/discover", "auth" to "bearer"))
        for (type in DiscoveryManifest.MDNS_SERVICE_TYPES) {
            if (listeners.containsKey(type)) continue
            val info = NsdServiceInfo().apply {
                serviceName = serviceName()
                serviceType = type
                port = BrokerServer.PORT
                for ((k, v) in txt) runCatching { setAttribute(k, v) }
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(s: NsdServiceInfo) {
                    registered[type] = s.serviceName
                    Log.i(TAG, "mDNS registered $type as ${s.serviceName}")
                }
                override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                    lastError = "register $type failed: $errorCode"
                    listeners.remove(type)
                    Log.w(TAG, lastError)
                }
                override fun onServiceUnregistered(s: NsdServiceInfo) {
                    registered.remove(type)
                }
                override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "unregister $type failed: $errorCode")
                }
            }
            try {
                listeners[type] = listener
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                listeners.remove(type)
                lastError = "register $type: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, lastError)
            }
        }
    }

    @Synchronized
    fun unregister(context: Context) {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        for ((type, l) in listeners.entries.toList()) {
            runCatching { nsd.unregisterService(l) }
            listeners.remove(type)
            registered.remove(type)
        }
    }
}
