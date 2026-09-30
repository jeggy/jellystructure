package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals

/** R338 — which theme a device shows, and the skin an older app draws instead. */
class ThemesTest {
    @Test
    fun `a device with no light or dark always shows the dark pick`() {
        assertEquals("noir", resolveTheme(follow = true, light = "daylight", dark = "noir", single = "daylight", deviceDark = null))
        assertEquals("noir", resolveTheme(follow = false, light = "daylight", dark = "noir", single = "daylight", deviceDark = null))
    }

    @Test
    fun `following the system picks by the device's appearance`() {
        assertEquals("graphite", resolveTheme(true, "daylight", "graphite", "aurora", deviceDark = true))
        assertEquals("daylight", resolveTheme(true, "daylight", "graphite", "aurora", deviceDark = false))
    }

    @Test
    fun `not following shows the one pick whatever the device says`() {
        assertEquals("daylight", resolveTheme(false, "daylight", "graphite", "daylight", deviceDark = true))
        assertEquals("midnight", resolveTheme(false, "daylight", "graphite", "midnight", deviceDark = false))
    }

    @Test
    fun `a wrong or unknown id falls back to something drawable`() {
        assertEquals("aurora", resolveTheme(true, "daylight", "daylight", "aurora", deviceDark = true))     // a light id as the dark pick
        assertEquals("daylight", resolveTheme(true, "noir", "noir", "aurora", deviceDark = false))         // a dark id as the light pick
        assertEquals("aurora", resolveTheme(false, null, null, "ocean", deviceDark = false))              // a theme from a newer server
    }

    @Test
    fun `older apps get the nearest of the three skins`() {
        assertEquals(Skin.NOIR, RaviloThemes.legacySkin("noir"))
        assertEquals(Skin.MIDNIGHT, RaviloThemes.legacySkin("graphite"))
        assertEquals(Skin.NOIR, RaviloThemes.legacySkin("daylight", darkFallback = "noir"))
        assertEquals(Skin.AURORA, RaviloThemes.legacySkin("daylight", darkFallback = "daylight"))
        assertEquals(Skin.AURORA, RaviloThemes.legacySkin(null))
    }
}
