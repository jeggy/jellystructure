package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.formatSize
import dev.jellystructure.model.seasonSizeBytes
import dev.jellystructure.model.sizeBytes
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.RowOrder
import dev.jellystructure.shared.tv.RowSort
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Phase 268 (FR-268-8) — the one size rule, its formatter, the Library sort, the batched write, the row order. */
class FileSizeTest {
    private val dbPath = "/tmp/jellystructure-test-filesize-${getpid()}.db"
    @AfterTest fun tearDown() { for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") } }

    private fun ep(path: String, season: Int, episode: Int, size: Long?) =
        Episode(filename = path.substringAfterLast('/'), path = path, seasonNumber = season, episodeNumber = episode, tracks = emptyList(), issueCount = 0, fileSizeBytes = size)

    private fun series(id: String, vararg eps: Episode) = MediaItem(
        id = id, title = id, year = 2000, kind = MediaKind.TV_SHOW, path = "/series/$id", tmdbId = null, originalLanguage = null,
        posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L, episodes = eps.toList(), jellyfinId = id,
    )

    private fun film(id: String, size: Long?, title: String = id) = MediaItem(
        id = id, title = title, year = 2000, kind = MediaKind.MOVIE, path = "/movies/$id.mkv", tmdbId = null, originalLanguage = null,
        posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L, jellyfinId = id, fileSizeBytes = size,
    )

    @Test
    fun theSizeRule() {
        assertEquals(4_000L, film("f", 4_000).sizeBytes())
        // A 3-in-1 file (three episodes, one path) once; a duplicate copy of an episode in a second file counted.
        val s = series("s",
            ep("/s/S01E01E02E03.mkv", 1, 1, 900), ep("/s/S01E01E02E03.mkv", 1, 2, 900), ep("/s/S01E01E02E03.mkv", 1, 3, 900),
            ep("/s/S01E04.mkv", 1, 4, 300), ep("/s/S01E04 copy.mkv", 1, 4, 300),
            ep("/s/S02E01.mkv", 2, 1, 500),
        )
        assertEquals(2_000L, s.sizeBytes())
        assertEquals(1_500L, s.seasonSizeBytes(1))
        assertEquals(500L, s.seasonSizeBytes(2))
        assertNull(series("u", ep("/u/1.mkv", 1, 1, 10), ep("/u/2.mkv", 1, 2, null)).sizeBytes(), "one unknown file ⇒ the whole title unknown, never a partial sum")
        assertNull(film("n", null).sizeBytes())
    }

    @Test
    fun theFormatter() {
        assertEquals("—", formatSize(null))
        assertEquals("812 MB", formatSize(812_300_000))
        assertEquals("23.4 GB", formatSize(23_440_000_000))
        assertEquals("16.1 TB", formatSize(16_100_000_000_000))
        assertEquals("999 B", formatSize(999))
    }

    @Test
    fun theLibrarySortAndTheBatchedWrite() = runBlocking {
        val store = MediaStore(createDatabase(dbPath), JsTagStore("/tmp/jellystructure-test-filesize-${getpid()}-tags.json"), ConfigStore("/tmp/jellystructure-test-filesize-${getpid()}.toml"))
        store.addOrUpdate(film("a", 300, "Alpha")); store.addOrUpdate(film("b", null, "Bravo")); store.addOrUpdate(film("c", 900, "Charlie"))
        assertEquals(listOf("c", "a", "b"), store.list(sort = "size", pageSize = 10).items.map { it.id }, "largest first, unknown last")
        assertEquals(listOf("a", "c", "b"), store.list(sort = "size_asc", pageSize = 10).items.map { it.id }, "smallest first, unknown still last")

        val feed = store.feedVersion
        assertEquals(2, store.setFileSizes(mapOf("/movies/b.mkv" to 100L, "/movies/c.mkv" to 950L, "/movies/a.mkv" to 300L)))
        assertEquals(feed + 1, store.feedVersion, "one bump for the batch")
        assertEquals(100L, store.get("b")!!.sizeBytes())
        assertEquals(0, store.setFileSizes(mapOf("/movies/b.mkv" to 100L)), "nothing changed, nothing written")
        assertEquals(feed + 1, store.feedVersion)
    }

    @Test
    fun aRowOrderedBySize() {
        data class T(val id: String, val size: Long?)
        val items = listOf(T("s", 10), T("u", null), T("l", 900), T("m", 50))
        fun order(desc: Boolean) = RowOrder.resolveAll(items, RowSort("size", desc), emptyList(), id = { it.id }, added = { 0L }, year = { null }, title = { it.id }, sortName = { null }, size = { it.size }).map { it.id }
        assertEquals(listOf("l", "m", "s", "u"), order(true))
        assertEquals(listOf("s", "m", "l", "u"), order(false))
        assertNull(RowOrder.problem(RowConfig(id = "r", kind = RowKind.CUSTOM, sort = RowSort("size", true))), "the validator accepts size")
        assertEquals("largest first", RowOrder.summary(RowConfig(id = "r", kind = RowKind.CUSTOM, sort = RowSort("size", true))))
    }
}
