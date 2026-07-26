package dev.jellystructure.media

import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bug fix (Ravilo auto-play-next loop): two files claiming the same episode used to share one Jellyfin
 * id, so the "next episode" after episode 1 could be episode 1 itself. Real reproduction: the whole
 * `Tellytots.S01…` folder had been extracted a second time inside the S02 folder.
 */
class DuplicateEpisodesTest {

    private fun ep(
        path: String,
        season: Int?,
        number: Int?,
        jellyfinId: String? = null,
        partIndex: Int = 0,
        partCount: Int = 1,
    ) = Episode(
        filename = path.substringAfterLast('/'),
        path = path,
        seasonNumber = season,
        episodeNumber = number,
        tracks = emptyList(),
        issueCount = 0,
        jellyfinId = jellyfinId,
        partIndex = partIndex,
        partCount = partCount,
    )

    private fun series(episodes: List<Episode>) = MediaItem(
        id = "s", title = "s", year = null, kind = MediaKind.TV_SHOW, path = "/x", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = episodes,
    )

    // The reported library: S01 exists twice, the copy nested inside the S02 folder, both carrying the
    // Jellyfin id of the real S01E01.
    private val tellytots = listOf(
        ep("/m/Tellytots/Tellytots.S01/S01E01.mkv", 1, 1, "jf-e1"),
        ep("/m/Tellytots/Tellytots.S02/Tellytots.S01/S01E01.mkv", 1, 1, "jf-e1"),
        ep("/m/Tellytots/Tellytots.S01/S01E02.mkv", 1, 2, "jf-e2"),
        ep("/m/Tellytots/Tellytots.S02/Tellytots.S01/S01E02.mkv", 1, 2, "jf-e2"),
        ep("/m/Tellytots/Tellytots.S02/S02E01.mkv", 2, 1, "jf-s2e1"),
    )

    @Test
    fun `a healthy series is untouched`() {
        val eps = listOf(
            ep("/m/S01E01.mkv", 1, 1, "a"),
            ep("/m/S01E02.mkv", 1, 2, "b"),
            ep("/m/S02E01.mkv", 2, 1, "c"),
        )
        assertEquals(0, DuplicateEpisodes.extraCount(eps))
        assertEquals(eps, DuplicateEpisodes.deduped(eps))
        assertEquals(eps, DuplicateEpisodes.withUniqueIds(eps))
        assertTrue(DuplicateEpisodes.describe(eps).isEmpty())
    }

    @Test
    fun `a multi-episode file is not a duplicate`() {
        // Phase 149: three episodes share one physical file but have distinct numbers.
        val eps = listOf(
            ep("/m/S01E01E02E03.mkv", 1, 1, "a", partIndex = 0, partCount = 3),
            ep("/m/S01E01E02E03.mkv", 1, 2, "b", partIndex = 1, partCount = 3),
            ep("/m/S01E01E02E03.mkv", 1, 3, "c", partIndex = 2, partCount = 3),
        )
        assertEquals(0, DuplicateEpisodes.extraCount(eps))
        assertEquals(eps, DuplicateEpisodes.deduped(eps))
    }

    @Test
    fun `duplicate files collapse to one entry per episode`() {
        assertEquals(2, DuplicateEpisodes.extraCount(tellytots))
        val deduped = DuplicateEpisodes.deduped(tellytots)
        assertEquals(3, deduped.size)
        // The nested re-extraction loses: the original S01 folder's path sorts first.
        assertEquals(
            listOf(
                "/m/Tellytots/Tellytots.S01/S01E01.mkv",
                "/m/Tellytots/Tellytots.S01/S01E02.mkv",
                "/m/Tellytots/Tellytots.S02/S02E01.mkv",
            ),
            deduped.map { it.path },
        )
        // The whole point: episode 1's successor is episode 2, not episode 1 again.
        assertEquals(listOf(1, 2, 1), deduped.map { it.episodeNumber })
    }

    @Test
    fun `no two exposed episodes share an id`() {
        val ids = DuplicateEpisodes.deduped(tellytots).mapNotNull { it.jellyfinId }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `redundant copies lose the borrowed jellyfin id but keep their row`() {
        val unique = DuplicateEpisodes.withUniqueIds(tellytots)
        assertEquals(tellytots.size, unique.size)
        assertEquals("jf-e1", unique.first { it.path == "/m/Tellytots/Tellytots.S01/S01E01.mkv" }.jellyfinId)
        assertNull(unique.first { it.path == "/m/Tellytots/Tellytots.S02/Tellytots.S01/S01E01.mkv" }.jellyfinId)
        val ids = unique.mapNotNull { it.jellyfinId }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `an entry that resolved a jellyfin id wins over one that did not`() {
        val eps = listOf(
            ep("/a/S01E01.mkv", 1, 1, jellyfinId = null),
            ep("/b/S01E01.mkv", 1, 1, jellyfinId = "real"),
        )
        assertEquals("real", DuplicateEpisodes.deduped(eps).single().jellyfinId)
    }

    @Test
    fun `unparseable filenames are never treated as duplicates of each other`() {
        // Two files nothing could number: they collide on no key, and dropping them would hide files
        // the operator can still rename.
        val eps = listOf(
            ep("/m/bonus-feature.mkv", null, null),
            ep("/m/making-of.mkv", null, null),
        )
        assertEquals(0, DuplicateEpisodes.extraCount(eps))
        assertEquals(2, DuplicateEpisodes.deduped(eps).size)
    }

    @Test
    fun `triage reports the duplicates as a library issue`() {
        assertEquals(2, TriageDetection.duplicateEpisodeCount(series(tellytots)))
        assertEquals(0, TriageDetection.duplicateEpisodeCount(series(DuplicateEpisodes.deduped(tellytots))))
        // A movie has no episodes to duplicate.
        val movie = MediaItem(
            id = "m", title = "m", year = null, kind = MediaKind.MOVIE, path = "/x", tmdbId = null,
            originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(),
            issueCount = 0, scannedAt = 0L,
        )
        assertEquals(0, TriageDetection.duplicateEpisodeCount(movie))
    }

    @Test
    fun `describe names the episode and every colliding file`() {
        val lines = DuplicateEpisodes.describe(tellytots)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("S01E01 appears in 2 files: "), lines[0])
        assertTrue(lines[1].startsWith("S01E02 appears in 2 files: "), lines[1])
    }
}
