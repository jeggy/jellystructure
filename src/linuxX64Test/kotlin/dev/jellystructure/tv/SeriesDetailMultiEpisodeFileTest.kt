package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaSegmentStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R309 (FR-R309-1/6/7) — production's exact shape, 2026-09-26: the three episodes of
 * `S01E01E02E03.mkv` share the file's one Jellyfin id (Phase 152's path match). The series payload
 * answered one entry per file (Johnny Bravo: 60 of 165 episodes), and a Continue Watching tile on the
 * file read S1:E1.
 */
class SeriesDetailMultiEpisodeFileTest {
    private lateinit var dbPath: String
    private lateinit var service: DetailService
    private lateinit var mediaStore: MediaStore

    private val device = DeviceData(
        deviceId = "dev1", deviceToken = "tok1", jellyfinUserId = "user1", jellyfinUsername = "user1",
        jellyfinUserToken = "jtok1", isAdmin = false,
    )

    @BeforeTest
    fun setUp() {
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-r309-$suffix.db"
        val db = createDatabase(dbPath)
        val configStore = ConfigStore("/tmp/jellystructure-test-r309-$suffix.toml")
        mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-r309-$suffix-tags.json"), configStore)
        val artwork = ArtworkDownloader(TmdbClient(configStore), Screengrabber())
        service = DetailService(mediaStore, JellyfinClient(), configStore, artwork, MediaSegmentStore(db), RaviloDeviceService(db), PlaybackStartSampleStore(db))
    }

    @AfterTest
    fun tearDown() { for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") } }

    private fun episode(n: Int, path: String, jellyfinId: String?, partIndex: Int = 0, partCount: Int = 1) = Episode(
        filename = path.substringAfterLast('/'), path = path, seasonNumber = 1, episodeNumber = n,
        tracks = emptyList(), issueCount = 0, jellyfinId = jellyfinId, runtime = 7,
        partIndex = partIndex, partCount = partCount,
    )

    private fun series(episodes: List<Episode>) = MediaItem(
        id = "show", title = "Show", year = 1997, kind = MediaKind.TV_SHOW, path = "/tv/Show", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = episodes, jellyfinId = "series-jf",
    )

    private val file1 = "/tv/Show/Season 1/S01E01E02E03.mkv"
    private val file2 = "/tv/Show/Season 1/S01E04E05E06.mkv"
    private val threeInOne = listOf(
        episode(1, file1, "jf-a", 0, 3), episode(2, file1, "jf-a", 1, 3), episode(3, file1, "jf-a", 2, 3),
        episode(4, file2, "jf-b", 0, 3), episode(5, file2, "jf-b", 1, 3), episode(6, file2, "jf-b", 2, 3),
        episode(7, "/tv/Show/Season 1/S01E07.mkv", "jf-c"),
    )

    @Test
    fun `every episode of a multi-episode file reaches the series payload`() = runBlocking {
        mediaStore.addOrUpdate(series(threeInOne))
        val eps = service.getSeriesDetail(device, "series-jf")!!.seasons.single().episodes
        assertEquals((1..7).toList(), eps.map { it.episodeNumber })
        assertEquals(listOf("jf-a", "jf-a", "jf-a"), eps.take(3).map { it.id })
        assertEquals(setOf(file1), eps.take(3).map { it.file }.toSet())
        assertEquals(listOf(3, 3, 3), eps.take(3).map { it.partCount })
    }

    @Test
    fun `R346 — Specials are listed after the last season`() = runBlocking {
        val special = episode(1, "/tv/Show/Specials/S00E01.mkv", "jf-sp").copy(seasonNumber = 0)
        val s2 = episode(1, "/tv/Show/Season 2/S02E01.mkv", "jf-s2").copy(seasonNumber = 2)
        mediaStore.addOrUpdate(series(threeInOne + special + s2))
        assertEquals(listOf(1, 2, 0), service.getSeriesDetail(device, "series-jf")!!.seasons.map { it.index })
    }

    @Test
    fun `R343 — Shuffle on 9 counted episodes — rows not files — specials and unplayable rows out`() = runBlocking {
        // 7 rows (3 + 3 + 1) in season 1: not enough; a special and a row with no Jellyfin item do not count.
        val special = episode(1, "/tv/Show/Specials/S00E01.mkv", "jf-sp").copy(seasonNumber = 0)
        val noItem = episode(8, "/tv/Show/Season 1/S01E08.mkv", null)
        mediaStore.addOrUpdate(series(threeInOne + special + noItem))
        val few = service.getSeriesDetail(device, "series-jf")!!
        assertEquals(false, few.shuffle); assertEquals(true, few.startOver)
        val two = listOf(episode(8, "/tv/Show/Season 1/S01E08.mkv", "jf-d"), episode(9, "/tv/Show/Season 1/S01E09.mkv", "jf-e"))
        mediaStore.addOrUpdate(series(threeInOne + two))
        assertEquals(true, service.getSeriesDetail(device, "series-jf")!!.shuffle)
    }

    @Test
    fun `two files claiming one id still reach the client as one`() = runBlocking {
        // The auto-play-next loop fix's own case: a second FILE carrying episode 7's id.
        val copy = episode(8, "/tv/Show/Season 1/copy/S01E07.mkv", "jf-c")
        mediaStore.addOrUpdate(series(threeInOne + copy))
        val eps = service.getSeriesDetail(device, "series-jf")!!.seasons.single().episodes
        assertEquals(1, eps.count { it.id == "jf-c" })
        assertEquals((1..7).toList(), eps.map { it.episodeNumber })
    }

    @Test
    fun `a resume on a multi-episode file names the whole range`() {
        val span = resolvedEpisodeSpan(series(threeInOne), "jf-a", season = 1, episode = 1)
        assertEquals(EpisodeSpan(1, 1, 3), span)
        assertEquals("S01E01–E03", span.code)
    }

    @Test
    fun `R199's fallback numbering carries the range too`() {
        val span = resolvedEpisodeSpan(series(threeInOne), "jf-b", season = null, episode = null)
        assertEquals("S01E04–E06", span.code)
    }

    @Test
    fun `a lone episode carries no range end`() {
        val span = resolvedEpisodeSpan(series(threeInOne), "jf-c", season = 1, episode = 7)
        assertNull(span.episodeEnd)
        assertEquals("S01E07", span.code)
    }

    @Test
    fun `a copy of an episode in another file never widens the range`() {
        val copy = episode(9, "/tv/Show/copy/S01E07.mkv", "jf-c")
        assertNull(resolvedEpisodeSpan(series(threeInOne + copy), "jf-c", season = 1, episode = 7).episodeEnd)
    }
}
