package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 184 (FR-184-3) — an operator's chosen fetch language must outlive every automatic scan/sync/
 * re-pull. `Scanner` never writes `metadataLanguage` itself (it's operator-only, consulted above the
 * resolver, never set by it), so a fresh scan always hands `addOrUpdate`/`updateOne` an item carrying
 * the field at its `null` default — this guard is the only thing standing between that and silently
 * forgetting the choice on the next scan.
 */
class MetadataLanguageLockTest {

    private fun item(metadataLanguage: String? = null, metadataLanguageSetAt: Long? = null) = MediaItem(
        id = "tenfold",
        title = "열배",
        year = 2003,
        kind = MediaKind.MOVIE,
        path = "/mnt/media/movies/Tenfold (2003)/Tenfold.mkv",
        tmdbId = 670,
        originalLanguage = "ko",
        posterPath = "/poster.jpg",
        overview = "Overview",
        tracks = emptyList(),
        issueCount = 0,
        scannedAt = 0,
        metadataLanguage = metadataLanguage,
        metadataLanguageSetAt = metadataLanguageSetAt,
    )

    @Test
    fun `a chosen language survives a scan-fresh item carrying the null default`() {
        val existing = item(metadataLanguage = "en", metadataLanguageSetAt = 1000L)
        val fresh = item()  // scan-fresh: null default, as Scanner always produces

        val merged = preserveMetadataLanguage(fresh, existing)

        assertEquals("en", merged.metadataLanguage)
        assertEquals(1000L, merged.metadataLanguageSetAt)
    }

    @Test
    fun `an unchosen item passes through untouched`() {
        val existing = item(metadataLanguage = null)
        val fresh = item()

        assertEquals(fresh, preserveMetadataLanguage(fresh, existing))
    }

    @Test
    fun `no existing item leaves the fresh item untouched`() {
        val fresh = item()

        assertEquals(fresh, preserveMetadataLanguage(fresh, null))
    }

    @Test
    fun `the guard never clears a value the fresh item already carries`() {
        // The set/reset route writes metadataLanguage directly and calls updateOne with the guard off;
        // this proves the guard itself is never what would have blocked a legitimate change.
        val existing = item(metadataLanguage = "en", metadataLanguageSetAt = 1000L)
        val fresh = item(metadataLanguage = "da", metadataLanguageSetAt = 2000L)

        val merged = preserveMetadataLanguage(fresh, existing)

        // The guard always trusts `existing` when set — this documents that the route setting a NEW
        // choice must pass respectMetadataLanguageLock = false, or the guard would revert it right back.
        assertEquals("en", merged.metadataLanguage)
    }

    @Test
    fun `clearing a TMDB match also clears the chosen language`() {
        val matched = item(metadataLanguage = "en", metadataLanguageSetAt = 1000L)

        val cleared = clearTmdbMatch(matched)

        assertNull(cleared.metadataLanguage)
        assertNull(cleared.metadataLanguageSetAt)
    }
}
