package com.rjkyker.shizukuai.broker.discovery

import android.content.Context
import android.os.Build
import com.rjkyker.shizukuai.broker.action.ActionAllowList
import com.rjkyker.shizukuai.broker.mcp.McpToolExecutor
import com.rjkyker.shizukuai.broker.server.BrokerServer
import com.rjkyker.shizukuai.broker.server.McpJsonRpcHandler
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Android glue: gathers real runtime state (SecurePrefs URLs, Build, network interfaces, NSD state)
 * and hands it to the pure [DiscoveryManifest] builder. Secrets are read only so the builder can
 * redact them if they ever leaked into a value; they are never added to the manifest.
 */
object DiscoveryProvider {

    fun manifest(context: Context): JSONObject = DiscoveryManifest.build(inputs(context.applicationContext))

    fun inputs(ctx: Context): DiscoveryManifest.Inputs {
        val pkg = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val versionCode = when {
            pkg == null -> 0L
            Build.VERSION.SDK_INT >= 28 -> pkg.longVersionCode
            else -> pkg.versionCode.toLong()
        }
        val bridges = linkedMapOf<String, String>()
        for (n in SecurePrefs.BRIDGE_NAMES) bridges[n] = SecurePrefs.getBridgeUrl(ctx, n)
        return DiscoveryManifest.Inputs(
            appId = ctx.packageName,
            appName = runCatching { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }.getOrDefault("Shizuku AI Broker"),
            versionName = pkg?.versionName ?: "?",
            versionCode = versionCode,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            localHost = BrokerServer.HOST,
            port = BrokerServer.PORT,
            serverRunning = BrokerServer.get()?.isRunning == true,
            serverName = McpJsonRpcHandler.SERVER_NAME,
            serverVersion = McpJsonRpcHandler.SERVER_VERSION,
            protocolVersions = McpJsonRpcHandler.SUPPORTED_PROTOCOL_VERSIONS.sorted(),
            defaultProtocolVersion = McpJsonRpcHandler.DEFAULT_PROTOCOL_VERSION,
            tailscaleAddresses = tailscaleAddresses(),
            meshBaseUrl = SecurePrefs.getMeshBaseUrl(ctx),
            bridgeUrls = bridges,
            upstreamUrl = SecurePrefs.getUpstreamMcpUrl(ctx),
            tools = toolSummaries(),
            mdnsServiceName = NsdAdvertiser.serviceName(),
            mdnsRegistered = NsdAdvertiser.isRegistered(),
            knownSecrets = knownSecrets(ctx),
            boundAddresses = BrokerServer.get()?.boundAddresses.orEmpty()
        )
    }

    /** Tool names + risk + description from the single source of truth (McpToolExecutor). */
    fun toolSummaries(): List<DiscoveryManifest.Tool> {
        val defs = McpToolExecutor.toolDefinitions()
        return (0 until defs.length()).mapNotNull { idx ->
            val o = defs.optJSONObject(idx) ?: return@mapNotNull null
            val name = o.optString("name")
            DiscoveryManifest.Tool(
                name = name,
                risk = (ActionAllowList.riskLevelFor(name)?.name ?: "HIGH"),
                description = o.optString("description")
            )
        }
    }

    /** IPv4 addresses on this device in Tailscale's 100.64.0.0/10 range (tailscale0 / tun). */
    fun tailscaleAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { DiscoveryManifest.isTailscaleV4(it) }
            .distinct()
    }.getOrDefault(emptyList())

    private fun knownSecrets(ctx: Context): List<String> = buildList {
        add(SecurePrefs.getMcpToken(ctx))
        add(SecurePrefs.getAirtableApiKey(ctx))
        add(SecurePrefs.getUpstreamMcpToken(ctx))
        add(SecurePrefs.getAgentKey(ctx))
        for (n in SecurePrefs.BRIDGE_NAMES) add(SecurePrefs.getBridgeToken(ctx, n))
    }.filter { it.isNotBlank() }
}
