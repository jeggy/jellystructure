package dev.jellystructure.media

import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 201 (FR-201-6) — [MkvLayoutAudit.mkvPaths] is the input side of the sweep: which files get
 * walked at all. `scanMkvLayout` itself (the actual EBML walk) is covered by [MkvLayoutTest].
 */
class MkvLayoutAuditTest {

    private fun movie(path: String) = MediaItem(
        id = path.substringAfterLast('/').substringBeforeLast('.'),
        title = "T", originalTitle = "T", year = 2020, kind = MediaKind.MOVIE, path = path,
        tmdbId = null, originalLanguage = "en", posterPath = null, overview = null,
        tracks = emptyList(), issueCount = 0, scannedAt = 0,
    )

    private fun series(path: String, episodePaths: List<String>) = MediaItem(
        id = "series", title = "S", originalTitle = "S", year = 2020, kind = MediaKind.TV_SHOW, path = path,
        tmdbId = null, originalLanguage = "en", posterPath = null, overview = null,
        tracks = emptyList(), issueCount = 0, scannedAt = 0,
        episodes = episodePaths.map { p ->
            Episode(filename = p.substringAfterLast('/'), path = p, seasonNumber = 1, episodeNumber = 1, tracks = emptyList(), issueCount = 0)
        },
    )

    @Test
    fun `only mkv files are included and other containers are dropped`() {
        val items = listOf(
            movie("/mnt/movies/A/A.mkv"),
            movie("/mnt/movies/B/B.mp4"),
            movie("/mnt/movies/C/C.MKV"), // extension case shouldn't matter
        )

        assertEquals(listOf("/mnt/movies/A/A.mkv", "/mnt/movies/C/C.MKV"), MkvLayoutAudit.mkvPaths(items))
    }

    @Test
    fun `a series contributes every episode's own path and not the series folder`() {
        val items = listOf(series("/mnt/tv/Show", listOf("/mnt/tv/Show/S01E01.mkv", "/mnt/tv/Show/S01E02.mkv")))

        assertEquals(listOf("/mnt/tv/Show/S01E01.mkv", "/mnt/tv/Show/S01E02.mkv"), MkvLayoutAudit.mkvPaths(items))
    }

    @Test
    fun `duplicate paths across episode rows collapse to one`() {
        // A multi-episode file (Phase 149) backs more than one Episode row with the same path.
        val items = listOf(series("/mnt/tv/Show", listOf("/mnt/tv/Show/S00E01-E02.mkv", "/mnt/tv/Show/S00E01-E02.mkv")))

        assertEquals(listOf("/mnt/tv/Show/S00E01-E02.mkv"), MkvLayoutAudit.mkvPaths(items))
    }

    @Test
    fun `an item with no episodes and no mkv extension contributes nothing`() {
        assertEquals(emptyList(), MkvLayoutAudit.mkvPaths(listOf(movie("/mnt/movies/D/D.avi"))))
    }
}
