package dev.jellystructure.media

import dev.jellystructure.model.Keyword
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase 269 (FR-269-3a) — the signals backfill batch. */
class SignalsBackfillTest {
    private fun item(n: Int, fetched: Boolean = false, tmdb: Boolean = true, kind: MediaKind = MediaKind.MOVIE) = MediaItem(
        id = "m$n", title = "T$n", year = 2000, kind = kind, path = "/m/$n.mkv", tmdbId = if (tmdb) n else null,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = n.toLong(),
        keywords = if (fetched) listOf(Keyword(1, "k")) else null,
    )

    @Test
    fun `only unfetched matched titles outside the working set and the longest-unscanned first`() {
        val all = (1..10).map { item(it) } + item(11, fetched = true) + item(12, tmdb = false) + item(13, kind = MediaKind.MUSIC_VIDEO)
        val batch = SignalsBackfill.pick(all, workingSet = listOf(all[0], all[1]), limit = 3)
        assertEquals(listOf("m3", "m4", "m5"), batch.items.map { it.id })
        assertEquals(5, batch.remaining)
    }

    @Test
    fun `nothing left to fetch is an empty batch`() {
        assertEquals(0, SignalsBackfill.pick(listOf(item(1, fetched = true)), emptyList()).items.size)
    }
}
