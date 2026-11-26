package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** DEB-5699 (DEB-5685, FR-56) — `aboutRows` in `:shared`: the same rows, in the same order, as ravilo-ui's About block drew before it moved. */
class AboutRowsTest {
    /** Echoes the key and its variables, so the test reads the row's shape without a language file. */
    private val echo: AboutStr = { key, vars -> if (vars.isEmpty()) key else "$key(${vars.entries.joinToString(",") { "${it.key}=${it.value}" }})" }

    private val film = AboutFacts(runtimeMin = 98, released = "2001-05-04", director = "A. Director", studioOrNetwork = "A Studio",
        country = "DK", originalLanguage = "da", added = 1_700_000_000, sizeBytes = 23_400_000_000)

    @Test fun `a film's rows come in the spec's order`() {
        assertEquals(listOf(
            "about.runtime" to "about.minutes(min=98)",
            "about.released" to "about.date(day=4,month=about.month.5,year=2001)",
            "about.director" to "A. Director",
            "about.studio" to "A Studio",
            "about.country" to "Denmark",
            "about.original_language" to "Dansk",
            "about.in_library_since" to "about.date(day=14,month=about.month.11,year=2023)",
            "about.size" to "23.4 GB",
        ), aboutRows(film, series = false, echo))
    }

    @Test fun `a series says per episode and first aired and network and its seasons and a creator stands in for a missing director`() {
        val rows = aboutRows(AboutFacts(runtimeMin = 45, released = "2019-09", creator = "A. Creator", studioOrNetwork = "A Network", seasons = 3, episodes = 30), series = true, echo)
        assertEquals(listOf(
            "about.runtime" to "about.per_episode(min=45)",
            "about.first_aired" to "about.month.9 2019",
            "about.creator" to "A. Creator",
            "about.network" to "A Network",
            "about.seasons" to "about.seasons_value(seasons=3,episodes=30)",
        ), rows)
    }

    @Test fun `nothing sent means no rows and an unknown country shows its code and a blank value is no row`() {
        assertEquals(emptyList(), aboutRows(AboutFacts(), series = false, echo))
        val rows = aboutRows(AboutFacts(country = "ZZ", director = " ", sizeBytes = 0), series = false, echo)
        assertEquals(listOf("about.country" to "ZZ"), rows)
        assertFalse(rows.any { it.first == "about.size" })
    }

    @Test fun `epochDayIso is UTC and handles leap days`() {
        assertEquals("1970-01-01", epochDayIso(0))
        assertEquals("2024-02-29", epochDayIso(1_709_164_800))
    }
}
