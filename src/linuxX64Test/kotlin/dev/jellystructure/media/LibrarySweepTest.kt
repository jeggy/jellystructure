package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase 181 (FR-181-1/FR-181-1a) — [computeLibraryDiff], extracted out of [sweepJellyfinLibrary] so the
 *  id-diff logic itself is testable without a live [MediaStore] or [dev.jellystructure.auth.JellyfinClient]. */
class LibrarySweepTest {

    private fun jf(id: String, type: String, seriesId: String? = null) =
        JellyfinItem(id = id, name = id, type = type, seriesId = seriesId)

    @Test
    fun `a movie jellyfin has and we don't is missing`() {
        val result = computeLibraryDiff(
            jfItems = listOf(jf("movie-1", "Movie")),
            ourTopLevelIds = emptySet(),
            ourEpisodeIds = emptySet(),
        )
        assertEquals(listOf("movie-1"), result.missingIds)
        assertTrue(result.staleTopLevelIds.isEmpty())
    }

    @Test
    fun `a movie we already hold is not missing`() {
        val result = computeLibraryDiff(
            jfItems = listOf(jf("movie-1", "Movie")),
            ourTopLevelIds = setOf("movie-1"),
            ourEpisodeIds = emptySet(),
        )
        assertTrue(result.missingIds.isEmpty())
    }

    @Test
    fun `a missing episode resolves to its parent series id not the episode id`() {
        // Reproduces the reported Klovn case: the series (Jellyfin id "klovn") is already held, but its
        // newest episode is not — the diff must surface the SERIES id so the existing episode-ingest path
        // (a re-scan of the parent series) can pick it up, since there is no standalone episode ingest.
        val result = computeLibraryDiff(
            jfItems = listOf(
                jf("klovn", "Series"),
                jf("klovn-s11e07", "Episode", seriesId = "klovn"),
            ),
            ourTopLevelIds = setOf("klovn"),
            ourEpisodeIds = emptySet(), // the new episode isn't in our set yet
        )
        assertEquals(listOf("klovn"), result.missingIds)
    }

    @Test
    fun `an episode we already hold contributes nothing`() {
        val result = computeLibraryDiff(
            jfItems = listOf(
                jf("klovn", "Series"),
                jf("klovn-s11e07", "Episode", seriesId = "klovn"),
            ),
            ourTopLevelIds = setOf("klovn"),
            ourEpisodeIds = setOf("klovn-s11e07"),
        )
        assertTrue(result.missingIds.isEmpty())
    }

    @Test
    fun `an episode with no series id is skipped not crashed on`() {
        val result = computeLibraryDiff(
            jfItems = listOf(jf("orphan-ep", "Episode", seriesId = null)),
            ourTopLevelIds = emptySet(),
            ourEpisodeIds = emptySet(),
        )
        assertTrue(result.missingIds.isEmpty())
    }

    @Test
    fun `a series we hold that jellyfin no longer reports is stale not deleted`() {
        val result = computeLibraryDiff(
            jfItems = listOf(jf("movie-1", "Movie")),
            ourTopLevelIds = setOf("movie-1", "gone-series"),
            ourEpisodeIds = emptySet(),
        )
        assertEquals(listOf("gone-series"), result.staleTopLevelIds)
    }

    @Test
    fun `a same-size episode swap is still caught by id diff unlike a count comparison`() {
        // The exact case FR-181-3's count-only check would miss: one episode removed, one added, net
        // count unchanged. The id-level diff catches it because it compares individual ids, not totals.
        val result = computeLibraryDiff(
            jfItems = listOf(
                jf("show", "Series"),
                jf("show-e2", "Episode", seriesId = "show"), // the new one, not held yet
            ),
            ourTopLevelIds = setOf("show"),
            ourEpisodeIds = setOf("show-e1"), // the old one jellyfin no longer has — count would net to 1 either way
        )
        assertEquals(listOf("show"), result.missingIds)
    }

    @Test
    fun `scannedCount reflects the full scoped jellyfin set`() {
        val result = computeLibraryDiff(
            jfItems = listOf(jf("a", "Movie"), jf("b", "Movie"), jf("c", "Episode", seriesId = "b")),
            ourTopLevelIds = emptySet(),
            ourEpisodeIds = emptySet(),
        )
        assertEquals(3, result.scannedCount)
    }
}
