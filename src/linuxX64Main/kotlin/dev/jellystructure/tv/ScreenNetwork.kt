package dev.jellystructure.tv

/**
 * Phase 236 (FR-236-6) — "on this network" judged from public addresses alone: IPv4 matches exactly,
 * IPv6 by /64 (the common home delegation — a /56 household still matches per subnet, which the spec
 * calls fine). No LAN probing, ever (an https page can't reach a TV's local HTTP anyway), and no
 * location permission. Two stated, accepted limits: carrier-grade NAT can group two unrelated houses
 * under the same public IPv4 (harmless — the list this feeds is already filtered to the caller's own
 * devices, never to a stranger's), and a phone on a VPN or mobile data groups with nothing (the TV is
 * then simply "not nearby", never wrongly grouped either way).
 */
fun isNearby(callerAddress: String?, deviceAddress: String?): Boolean {
    val a = callerAddress?.trim()?.ifBlank { null } ?: return false
    val b = deviceAddress?.trim()?.ifBlank { null } ?: return false
    if (a == b) return true // exact match — the rule for public IPv4, and a fast path for identical IPv6
    // R378 (found live 2026-10-09): when the router forwards to the server without rewriting the source (the
    // household's DNAT since 2026-09-30), the server sees each LAN device's own private address — a phone at
    // 192.168.10.23, a TV at 192.168.11.40 (stand-ins) — and an exact match never holds inside the house, so no relay was ever
    // chosen. A private address only reaches the server from inside the household network (a remote viewer arrives
    // with a public address), so two of them are nearby; every other pair keeps the public-address rule below.
    if (isPrivateIpv4(a) && isPrivateIpv4(b)) return true
    val aIsV6 = ':' in a
    val bIsV6 = ':' in b
    if (aIsV6 != bIsV6 || !aIsV6) return false // mixed families, or two non-identical IPv4s: never nearby
    val aGroups = expandIpv6(a) ?: return false
    val bGroups = expandIpv6(b) ?: return false
    return aGroups.take(4) == bGroups.take(4) // the first 64 bits = 4 of the 8 16-bit groups
}

/** RFC 1918 — 10/8, 172.16/12, 192.168/16. */
internal fun isPrivateIpv4(raw: String): Boolean {
    val o = raw.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false } ?: return false
    if (o.any { it !in 0..255 }) return false
    return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
}

/** Expands a (possibly "::"-compressed, possibly zone-id-suffixed) IPv6 literal to 8 hex groups, or
 *  null if it doesn't parse as one. */
private fun expandIpv6(raw: String): List<String>? {
    val addr = raw.substringBefore('%') // strip a zone id, e.g. fe80::1%eth0
    if ("::" in addr) {
        val sides = addr.split("::", limit = 2)
        if (sides.size != 2 || "::" in sides[1]) return null // at most one "::" in a valid literal
        val head = if (sides[0].isEmpty()) emptyList() else sides[0].split(":")
        val tail = if (sides[1].isEmpty()) emptyList() else sides[1].split(":")
        val missing = 8 - head.size - tail.size
        if (missing < 0) return null
        return head + List(missing) { "0" } + tail
    }
    val groups = addr.split(":")
    return groups.takeIf { it.size == 8 }
}
