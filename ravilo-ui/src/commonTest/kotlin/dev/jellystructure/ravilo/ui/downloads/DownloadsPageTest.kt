package dev.jellystructure.ravilo.ui.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-4071 (DEB-3995 FR-9, FR-12) — what the Downloads page lists and totals. Pure: the model has no screen in it. */
class DownloadsPageTest {
    private fun rec(id: String, kind: String = "movie", title: String = "T$id", bytes: Long = 1_000_000_000L, queuedAt: Long = 0L,
                    seriesId: String? = null, seriesTitle: String? = null, season: Int? = null, episode: Int? = null, watched: Boolean = false,
                    state: RecordState = RecordState.STARTED) =
        DownloadRecord(itemId = id, kind = kind, title = title, seriesId = seriesId, seriesTitle = seriesTitle, season = season, episode = episode,
            quality = "medium", height = 720, bytes = bytes, queuedAt = queuedAt, state = state, watched = watched)

    private val done = DownloadPhase.Downloaded

    @Test fun `the Downloading block holds every entry that is not on the phone yet, in queue order, and the lists hold only finished ones`() {
        val records = listOf(
            rec("run", queuedAt = 3), rec("paused", queuedAt = 1), rec("wait", queuedAt = 4), rec("fail", queuedAt = 2), rec("q", queuedAt = 5, state = RecordState.QUEUED),
            rec("film"), rec("ep", kind = "episode", seriesId = "s", seriesTitle = "S", season = 1, episode = 1),
        )
        val phases = mapOf(
            "run" to DownloadPhase.Running(40), "paused" to DownloadPhase.Paused(10), "wait" to DownloadPhase.WaitingWifi, "fail" to DownloadPhase.Failed(500),
            "film" to done, "ep" to done,
        )
        val page = buildDownloadsPage(records, phases)
        assertEquals(listOf("paused", "fail", "run", "wait", "q"), page.downloading.map { it.itemId })
        assertEquals(DownloadPhase.Queued, page.downloading.last().phase, "a record with no ticket reads as queued")
        assertEquals(listOf("film"), page.films.map { it.itemId })
        assertEquals(listOf("ep"), page.series.single().episodes.map { it.itemId })
        assertTrue(page.downloading.none { it.phase == done } && page.films.all { it.phase == done })
    }

    @Test fun `an entry moves from the block into its list when it finishes`() {
        val r = listOf(rec("a"))
        assertEquals(listOf("a"), buildDownloadsPage(r, mapOf("a" to DownloadPhase.Running(99))).downloading.map { it.itemId })
        val after = buildDownloadsPage(r, mapOf("a" to done))
        assertTrue(after.downloading.isEmpty()); assertEquals(listOf("a"), after.films.map { it.itemId })
    }

    @Test fun `a series groups by series then season and orders its episodes, and its total adds them up`() {
        fun ep(id: String, s: Int, e: Int, title: String = "N", series: String = "n") =
            rec(id, kind = "episode", title = "e$id", bytes = 650_000_000L, seriesId = series, seriesTitle = title, season = s, episode = e)
        val records = listOf(ep("c", 1, 3), ep("a", 1, 1), ep("d", 2, 1), ep("b", 1, 2), ep("z", 1, 1, "Zed", "zed"))
        val phases = records.associate { it.itemId to done }
        val page = buildDownloadsPage(records, phases)
        assertEquals(listOf("N", "Zed"), page.series.map { it.title })
        val n = page.series.first()
        assertEquals(listOf(1, 2), n.seasons.map { it.season })
        assertEquals(listOf("a", "b", "c"), n.seasons[0].episodes.map { it.itemId })
        assertEquals(4, n.episodeCount)
        assertEquals(4 * 650_000_000L, n.bytes)
        assertEquals(n.bytes + 650_000_000L, page.seriesBytes)
    }

    @Test fun `films are listed by title and a film is never grouped as a series`() {
        val records = listOf(rec("b", title = "Brot"), rec("a", title = "alfa"), rec("c", title = "Cove"))
        val page = buildDownloadsPage(records, records.associate { it.itemId to done })
        assertEquals(listOf("alfa", "Brot", "Cove"), page.films.map { it.record.title })
        assertTrue(page.series.isEmpty())
    }

    @Test fun `this profile's total counts what is on the disk, a running entry's part included, and Delete all names the same figure`() {
        val records = listOf(rec("film", bytes = 6_200_000_000L), rec("run", bytes = 650_000_000L))
        val page = buildDownloadsPage(records, mapOf("film" to done, "run" to DownloadPhase.Running(42)))
        assertEquals(6_200_000_000L + 273_000_000L, page.profileBytes)
        assertEquals(page.profileBytes, page.deleteAllBytes)
    }

    @Test fun `Delete watched frees only the watched titles that are fully on the phone`() {
        val records = listOf(rec("w", bytes = 7L, watched = true), rec("u", bytes = 5L), rec("running", bytes = 100L, watched = true))
        val page = buildDownloadsPage(records, mapOf("w" to done, "u" to done, "running" to DownloadPhase.Running(50)))
        assertEquals(7L, page.watchedBytes)
    }

    @Test fun `an empty profile is empty, and a record the engine lost is not drawn as a ghost`() {
        assertTrue(buildDownloadsPage(emptyList(), emptyMap()).isEmpty)
        val ghost = buildDownloadsPage(listOf(rec("gone")), emptyMap())   // handed to the engine, which no longer has it
        assertTrue(ghost.isEmpty)
    }

    @Test fun `the storage bar's Ravilo part is everything Ravilo holds and the three parts add up to the volume`() {
        val bar = storageBar(raviloBytes = 36_300_000_000L, freeBytes = 41_200_000_000L, totalBytes = 126_100_000_000L)
        assertEquals(36_300_000_000L, bar.ravilo)
        assertEquals(41_200_000_000L, bar.free)
        assertEquals(48_600_000_000L, bar.otherApps)   // what is neither Ravilo's nor free
        assertEquals(126_100_000_000L, bar.total)
        assertEquals(1f, bar.fraction(bar.ravilo) + bar.fraction(bar.otherApps) + bar.fraction(bar.free), 0.0001f)
    }

    @Test fun `a volume that cannot be read draws an empty bar, and Ravilo is clamped to what is not free`() {
        assertEquals(StorageBar(0, 0, 0), storageBar(5L, 5L, 0L))
        val clamped = storageBar(raviloBytes = 90L, freeBytes = 50L, totalBytes = 100L)
        assertEquals(50L, clamped.ravilo); assertEquals(0L, clamped.otherApps); assertEquals(100L, clamped.total)
        assertFalse(storageBar(1L, 1L, 10L).fraction(0L) > 0f)
    }

    @Test fun `only a finished download is in the downloaded set that marks a card`() {
        val phases = mapOf(
            "a" to done, "b" to DownloadPhase.Running(99), "c" to DownloadPhase.Queued, "d" to DownloadPhase.Failed(), "e" to DownloadPhase.Paused(5),
            "f" to DownloadPhase.WaitingWifi, "g" to DownloadPhase.WaitingServer,
        )
        assertEquals(setOf("a"), downloadedIds(phases))
    }
}
