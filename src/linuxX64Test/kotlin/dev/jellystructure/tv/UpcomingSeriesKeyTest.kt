package dev.jellystructure.tv

import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.UpcomingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2183 (DEB-2078, FR-1) — an episode names its series with an opaque key that is equal across the series
 * and never reaches a film. Fake rows only.
 */
class UpcomingSeriesKeyTest {
    @Test
    fun `every episode of one series gets the same key and another series another`() {
        assertEquals(seriesKeyOf(7), seriesKeyOf(7))
        assertNotEquals(seriesKeyOf(7), seriesKeyOf(8))
    }

    @Test
    fun `the key is on the wire for an episode and absent on a film`() {
        val ep = UpcomingItem(id = "ep-7-1-1", kind = MediaKind.SERIES, title = "t", date = "2026-11-01", seriesKey = seriesKeyOf(7))
        val film = UpcomingItem(id = "mv-1", kind = MediaKind.MOVIE, title = "t", date = "2026-11-01")
        val epJson = RaviloWireJson.encodeToString(UpcomingItem.serializer(), ep)
        assertTrue("\"series_key\":\"s7\"" in epJson, epJson)
        assertFalse("series_key" in RaviloWireJson.encodeToString(UpcomingItem.serializer(), film))
        assertEquals("s7", RaviloWireJson.decodeFromString(UpcomingItem.serializer(), epJson).seriesKey)
    }

    @Test
    fun `an item from a server that predates the key decodes with none`() {
        val old = RaviloWireJson.decodeFromString(UpcomingItem.serializer(), """{"id":"ep-7-1-1","title":"t","date":"2026-11-01"}""")
        assertNull(old.seriesKey)
    }
}
