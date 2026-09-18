package dev.jellystructure.tv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 236 (FR-236-6) — "on this network" grouping: IPv4 exact, IPv6 by /64, the CGNAT case the spec
 *  calls an accepted (documented) limitation rather than a bug. */
class ScreenNetworkTest {
    @Test
    fun ipv4MatchesOnlyExactly() {
        assertTrue(isNearby("203.0.113.7", "203.0.113.7"))
        assertFalse(isNearby("203.0.113.7", "203.0.113.8"))
    }

    @Test
    fun ipv6MatchesBySlash64() {
        // Same /64 (first four groups), different host part ⇒ nearby.
        assertTrue(isNearby("2001:db8:1:2:aaaa::1", "2001:db8:1:2:bbbb::2"))
        // Different /64 ⇒ not nearby.
        assertFalse(isNearby("2001:db8:1:2::1", "2001:db8:1:3::1"))
        // A /56 household still matches per subnet (the spec's own stated-fine case).
        assertTrue(isNearby("2001:db8:1:20::1", "2001:db8:1:20:ffff::2"))
    }

    @Test
    fun mixedFamiliesNeverMatch() {
        assertFalse(isNearby("203.0.113.7", "2001:db8::1"))
    }

    @Test
    fun cgnatSharedIpv4LooksNearby_documentedLimitation() {
        // Two unrelated households behind the same carrier-grade NAT pool present the same public IPv4 —
        // the spec calls this harmless (the list this feeds is already filtered to the caller's own
        // devices) rather than something to detect and refuse.
        assertTrue(isNearby("100.64.0.5", "100.64.0.5"))
    }

    @Test
    fun missingOrBlankAddressIsNeverNearby() {
        assertFalse(isNearby(null, "203.0.113.7"))
        assertFalse(isNearby("203.0.113.7", null))
        assertFalse(isNearby("", ""))
        assertFalse(isNearby(null, null))
    }

    @Test
    fun tooManyGroupsNeverMatches() {
        assertFalse(isNearby("2001:db8:1:2:3:4:5:6:7", "2001:db8:1:2:3:4:5:6")) // 9 groups, not 8 — refused, not truncated
    }
}
