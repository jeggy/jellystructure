package dev.jellystructure.tv

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.RaviloWireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-1482 (FR-2) — the poster badge's airing instant rides next to the bare episode code, under the same
 * condition, and the code itself is unchanged (an installed app wraps it in its own "Soon • …").
 */
class UpcomingEpisodeWireTest {
    private val utc = "2026-11-12T01:00:00Z"

    private fun series(
        status: String? = "continuing", date: String? = "2026-11-12", season: Int? = 3, episode: Int? = 1,
        airUtc: String? = utc, kind: MediaKind = MediaKind.TV_SHOW,
    ) = MediaItem(
        id = "t", title = "t", year = 2026, kind = kind, path = "/s/t", tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(),
        issueCount = 0, scannedAt = 0L, sonarrStatus = status, sonarrNextAiringDate = date,
        sonarrNextAiringSeason = season, sonarrNextAiringEpisode = episode, sonarrNextAiringUtc = airUtc,
    )

    @Test
    fun `the instant is present exactly when the episode code is and the code is unchanged`() {
        val s = series()
        assertEquals("S03E01", s.upcomingEpisodeCode(true))
        assertEquals(utc, s.upcomingAirUtc(true))
        val cases = listOf(
            series(status = "ended"), series(date = null), series(season = null), series(episode = null),
            series(kind = MediaKind.MOVIE),
        )
        for (c in cases) {
            assertNull(c.upcomingEpisodeCode(true)); assertNull(c.upcomingAirUtc(true))
        }
        assertNull(s.upcomingEpisodeCode(false)); assertNull(s.upcomingAirUtc(false))
    }

    @Test
    fun `a row Sonarr has not refreshed yet keeps its code and has no instant`() {
        val old = series(airUtc = null)
        assertEquals("S03E01", old.upcomingEpisodeCode(true))
        assertNull(old.upcomingAirUtc(true))
    }

    @Test
    fun `the card puts upcoming_air_utc beside upcoming_episode on the wire`() {
        val card = MediaCard(
            id = "t", kind = dev.jellystructure.shared.tv.MediaKind.SERIES, title = "t", year = null, genre = null, rating = null,
            posterUrl = null, backdropUrl = null,
            upcomingEpisode = series().upcomingEpisodeCode(true), upcomingAirUtc = series().upcomingAirUtc(true),
        )
        val json = RaviloWireJson.encodeToString(MediaCard.serializer(), card)
        assertTrue("\"upcoming_episode\":\"S03E01\"" in json, json)
        assertTrue("\"upcoming_air_utc\":\"$utc\"" in json, json)
        // An app that predates the field decodes the same payload.
        assertEquals("S03E01", RaviloWireJson.decodeFromString(MediaCard.serializer(), json).upcomingEpisode)
    }
}
