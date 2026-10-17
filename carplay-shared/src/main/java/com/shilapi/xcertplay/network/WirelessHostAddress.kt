package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Use the same scoped, link-local path for manual APs as for Wi-Fi Direct. */
internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? {
    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            return Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }

    }
    return addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
}

/** A bound control socket must use its source family and the local interface's IPv6 scope. */
internal fun carPlayControlAddress(addresses: List<InetAddress>, local: InetAddress?): InetAddress? {
    val usable = addresses.filterNot {
        it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress
    }
    if (local is Inet4Address) return usable.firstOrNull { it is Inet4Address }
    val linkLocal = usable.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }
    if (local is Inet6Address) {
        if (local.scopeId <= 0 || linkLocal == null) return null
        return Inet6Address.getByAddress(null, linkLocal.address, local.scopeId)
    }
    return linkLocal ?: usable.firstOrNull { it is Inet4Address } ?: usable.firstOrNull()
}
