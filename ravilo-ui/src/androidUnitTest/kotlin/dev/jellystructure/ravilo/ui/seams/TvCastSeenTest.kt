package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.castv2.CastDevice
import dev.jellystructure.shared.tv.CastSeenDevice
import kotlin.test.Test
import kotlin.test.assertEquals

/** R378 (FR-R378-2) — the reach report an Android TV sends from what its own discovery found. */
class TvCastSeenTest {
    private val stueTv = CastDevice("tv-own", "Stue TV", "BRAVIA 4K", "192.168.11.28", 8009, 4101, null)
    private val stue = CastDevice("b1c2", "Stue", "Google Nest Mini", "192.168.11.50", 8009, 199172, null)
    private val hub = CastDevice("a9f0", "Køkken", "Google Nest Hub", "192.168.11.51", 8009, 201221, "Spotify")
    private val group = CastDevice("g-1", "Hele huset", "Google Cast Group", "192.168.11.50", 32187, 199204, null)
    private val other = CastDevice("c3d4", "Gæsteværelse", "Chromecast", "192.168.11.52", 8009, 201221, null)

    @Test
    fun `speakers and displays are reported with their Cast ids, in id order`() {
        val seen = tvCastSeenOf(listOf(stue, hub), null, emptySet()) { true }
        assertEquals(listOf(CastSeenDevice("a9f0", "Køkken", "display"), CastSeenDevice("b1c2", "Stue", "speaker")), seen)
    }

    @Test
    fun `the TV's own receiver is never reported, by name or by address`() {
        assertEquals(listOf("b1c2"), tvCastSeenOf(listOf(stueTv, stue), "Stue TV", emptySet()) { true }.map { it.castDeviceId })
        assertEquals(listOf("b1c2"), tvCastSeenOf(listOf(stueTv.copy(name = "BRAVIA"), stue), "Stue TV", setOf("192.168.11.28", "fe80::1%wlan0")) { true }.map { it.castDeviceId })
    }

    @Test
    fun `a group is never reported`() {
        assertEquals(listOf("b1c2"), tvCastSeenOf(listOf(group, stue), null, emptySet()) { true }.map { it.castDeviceId })
    }

    @Test
    fun `only a device that said our receiver runs there is reported (R330 D2)`() {
        val answers = mapOf("b1c2" to true, "a9f0" to false)   // c3d4 not asked yet
        assertEquals(listOf("b1c2"), tvCastSeenOf(listOf(stue, hub, other), null, emptySet()) { answers[it.id] }.map { it.castDeviceId })
    }

    @Test
    fun `a device seen twice is reported once`() {
        assertEquals(1, tvCastSeenOf(listOf(stue, stue.copy(host = "192.168.11.53")), null, emptySet()) { true }.size)
    }
}
