package dev.jellystructure.ravilo.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class CastDiscoveryTest {
    @Test
    fun `Bonjour's snapshot becomes devices and the refusal state`() {
        val text = "state=0\n" +
            "8a1b\tStue\tGoogle Home Mini\t199172\tSpotify\t10.0.0.6\t8009\n" +
            "9c2d\tLiving room TV\tChromecast\t201221\t\t10.0.0.5\t8009\n" +
            "broken line\n" +
            "8a1b\tStue\tGoogle Home Mini\t199172\t\t10.0.0.6\t8009\n"
        val (state, devices) = CastDiscovery.parseSnapshot(text)
        assertEquals(0, state)
        assertEquals(listOf("8a1b", "9c2d"), devices.map { it.id }, "one row per device, a broken line skipped")
        assertEquals("speaker", devices[0].kind); assertEquals("Spotify", devices[0].runningApp)
        assertEquals("display", devices[1].kind); assertEquals(null, devices[1].runningApp)
        assertEquals(1, CastDiscovery.parseSnapshot("state=1").first, "Local Network access refused")
        assertEquals(0 to emptyList(), CastDiscovery.parseSnapshot(""))
    }
}
