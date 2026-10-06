package dev.jellystructure.tv

import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R377 — a picture's tier by Jellyfin's upper bounds; the tile's badge from it. */
class QualityTest {
    private fun video(w: Int?, h: Int?, range: String? = "SDR") = Track(
        streamIndex = 0, specifier = "v:0", kind = TrackKind.VIDEO, codec = "hevc", language = null, title = null,
        default = true, forced = false, width = w, height = h, videoRange = range,
    )

    private fun film(w: Int?, h: Int?, range: String? = "SDR") = MediaItem(
        id = "f", title = "f", year = 2026, kind = MediaKind.MOVIE, path = "/m/f.mkv", tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, tracks = listOf(video(w, h, range)),
        issueCount = 0, scannedAt = 0L,
    )

    @Test
    fun `tiers follow Jellyfin's buckets`() {
        val cases = listOf(
            3840 to 2160 to "4K", 3832 to 1600 to "4K", 3236 to 2152 to "4K", 7680 to 4320 to "4K",
            2560 to 1440 to "1080p", 1920 to 1080 to "1080p", 1918 to 802 to "1080p", 1440 to 1080 to "1080p",
            1292 to 1076 to "1080p", 1280 to 720 to "720p", 1278 to 716 to "720p",
            1024 to 576 to "SD", 720 to 576 to "SD",
        )
        for ((size, tier) in cases) assertEquals(tier, resolutionTier(size.first, size.second), "${size.first}x${size.second}")
    }

    @Test
    fun `one known dimension is enough and none gives no tier`() {
        assertEquals("4K", resolutionTier(3832, null))
        assertEquals("1080p", resolutionTier(null, 1080))
        assertEquals("SD", resolutionTier(null, 480))
        assertNull(resolutionTier(null, null))
        assertNull(film(null, null).qualityLabel())
    }

    @Test
    fun `HDR rides every tier and the badge shows only 4K and HDR`() {
        assertEquals("4K HDR", film(3832, 1600, "HDR").qualityLabel())
        assertEquals("4K HDR", film(3832, 1600, "HDR").qualityBadge())
        assertEquals("4K", film(3836, 1604).qualityBadge())
        assertEquals("1080p HDR", film(1920, 800, "HDR").qualityLabel())
        assertEquals("HDR", film(1920, 800, "HDR").qualityBadge())
        assertEquals("720p HDR", film(1280, 720, "HDR").qualityLabel())
        assertEquals("SD HDR", film(720, 576, "HDR").qualityLabel())
        assertNull(film(1920, 1080).qualityBadge())
        assertNull(film(1918, 802).qualityBadge())
    }

    @Test
    fun `a series takes its largest episode`() {
        fun ep(n: Int, w: Int, h: Int) = Episode(filename = "e$n.mkv", path = "/s/e$n.mkv", seasonNumber = 1, episodeNumber = n,
            tracks = listOf(video(w, h)), issueCount = 0)
        val series = film(null, null).copy(kind = MediaKind.TV_SHOW, tracks = emptyList(),
            episodes = listOf(ep(1, 1918, 802), ep(2, 3832, 1600), ep(3, 1280, 720)))
        assertEquals("4K", series.qualityLabel())
    }
}
