package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** R303 (FR-R303-2/3/4/6) — what the player says top right: one decision for the TV, the phone and the web app. */
class PlayerIdentTest {
    @Test
    fun `an episode with a logo shows the series logo`() {
        assertEquals(PlayerIdent.Logo("/api/tv/image/s1/logo?v=3", plate = false), playerIdent("/api/tv/image/s1/logo?v=3", "light", "Havets Hjarta"))
    }

    @Test
    fun `a dark-ink logo sits on the plate and an unjudged one does not`() {
        assertEquals(PlayerIdent.Logo("/l", plate = true), playerIdent("/l", "dark", null))
        assertEquals(PlayerIdent.Logo("/l", plate = false), playerIdent("/l", null, null))
    }

    @Test
    fun `a series with no logo shows its name and a film with none shows nothing`() {
        assertEquals(PlayerIdent.Name("Havets Hjarta"), playerIdent(null, null, "Havets Hjarta"))
        assertEquals(PlayerIdent.None, playerIdent(null, null, null))
        assertEquals(PlayerIdent.None, playerIdent("", null, ""))
    }

    @Test
    fun `a logo that failed to load falls back the same way as no logo`() {
        assertEquals(PlayerIdent.Name("Havets Hjarta"), playerIdent("/l", "dark", "Havets Hjarta", logoFailed = true))
        assertEquals(PlayerIdent.None, playerIdent("/l", null, null, logoFailed = true))
    }

    @Test
    fun `the slot gives way when something else names a TV in the top row`() {
        assertEquals(PlayerIdent.None, playerIdent("/l", null, "Havets Hjarta", hidden = true))
    }
}
