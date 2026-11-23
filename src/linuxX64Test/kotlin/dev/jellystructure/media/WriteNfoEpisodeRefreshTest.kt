package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.nfo.NfoWriter
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-5440 (FR-3) — the bulk `write_nfo` pass refreshes a series' episode NFOs when it has just rewritten the series' own
 *  NFO (episode NFOs carry no hash), and writes none for a series whose NFO is unchanged. */
class WriteNfoEpisodeRefreshTest {
    private val dir = "/tmp/jellystructure-test-write-nfo-episodes-${getpid()}"
    private lateinit var store: MediaStore

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(Path("$dir/Show/Season 01"))
        val configStore = ConfigStore("$dir/config.toml")
        store = MediaStore(createDatabase("$dir/test.db"), JsTagStore("$dir/tags.json"), configStore)
    }

    @AfterTest
    fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private val episode = Episode(
        filename = "Show - S01E01.mkv", path = "$dir/Show/Season 01/Show - S01E01.mkv", seasonNumber = 1, episodeNumber = 1,
        tracks = emptyList(), issueCount = 0, title = "One", airDate = "2022-06-03", jellyfinId = "jf-ep",
    )

    private fun series(nfoHash: String?) = MediaItem(
        id = "show", title = "Show", year = 2022, kind = MediaKind.TV_SHOW, path = "$dir/Show", tmdbId = 1, originalLanguage = null,
        posterPath = null, overview = null, director = null, tracks = emptyList(), issueCount = 0, scannedAt = 0,
        releaseDate = "2022-06-03", episodes = listOf(episode), nfoHash = nfoHash,
    )

    @Test
    fun `a rewritten series NFO also rewrites its episode NFOs with the air date`() = runBlocking {
        val result = PipelineStepOps.writeNfo(series(nfoHash = "stale"), store, "", emptyList(), allowForeign = true)
        assertEquals(PipelineStepOps.NfoResult.WRITTEN, result)
        assertTrue(assertNotNull(NfoWriter.readRawEpisode(episode)).contains("<aired>2022-06-03</aired>"))
    }

    @Test
    fun `an unchanged series NFO writes no episode NFO`() = runBlocking {
        val current = series(nfoHash = null)
        val result = PipelineStepOps.writeNfo(current.copy(nfoHash = NfoWriter.contentHash(current, "", emptyList())), store, "", emptyList(), allowForeign = true)
        assertEquals(PipelineStepOps.NfoResult.UNCHANGED, result)
        assertNull(NfoWriter.readRawEpisode(episode))
    }
}
