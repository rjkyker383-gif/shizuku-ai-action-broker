package com.rjkyker.shizukuai.broker.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Android/JVM glue: snapshot real interfaces for [NetBind]. Soft-fails to empty. */
object NetInfo {
    fun interfaces(): List<NetBind.Iface> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
            NetBind.Iface(
                name = ni.name ?: "",
                up = runCatching { ni.isUp }.getOrDefault(false),
                ipv4 = ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            )
        }
    }.getOrDefault(emptyList())

    fun tailscaleIps(): List<String> = NetBind.tailscaleIps(interfaces())
}
