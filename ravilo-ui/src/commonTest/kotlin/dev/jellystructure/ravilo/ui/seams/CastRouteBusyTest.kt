package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R353 (FR-R353-1) — an idle Cast device's status line is its model name, never an app it is "busy" with. */
class CastRouteBusyTest {
    @Test
    fun `an idle device's model name is not an app`() {
        assertNull(castRouteBusyWith("Nest Wifi point", modelName = "Nest Wifi point"))
        assertNull(castRouteBusyWith("Google Nest Hub", modelName = "Google Nest Hub"))
        assertNull(castRouteBusyWith(" nest wifi point ", modelName = "Nest Wifi point"))
    }

    @Test
    fun `the device's own name is not an app either`() {
        assertNull(castRouteBusyWith("Kitchen", modelName = "Nest Wifi point", friendlyName = "Kitchen"))
    }

    @Test
    fun `nothing said is nothing known`() {
        assertNull(castRouteBusyWith(null, modelName = "Nest Wifi point"))
        assertNull(castRouteBusyWith("  ", modelName = "Nest Wifi point"))
    }

    @Test
    fun `a running app is named`() {
        assertEquals("Spotify", castRouteBusyWith("Spotify", modelName = "Nest Wifi point"))
        assertEquals("Ravilo", castRouteBusyWith("Ravilo", modelName = "Nest Wifi point"))
    }

    @Test
    fun `with no model name known the line is kept`() {
        assertEquals("Spotify", castRouteBusyWith("Spotify", modelName = null))
    }
}
