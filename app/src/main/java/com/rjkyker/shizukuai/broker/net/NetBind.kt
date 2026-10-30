package com.rjkyker.shizukuai.broker.net

import com.rjkyker.shizukuai.broker.discovery.DiscoveryManifest

/**
 * Pure (Android-free) helpers that decide which local addresses the broker listens on.
 *
 * Policy: ALWAYS 127.0.0.1, PLUS every IPv4 on a Tailscale interface (100.64.0.0/10 CGNAT range)
 * when present. Never 0.0.0.0 and never a Wi-Fi/LAN address — so a new / public Wi-Fi can't reach
 * the server, while the owner's tailnet can reach it directly after a reboot or a Wi-Fi change
 * without needing wireless ADB or an `adb forward`.
 */
object NetBind {

    const val LOOPBACK = "127.0.0.1"

    /** Interface-name hints Tailscale uses on Android (VpnService tun). Address range is authoritative. */
    private val TS_IFACE_HINTS = listOf("tailscale", "tun")

    data class Iface(val name: String, val up: Boolean, val ipv4: List<String>)

    /** Tailscale IPv4s from an interface snapshot: up + in 100.64/10. Sorted, distinct. */
    fun tailscaleIps(ifaces: List<Iface>): List<String> =
        ifaces.asSequence()
            .filter { it.up }
            .flatMap { it.ipv4.asSequence() }
            .filter { DiscoveryManifest.isTailscaleV4(it) }
            .distinct()
            .sorted()
            .toList()

    /** Final bind set: loopback first, then Tailscale IPs. Never wildcard, never LAN. */
    fun bindAddresses(tailscale: List<String>): List<String> =
        (listOf(LOOPBACK) + tailscale.filter { DiscoveryManifest.isTailscaleV4(it) }).distinct()

    /** True when the server must rebind (Tailscale IP appeared / vanished / changed). */
    fun needsRebind(current: Collection<String>, desired: Collection<String>): Boolean =
        current.toSet() != desired.toSet()

    /** Whether an address is ever allowed as a listen address. */
    fun isAllowedBind(addr: String): Boolean =
        addr == LOOPBACK || DiscoveryManifest.isTailscaleV4(addr)

    fun looksLikeTailscaleIface(name: String): Boolean =
        TS_IFACE_HINTS.any { name.lowercase().startsWith(it) }

    fun baseUrl(addr: String, port: Int): String = "http://$addr:$port"
}
