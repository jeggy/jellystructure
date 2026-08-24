package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Person
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 174 — an operator's "this has no correct TMDB match" decision must outlive every automatic
 * path. `Scanner.rescanMetadata` refuses to search at all for a locked item, but the from-scratch scan
 * paths have no store access: they re-run the search and hand `addOrUpdate`/`updateOne` a fresh item
 * carrying the flag's `false` default. This pins that merge — the same place the Phase-133/151 artwork
 * regression lived.
 */
class TmdbMatchLockTest {

    private fun matched(
        title: String = "Drifting",
        year: Int? = 1923,
        tmdbId: Int? = 12345,
        locked: Boolean = false,
    ) = MediaItem(
        id = "drifting",
        title = title,
        originalTitle = "Drifting",
        year = year,
        kind = MediaKind.MUSIC_VIDEO,
        path = "/mnt/media/music/Tina Dico/Drifting.mkv",
        tmdbId = tmdbId,
        originalLanguage = "en",
        posterPath = "/wrong-poster.jpg",
        backdropPath = "/wrong-fanart.jpg",
        overview = "A 1923 silent film.",
        genres = listOf("Drama"),
        tmdbGenres = listOf("Drama"),
        studio = "Universal",
        studioTmdbId = 33,
        cast = listOf(Person(tmdbId = 1, name = "Priscilla Dean")),
        crew = listOf(Person(tmdbId = 2, name = "Tod Browning")),
        imdbId = "tt0013918",
        runtime = 80,
        certifications = mapOf("US" to "NR"),
        tracks = emptyList(),
        issueCount = 0,
        scannedAt = 0,
        tags = listOf("live"),
        tmdbMatchLocked = locked,
    )

    @Test
    fun `clearing a match empties every TMDB-owned field and sets the lock`() {
        val cleared = clearTmdbMatch(matched())

        assertNull(cleared.tmdbId)
        assertNull(cleared.posterPath)
        assertNull(cleared.backdropPath)
        assertNull(cleared.overview)
        assertNull(cleared.studio)
        assertNull(cleared.studioTmdbId)
        assertNull(cleared.originalTitle)
        assertNull(cleared.originalLanguage)
        assertNull(cleared.imdbId)
        assertNull(cleared.runtime)
        assertNull(cleared.trailer)
        assertNull(cleared.imdbRating)
        assertTrue(cleared.genres.isEmpty())
        assertTrue(cleared.tmdbGenres.isEmpty())
        assertTrue(cleared.cast.isEmpty())
        assertTrue(cleared.crew.isEmpty())
        assertTrue(cleared.certifications.isEmpty())
        assertTrue(cleared.tmdbMatchLocked)
    }

    /** `title`/`year`/`tags` are not TMDB-exclusive — a music video's year comes from Jellyfin, and the
     *  title may already carry a manual fix. Clearing them would destroy data with no way back. */
    @Test
    fun `clearing a match leaves title year and tags alone`() {
        val cleared = clearTmdbMatch(matched(title = "Tina Dico - Drifting", year = 2020))

        assertEquals("Tina Dico - Drifting", cleared.title)
        assertEquals(2020, cleared.year)
        assertEquals(listOf("live"), cleared.tags)
    }

    /** The reported bug: without the guard, the next full scan re-runs the same search, gets the same
     *  wrong hit, and writes it straight back with the flag reset to its default. */
    @Test
    fun `a re-matched scan result is stripped back for a locked item`() {
        val existing = clearTmdbMatch(matched(title = "Tina Dico - Drifting", year = 2020))
        val fresh = matched()  // scan-fresh: wrong match again, tmdbMatchLocked = false

        val merged = preserveTmdbMatchLock(fresh, existing)

        assertNull(merged.tmdbId)
        assertNull(merged.overview)
        assertTrue(merged.cast.isEmpty())
        assertTrue(merged.tmdbMatchLocked, "the flag must be carried forward or it survives one scan only")
    }

    /** The fresh item's title/year came out of the re-search being discarded, so the stored ones win. */
    @Test
    fun `a locked item keeps its own title and year over the re-matched ones`() {
        val existing = clearTmdbMatch(matched(title = "Tina Dico - Drifting", year = 2020))
        val fresh = matched(title = "Drifting", year = 1923)

        val merged = preserveTmdbMatchLock(fresh, existing)

        assertEquals("Tina Dico - Drifting", merged.title)
        assertEquals(2020, merged.year)
    }

    /** The guard runs on EVERY write, including ordinary store-derived ones. A metadata edit on a
     *  locked item carries no match, so it must keep the operator's new title/year — carrying the
     *  stored ones forward there would silently revert the edit. */
    @Test
    fun `an edit to a locked item keeps its new title and year and stays locked`() {
        val existing = clearTmdbMatch(matched(title = "Tina Dico - Drifting", year = 2020))
        val edited = existing.copy(title = "Tina Dickow - Drifting", year = 2021)

        val merged = preserveTmdbMatchLock(edited, existing)

        assertEquals("Tina Dickow - Drifting", merged.title)
        assertEquals(2021, merged.year)
        assertTrue(merged.tmdbMatchLocked)
    }

    /** A scan whose re-search found nothing still arrives with the flag at its default. */
    @Test
    fun `a locked item stays locked when the fresh scan found no match`() {
        val existing = clearTmdbMatch(matched())
        val fresh = matched(tmdbId = null).copy(tmdbMatchLocked = false, posterPath = null, overview = null)

        assertTrue(preserveTmdbMatchLock(fresh, existing).tmdbMatchLocked)
    }

    @Test
    fun `an unlocked item passes through untouched`() {
        val existing = matched(locked = false)
        val fresh = matched(tmdbId = 999)

        assertEquals(fresh, preserveTmdbMatchLock(fresh, existing))
    }

    /** A first insert has no predecessor — nothing to preserve, and nothing to strip. */
    @Test
    fun `no existing item leaves the fresh item untouched`() {
        val fresh = matched()

        assertEquals(fresh, preserveTmdbMatchLock(fresh, null))
        assertFalse(preserveTmdbMatchLock(fresh, null).tmdbMatchLocked)
    }
}
