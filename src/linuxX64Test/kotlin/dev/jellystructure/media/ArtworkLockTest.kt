package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 151 — a manually picked/uploaded poster or backdrop must survive every automatic metadata pull.
 * The guard is applied at both `MediaStore` write choke points (`addOrUpdate` + `updateOne`); this pins
 * the merge itself, which is where the Phase-133 regression lived.
 */
class ArtworkLockTest {

    private fun item(
        posterPath: String?,
        backdropPath: String? = null,
        locked: List<String> = emptyList(),
    ) = MediaItem(
        id = "show-2020",
        title = "Show",
        year = 2020,
        kind = MediaKind.TV_SHOW,
        path = "/mnt/series/Show",
        tmdbId = 42,
        originalLanguage = "en",
        posterPath = posterPath,
        backdropPath = backdropPath,
        overview = null,
        tracks = emptyList(),
        issueCount = 0,
        scannedAt = 0,
        lockedArtwork = locked,
    )

    /** The reported bug: a re-pull hands us TMDB's default poster; the operator's pick must win. */
    @Test
    fun `locked poster survives a fresh TMDB pull`() {
        val existing = item(posterPath = "/manual-pick.jpg", locked = listOf(ArtworkAsset.POSTER))
        val fresh = item(posterPath = "/tmdb-default.jpg")

        val merged = preserveLockedArtwork(fresh, existing)

        assertEquals("/manual-pick.jpg", merged.posterPath)
        assertTrue(ArtworkAsset.POSTER in merged.lockedArtwork)
    }

    /** An uploaded poster is served from disk, so the sentinel path must survive too. */
    @Test
    fun `locked upload sentinel path survives a fresh TMDB pull`() {
        val existing = item(posterPath = "/tv/image/show-2020/poster", locked = listOf(ArtworkAsset.POSTER))
        val fresh = item(posterPath = "/tmdb-default.jpg")

        assertEquals("/tv/image/show-2020/poster", preserveLockedArtwork(fresh, existing).posterPath)
    }

    @Test
    fun `locked backdrop survives a fresh TMDB pull`() {
        val existing = item(posterPath = "/p.jpg", backdropPath = "/manual-fanart.jpg", locked = listOf(ArtworkAsset.BACKDROP))
        val fresh = item(posterPath = "/p.jpg", backdropPath = "/tmdb-fanart.jpg")

        assertEquals("/manual-fanart.jpg", preserveLockedArtwork(fresh, existing).backdropPath)
    }

    /** Locking the backdrop must not pin the poster — TMDB stays authoritative for unlocked assets. */
    @Test
    fun `an unlocked asset still follows TMDB`() {
        val existing = item(posterPath = "/old-poster.jpg", backdropPath = "/manual-fanart.jpg", locked = listOf(ArtworkAsset.BACKDROP))
        val fresh = item(posterPath = "/new-poster.jpg", backdropPath = "/tmdb-fanart.jpg")

        val merged = preserveLockedArtwork(fresh, existing)

        assertEquals("/new-poster.jpg", merged.posterPath)
        assertEquals("/manual-fanart.jpg", merged.backdropPath)
    }

    @Test
    fun `nothing locked leaves the fresh item untouched`() {
        val existing = item(posterPath = "/old-poster.jpg")
        val fresh = item(posterPath = "/new-poster.jpg")

        assertEquals(fresh, preserveLockedArtwork(fresh, existing))
    }

    /** A first insert has no predecessor to preserve from. */
    @Test
    fun `no existing item leaves the fresh item untouched`() {
        val fresh = item(posterPath = "/new-poster.jpg")

        assertEquals(fresh, preserveLockedArtwork(fresh, null))
    }

    /** The lock itself must be carried forward, or it would only ever survive one pull. */
    @Test
    fun `the lock list is carried over even when the scanner drops it`() {
        val existing = item(posterPath = "/manual.jpg", locked = listOf(ArtworkAsset.POSTER, ArtworkAsset.CLEARLOGO))
        val fresh = item(posterPath = "/tmdb.jpg")

        assertEquals(listOf(ArtworkAsset.POSTER, ArtworkAsset.CLEARLOGO), preserveLockedArtwork(fresh, existing).lockedArtwork)
    }
}
