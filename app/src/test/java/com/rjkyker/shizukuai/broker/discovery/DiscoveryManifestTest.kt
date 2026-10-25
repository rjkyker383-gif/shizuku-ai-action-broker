package com.rjkyker.shizukuai.broker.discovery

import com.rjkyker.shizukuai.broker.action.ActionAllowList
import com.rjkyker.shizukuai.broker.action.RiskLevel
import com.rjkyker.shizukuai.broker.mcp.McpToolExecutor
import com.rjkyker.shizukuai.broker.util.SecurePrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryManifestTest {

    private val fakeMcpToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fakeAirtable = "patFAKEairtableKEY.0123456789abcdef"
    private val fakeBridgeToken = "bridge-secret-xyz-123456"

    private fun tools(): List<DiscoveryManifest.Tool> {
        val defs = McpToolExecutor.toolDefinitions()
        return (0 until defs.length()).map {
            val o = defs.getJSONObject(it)
            val n = o.getString("name")
            DiscoveryManifest.Tool(n, ActionAllowList.riskLevelFor(n)!!.name, o.getString("description"))
        }
    }

    private fun inputs(
        upstream: String = SecurePrefs.DEFAULT_UPSTREAM_MCP_URL,
        bridges: Map<String, String> = SecurePrefs.BRIDGE_NAMES.associateWith { SecurePrefs.defaultBridgeUrl(it) }
    ) = DiscoveryManifest.Inputs(
        appId = "com.rjkyker.shizukuai.broker", appName = "Shizuku AI Broker",
        versionName = "1.8.0-dev", versionCode = 10,
        manufacturer = "Zebra Technologies", model = "TC22", androidRelease = "14", sdkInt = 34,
        localHost = "127.0.0.1", port = 17999, serverRunning = true,
        serverName = "shizuku-ai-action-broker", serverVersion = "1.8.0-dev",
        protocolVersions = listOf("2024-11-05", "2025-03-26", "2025-06-18"),
        defaultProtocolVersion = "2024-11-05",
        tailscaleAddresses = listOf("100.95.242.123"),
        meshBaseUrl = SecurePrefs.DEFAULT_MESH_BASE_URL,
        bridgeUrls = bridges, upstreamUrl = upstream, tools = tools(),
        mdnsServiceName = "ShizukuBroker-TC22", mdnsRegistered = true,
        knownSecrets = listOf(fakeMcpToken, fakeAirtable, fakeBridgeToken)
    )

    @Test
    fun manifestHasExpectedShape() {
        val m = DiscoveryManifest.build(inputs())
        assertEquals(DiscoveryManifest.SCHEMA, m.getString("schema"))
        assertEquals("com.rjkyker.shizukuai.broker", m.getJSONObject("app").getString("id"))
        assertEquals("1.8.0-dev", m.getJSONObject("app").getString("version"))
        assertEquals("TC22", m.getJSONObject("device").getString("model"))
        assertTrue(m.getJSONObject("auth").getBoolean("required"))
        assertEquals("bearer", m.getJSONObject("auth").getString("scheme"))
        assertFalse(m.getBoolean("credentials_included"))
        val transports = m.getJSONObject("mcp").getJSONArray("transports")
        assertEquals("/mcp", transports.getJSONObject(0).getString("path"))
        assertEquals("/ws", transports.getJSONObject(1).getString("path"))
        val mdns = m.getJSONObject("mdns")
        assertEquals(listOf("_mcp._tcp", "_broker._tcp"), (0 until mdns.getJSONArray("service_types").length()).map { mdns.getJSONArray("service_types").getString(it) })
        assertEquals("/discover", mdns.getJSONObject("txt").getString("path"))
    }

    @Test
    fun connectionsIncludeLoopbackTailscaleAndAllPrefilledMeshBridges() {
        val m = DiscoveryManifest.build(inputs())
        val conns = m.getJSONArray("connections")
        val byId = (0 until conns.length()).associate { conns.getJSONObject(it).getString("id") to conns.getJSONObject(it) }
        assertEquals("http://127.0.0.1:17999/mcp", byId.getValue("loopback").getString("mcp_http"))
        assertTrue(byId.containsKey("tailscale_100.95.242.123"))
        val host = "https://presocial-endocrinopathic-fumiko.ngrok-free.dev"
        for (b in listOf("android", "spark", "ether", "war", "magic")) {
            val c = byId.getValue("mesh_$b")
            assertEquals("$host/$b/mcp", c.getString("mcp_http"))
            assertEquals("$host/$b/health", c.getString("health"))
        }
        assertEquals("$host/android/mcp", byId.getValue("upstream").getString("mcp_http"))
    }

    @Test
    fun toolsListedWithRiskAndIncludeDiscoveryInfo() {
        val m = DiscoveryManifest.build(inputs())
        val t = m.getJSONArray("tools")
        assertEquals(McpToolExecutor.TOOL_NAMES.size, t.length())
        assertEquals(McpToolExecutor.TOOL_NAMES.size, m.getInt("tool_count"))
        val names = (0 until t.length()).map { t.getJSONObject(it).getString("name") }.toSet()
        assertEquals(McpToolExecutor.TOOL_NAMES, names)
        for (i in 0 until t.length()) {
            val o = t.getJSONObject(i)
            assertTrue(o.getString("risk") in setOf("LOW", "MEDIUM", "HIGH"))
            assertTrue(o.getString("description").length <= 160)
            assertFalse("tool entry must not carry schemas", o.has("inputSchema"))
        }
        assertEquals(RiskLevel.LOW, ActionAllowList.riskLevelFor("discovery_info"))
    }

    @Test
    fun manifestNeverContainsSecretKeysOrValues() {
        // Even if a secret sneaks into a URL (user-info or ?token=) it must not survive.
        val leaky = inputs(
            upstream = "https://user:$fakeBridgeToken@example.com/mcp?token=$fakeMcpToken",
            bridges = mapOf("android" to "https://h.example/android/mcp?token=$fakeMcpToken#$fakeAirtable")
        )
        for (m in listOf(DiscoveryManifest.build(inputs()), DiscoveryManifest.build(leaky))) {
            val text = m.toString()
            for (secret in listOf(fakeMcpToken, fakeAirtable, fakeBridgeToken)) {
                assertFalse("secret leaked: $secret", text.contains(secret))
            }
            for (k in DiscoveryManifest.allKeys(m)) {
                assertFalse("forbidden key: $k", DiscoveryManifest.isForbiddenKey(k))
            }
            // No long hex blobs (bearer tokens are 64 hex chars).
            for (v in DiscoveryManifest.allStrings(m)) {
                assertFalse("hex blob in value: $v", Regex("[0-9a-fA-F]{32,}").containsMatchIn(v))
            }
        }
        val leakyUp = DiscoveryManifest.build(leaky).getJSONArray("connections")
        val up = (0 until leakyUp.length()).map { leakyUp.getJSONObject(it) }.first { it.getString("id") == "upstream" }
        assertEquals("https://example.com/mcp", up.getString("mcp_http"))
    }

    @Test
    fun redactionScrubsKnownSecretsAndForbiddenKeys() {
        val o = JSONObject().put("a", "x$fakeMcpToken").put("mcp_token", "zzz").put("nested", JSONObject().put("apiKey", "q"))
        DiscoveryManifest.redactKnownSecrets(o, listOf(fakeMcpToken))
        assertEquals("[redacted]", o.getString("a"))
        assertFalse(o.has("mcp_token"))
        assertFalse(o.getJSONObject("nested").has("apiKey"))
    }

    @Test
    fun helpers() {
        assertTrue(DiscoveryManifest.isTailscaleV4("100.95.242.123"))
        assertTrue(DiscoveryManifest.isTailscaleV4("100.64.0.1"))
        assertFalse(DiscoveryManifest.isTailscaleV4("100.128.0.1"))
        assertFalse(DiscoveryManifest.isTailscaleV4("192.168.1.158"))
        assertEquals("", DiscoveryManifest.sanitizeUrl("ftp://x/y"))
        assertEquals("http://h:8486/health", DiscoveryManifest.healthUrlFor("http://h:8486/mcp"))
        assertEquals("https://h/health", DiscoveryManifest.healthUrlFor("https://h/rpc"))
    }

    @Test
    fun builtInDefaultsArePrefilledAndCarryNoCredential() {
        assertEquals("https://presocial-endocrinopathic-fumiko.ngrok-free.dev/android/mcp", SecurePrefs.DEFAULT_UPSTREAM_MCP_URL)
        assertEquals(listOf("android", "spark", "ether", "war", "magic"), SecurePrefs.BRIDGE_NAMES)
        for (b in SecurePrefs.BRIDGE_NAMES) {
            val u = SecurePrefs.defaultBridgeUrl(b)
            assertEquals("${SecurePrefs.DEFAULT_MESH_BASE_URL}/$b/mcp", u)
            assertFalse(u.contains("?") || u.contains("@"))
        }
        assertFalse(SecurePrefs.DEFAULT_UPSTREAM_MCP_URL.contains("192.168."))
    }

    @Test
    fun noThroughWallClaims() {
        val m = DiscoveryManifest.build(inputs())
        val limits = m.getJSONObject("limits").getString("wifi_presence").lowercase()
        assertTrue(limits.contains("cannot see through walls"))
    }
}
