package dev.jellystructure.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Security fix (2026-08-02 review, finding M5) — UrlSafety is the SSRF guard in front of the artwork
 * "pick from URL" download path. An early version of the IPv6 host-extraction logic split off the port
 * BEFORE stripping the surrounding brackets, which silently defeated the "::1" loopback check for any
 * bracketed IPv6 literal (e.g. "http://[::1]:8080/x") — caught only by manual tracing, not execution,
 * which is exactly the kind of bug a guard like this must not ship with unverified. Pinning both the
 * fixed behavior and the surrounding "obviously safe" cases so a future edit can't reintroduce it.
 */
class UrlSafetyTest {

    @Test
    fun allowsOrdinaryExternalHosts() {
        assertTrue(UrlSafety.isSafeExternalUrl("https://image.tmdb.org/t/p/original/abc.jpg"))
        assertTrue(UrlSafety.isSafeExternalUrl("http://example.com/poster.jpg"))
        assertTrue(UrlSafety.isSafeExternalUrl("https://example.com:8443/poster.jpg"))
    }

    @Test
    fun rejectsNonHttpSchemes() {
        assertFalse(UrlSafety.isSafeExternalUrl("file:///etc/passwd"))
        assertFalse(UrlSafety.isSafeExternalUrl("ftp://example.com/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("gopher://example.com/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("not-a-url"))
    }

    @Test
    fun rejectsLocalhostByName() {
        assertFalse(UrlSafety.isSafeExternalUrl("http://localhost/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://localhost:9505/api/config"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://foo.localhost/x"))
    }

    @Test
    fun rejectsIpv4LoopbackAndPrivateRanges() {
        assertFalse(UrlSafety.isSafeExternalUrl("http://127.0.0.1/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://127.0.0.1:8096/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://10.0.0.5/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://172.16.0.1/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://172.31.255.255/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://192.168.1.1/x"))
    }

    @Test
    fun rejectsCloudMetadataAddress() {
        // The classic SSRF target — 169.254.169.254 (AWS/GCP/Azure instance metadata).
        assertFalse(UrlSafety.isSafeExternalUrl("http://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun allowsPublicIpv4LooksLikePrivateButIsnt() {
        // 172.32.x.x and 172.15.x.x are OUTSIDE the 172.16.0.0/12 private range — regression guard for
        // an off-by-one in the (a == 172 && b in 16..31) check.
        assertTrue(UrlSafety.isSafeExternalUrl("http://172.32.0.1/x"))
        assertTrue(UrlSafety.isSafeExternalUrl("http://172.15.255.255/x"))
    }

    @Test
    fun rejectsIpv6LoopbackAndLinkLocal_bareAndBracketed() {
        assertFalse(UrlSafety.isSafeExternalUrl("http://[::1]/x"))
        // The bracket-then-port-strip ordering bug: a bracketed loopback WITH an explicit port used to
        // slip through because the port was split off before the brackets were stripped.
        assertFalse(UrlSafety.isSafeExternalUrl("http://[::1]:8080/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://[fe80::1]/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://[fe80::1]:8080/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://[fc00::1]:8080/x"))
        assertFalse(UrlSafety.isSafeExternalUrl("http://[fd12::1]:8080/x"))
    }

    @Test
    fun handlesUserinfoInAuthority() {
        // A URL with embedded credentials must still resolve to the HOST, not the userinfo segment.
        assertFalse(UrlSafety.isSafeExternalUrl("http://admin:pw@127.0.0.1:8096/x"))
        assertTrue(UrlSafety.isSafeExternalUrl("http://user@example.com/x"))
    }
}
