package dev.jellystructure.ravilo.castv2

/**
 * R378 (FR-R378-1) — a TXT record's values as text. Android's `NsdManager` hands them over as bytes, and a key without a
 * value as null; such a key reads as empty, which [CastDevice.fromTxt] treats as not said.
 */
fun castTxtStrings(attributes: Map<String, ByteArray?>): Map<String, String> =
    attributes.mapValues { (_, v) -> v?.decodeToString() ?: "" }

/**
 * R378 (FR-R378-1) — the address to connect to, of those a `_googlecast._tcp` record resolved to: IPv4 first (the Mac's
 * lesson, `cc6a4bec` — a device resolved over IPv6 link-local lost its scope and could not be reached); then an IPv6
 * address that says how to get there (a global one, or a link-local one carrying its `%scope`); never a bare
 * link-local one. Null when none will do. A leading `/` (Java's `InetAddress.toString()`) is ignored.
 */
fun preferredCastHost(addresses: List<String>): String? {
    val clean = addresses.map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() }
    clean.firstOrNull(::isIpv4Literal)?.let { return it }
    return clean.firstOrNull { ':' in it && (!isLinkLocalIpv6(it) || '%' in it) }
}

/**
 * R378 (FR-R378-1) — the TV's own built-in Cast receiver is never listed (a TV does not relay to itself): matched by its
 * friendly name (the TV advertises its `Settings.Global.DEVICE_NAME` as `fn`) or by an address the TV holds.
 */
fun isOwnCastDevice(device: CastDevice, ownName: String?, ownAddresses: Set<String>): Boolean {
    val name = ownName?.trim()?.ifEmpty { null }
    if (name != null && device.name.trim().equals(name, ignoreCase = true)) return true
    val host = device.host.removePrefix("/").substringBefore('%').lowercase()
    return ownAddresses.any { it.removePrefix("/").substringBefore('%').lowercase() == host }
}

internal fun isIpv4Literal(a: String): Boolean {
    val parts = a.split('.')
    return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
}

/** `fe80::/10`. */
internal fun isLinkLocalIpv6(a: String): Boolean {
    val head = a.lowercase().substringBefore(':')
    val v = head.toIntOrNull(16) ?: return false
    return head.length == 4 && v and 0xffc0 == 0xfe80
}
