package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase 171 (music-video artwork path fix) — a music video shares its folder with siblings, so its
 *  artwork needs the same per-basename convention as episode stills/NFOs, not a movie's fixed
 *  `poster.jpg` inside a dedicated folder. */
class AssetFilePathTest {

    private fun item(kind: MediaKind, path: String) = MediaItem(
        id = "x", title = "x", year = null, kind = kind, path = path,
        tmdbId = null, originalLanguage = null, posterPath = null, overview = null,
        tracks = emptyList(), issueCount = 0, scannedAt = 0,
    )

    @Test
    fun `movie poster sits at a fixed filename in the movie's own folder`() {
        val path = assetFilePath(item(MediaKind.MOVIE, "/mnt/movies/Foo (2020)/Foo.mkv"), "poster.jpg")
        assertEquals("/mnt/movies/Foo (2020)/poster.jpg", path)
    }

    @Test
    fun `tv show poster sits at a fixed filename in the series directory`() {
        val path = assetFilePath(item(MediaKind.TV_SHOW, "/mnt/series/Foo"), "poster.jpg")
        assertEquals("/mnt/series/Foo/poster.jpg", path)
    }

    @Test
    fun `music video poster uses the video's own basename since siblings share the folder`() {
        val path = assetFilePath(
            item(MediaKind.MUSIC_VIDEO, "/mnt/music/Eivør/Eivør - Live in Bucharest (2026-08-04).mkv"),
            "poster.jpg",
        )
        assertEquals("/mnt/music/Eivør/Eivør - Live in Bucharest (2026-08-04)-poster.jpg", path)
    }
}
