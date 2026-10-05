package dev.jellystructure.ravilo.castv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R378 (FR-R378-1) — what an Android TV's own discovery makes of a `_googlecast._tcp` record. */
class CastDiscoveryRulesTest {
    @Test
    fun `TXT bytes read as text and a key without a value reads as empty`() {
        val txt = castTxtStrings(mapOf("id" to "8a1b2c".encodeToByteArray(), "fn" to "Gæsteværelse".encodeToByteArray(), "rs" to null))
        assertEquals(mapOf("id" to "8a1b2c", "fn" to "Gæsteværelse", "rs" to ""), txt)
        val d = CastDevice.fromTxt(txt + ("ca" to "199172") + ("md" to "Google Home Mini"), "10.0.0.6", 8009)!!
        assertEquals("speaker", d.kind)
        assertNull(d.runningApp, "an empty rs is not a running app")
    }

    @Test
    fun `IPv4 comes first whatever order the addresses arrive in`() {
        assertEquals("10.10.11.40", preferredCastHost(listOf("fe80::1c2:3%wlan0", "2a01:4f8::7", "10.10.11.40")))
        assertEquals("10.10.11.40", preferredCastHost(listOf("/10.10.11.40")), "Java's InetAddress.toString() form")
    }

    @Test
    fun `without IPv4 an IPv6 address that names its way out will do and a bare link-local one never`() {
        assertEquals("2a01:4f8::7", preferredCastHost(listOf("fe80::1c2:3", "2a01:4f8::7")))
        assertEquals("fe80::1c2:3%wlan0", preferredCastHost(listOf("fe80::1c2:3", "fe80::1c2:3%wlan0")))
        assertNull(preferredCastHost(listOf("fe80::1c2:3", "FEBF::9")), "link-local without its scope cannot be reached")
        assertNull(preferredCastHost(emptyList()))
    }

    @Test
    fun `not every dotted thing is IPv4`() {
        assertTrue(isIpv4Literal("192.168.0.255"))
        assertFalse(isIpv4Literal("256.1.1.1"))
        assertFalse(isIpv4Literal("1.2.3"))
        assertFalse(isIpv4Literal("stue.local"))
    }

    private val stueTv = CastDevice("tv-1", "Stue TV", "BRAVIA 4K", "10.10.11.128", 8009, 4101, null)
    private val speaker = CastDevice("sp-1", "Stue", "Google Nest Mini", "10.10.11.40", 8009, 199172, null)

    @Test
    fun `the TV's own receiver is never listed, by its name or by its address`() {
        assertTrue(isOwnCastDevice(stueTv, "Stue TV", emptySet()), "by the TV's DEVICE_NAME")
        assertTrue(isOwnCastDevice(stueTv, " stue tv ", emptySet()), "names compare trimmed and in any case")
        assertTrue(isOwnCastDevice(stueTv.copy(name = "Living room"), null, setOf("10.10.11.128")), "renamed: by the address")
        assertTrue(isOwnCastDevice(stueTv.copy(host = "fe80::5%eth0"), null, setOf("fe80::5%wlan0")), "an IPv6 address, whatever its scope")
    }

    @Test
    fun `another device is listed, even one whose name starts like the TV's`() {
        assertFalse(isOwnCastDevice(speaker, "Stue TV", setOf("10.10.11.128")))
        assertFalse(isOwnCastDevice(speaker, null, emptySet()))
        assertFalse(isOwnCastDevice(speaker, "  ", emptySet()), "a blank DEVICE_NAME matches nothing")
    }
}
