package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase 149 — multi-episode filename parsing (`parseSeasonEpisodes`). */
class ScannerFilenameParsingTest {

    @Test
    fun `single episode SxxEyy still parses as one episode`() {
        val (season, episodes) = parseSeasonEpisodes("/mnt/series/Show/Show.S01E05.mkv")
        assertEquals(1, season)
        assertEquals(listOf(5), episodes)
    }

    @Test
    fun `repeated-token multi-episode SxxEyyEzz parses every contained episode`() {
        val (season, episodes) = parseSeasonEpisodes(
            "/mnt/series/Johnny Bravo/Johnny.Bravo.S01E01E02E03.NORDIC.PDTV.x264-ROCKETRACCOON.mkv",
        )
        assertEquals(1, season)
        assertEquals(listOf(1, 2, 3), episodes)
    }

    @Test
    fun `dash range SxxEyy-Ezz expands the middle episode numbers`() {
        val (season, episodes) = parseSeasonEpisodes("/mnt/series/Show/Show.S02E04-E06.mkv")
        assertEquals(2, season)
        assertEquals(listOf(4, 5, 6), episodes)
    }

    @Test
    fun `chained Kodi-style 1x01x02x03 parses every contained episode`() {
        val (season, episodes) = parseSeasonEpisodes("/mnt/series/Show/Show.1x01x02x03.mkv")
        assertEquals(1, season)
        assertEquals(listOf(1, 2, 3), episodes)
    }

    @Test
    fun `single Kodi-style 2x01 still parses as one episode`() {
        val (season, episodes) = parseSeasonEpisodes("/mnt/series/Show/Show.2x01.mkv")
        assertEquals(2, season)
        assertEquals(listOf(1), episodes)
    }

    @Test
    fun `resolution token is not mistaken for chained Kodi-style numbering`() {
        val (season, episodes) = parseSeasonEpisodes("/mnt/movies/Show/Show.1920x1080.mkv")
        assertEquals(null, season)
        assertEquals(emptyList(), episodes)
    }

    @Test
    fun `unparseable filename returns no season and no episodes`() {
        val (season, episodes) = parseSeasonEpisodes("/mnt/series/Show/random-file-name.mkv")
        assertEquals(null, season)
        assertEquals(emptyList(), episodes)
    }

    // Phase 160 — resolveSeasonEpisode: Jellyfin-numbering fallback when our own filename regexes miss.

    @Test
    fun `resolveSeasonEpisode falls back to Jellyfin numbering when the filename parse found nothing`() {
        val (season, episodes) = resolveSeasonEpisode(Pair(null, emptyList()), jfSeason = 1, jfEpisode = 3)
        assertEquals(1, season)
        assertEquals(listOf(3), episodes)
    }

    @Test
    fun `resolveSeasonEpisode never overrides a successful filename parse`() {
        val (season, episodes) = resolveSeasonEpisode(Pair(2, listOf(4)), jfSeason = 9, jfEpisode = 9)
        assertEquals(2, season)
        assertEquals(listOf(4), episodes)
    }

    @Test
    fun `resolveSeasonEpisode stays unresolved when Jellyfin has no numbering either`() {
        val (season, episodes) = resolveSeasonEpisode(Pair(null, emptyList()), jfSeason = null, jfEpisode = null)
        assertEquals(null, season)
        assertEquals(emptyList(), episodes)
    }

    @Test
    fun `resolveSeasonEpisode requires both season and episode from Jellyfin not just one`() {
        val (season, episodes) = resolveSeasonEpisode(Pair(null, emptyList()), jfSeason = 1, jfEpisode = null)
        assertEquals(null, season)
        assertEquals(emptyList(), episodes)
    }
}
