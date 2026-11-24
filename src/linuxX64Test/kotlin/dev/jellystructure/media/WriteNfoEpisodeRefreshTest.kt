package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Person
import dev.jellystructure.nfo.NfoWriter
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
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

/** DEB-5458 (FR-4, FR-5, FR-6) — `write_nfo` settles: an episode NFO is written only when its bytes differ from the file on disk,
 *  a repair bumps `nfoWrittenAt` only when it wrote, and a series NFO the file already holds is not rewritten. */
@OptIn(ExperimentalForeignApi::class)
class WriteNfoSettlesTest {
    private val dir = "/tmp/jellystructure-test-write-nfo-settles-${getpid()}"
    private lateinit var store: MediaStore
    private val oldMtime = 1_000_000L

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

    private fun episode(jellyfinId: String?) = Episode(
        filename = "Show - S01E01.mkv", path = "$dir/Show/Season 01/Show - S01E01.mkv", seasonNumber = 1, episodeNumber = 1,
        tracks = emptyList(), issueCount = 0, title = "One", airDate = "2022-06-03", jellyfinId = jellyfinId,
    )

    private fun series(ep: Episode, nfoHash: String?, cast: List<Person> = emptyList()) = MediaItem(
        id = "show", title = "Show", year = 2022, kind = MediaKind.TV_SHOW, path = "$dir/Show", tmdbId = 1, originalLanguage = null,
        posterPath = null, overview = null, director = null, tracks = emptyList(), issueCount = 0, scannedAt = 0,
        releaseDate = "2022-06-03", episodes = listOf(ep), cast = cast, nfoHash = nfoHash,
    )

    private fun hashOf(item: MediaItem) = NfoWriter.contentHash(item, "", emptyList())

    private fun setMtime(path: String) = memScoped {
        val b = alloc<platform.posix.utimbuf>()
        b.actime = oldMtime; b.modtime = oldMtime
        assertEquals(0, platform.posix.utime(path, b.ptr))
    }

    private fun mtime(path: String): Long = memScoped {
        val st = alloc<platform.posix.stat>()
        assertEquals(0, platform.posix.stat(path, st.ptr))
        st.st_mtim.tv_sec
    }

    /** Writes [ep]'s NFO as it would be written now and ages its mtime, so a later rewrite shows. */
    private suspend fun currentEpisodeNfo(ep: Episode, cast: List<Person> = emptyList()): String {
        NfoWriter.writeEpisodeNfos(listOf(ep), cast).forEach { it.second.getOrThrow() }
        val path = NfoWriter.episodeNfoPath(ep)
        setMtime(path)
        return path
    }

    @Test
    fun `a series whose hash moved but whose episode NFOs are current writes no episode file`() = runBlocking {
        val ep = episode("jf-ep")
        val path = currentEpisodeNfo(ep)
        val result = PipelineStepOps.writeNfo(series(ep, nfoHash = "stale"), store, "", emptyList(), allowForeign = true)
        assertEquals(PipelineStepOps.NfoResult.WRITTEN, result)
        assertEquals(oldMtime, mtime(path))
    }

    @Test
    fun `a cast change on the series rewrites the episode NFO that changes`() = runBlocking {
        val ep = episode("jf-ep")
        val path = currentEpisodeNfo(ep)
        val cast = listOf(Person(tmdbId = 7, name = "Some Actor", character = "Role"))
        PipelineStepOps.writeNfo(series(ep, nfoHash = "stale", cast = cast), store, "", emptyList(), allowForeign = true)
        assertTrue(mtime(path) > oldMtime)
        assertTrue(assertNotNull(NfoWriter.readRawEpisode(ep)).contains("<name>Some Actor</name>"))
    }

    @Test
    fun `a repair whose episode NFO is already current writes nothing and leaves nfoWrittenAt alone`() = runBlocking {
        val ep = episode(jellyfinId = null)
        val path = currentEpisodeNfo(ep)
        val s = series(ep, nfoHash = null)
        val current = s.copy(nfoHash = hashOf(s))
        store.updateOne(current)
        val result = PipelineStepOps.writeNfo(current, store, "", emptyList(), allowForeign = true)
        assertEquals(PipelineStepOps.NfoResult.UNCHANGED, result)
        assertEquals(oldMtime, mtime(path))
        assertNull(store.get("show")?.nfoWrittenAt)
    }

    @Test
    fun `a repair whose episode NFO is missing writes it and bumps nfoWrittenAt`() = runBlocking {
        val ep = episode(jellyfinId = null)
        val s = series(ep, nfoHash = null)
        val current = s.copy(nfoHash = hashOf(s))
        store.updateOne(current)
        PipelineStepOps.writeNfo(current, store, "", emptyList(), allowForeign = true)
        assertNotNull(NfoWriter.readRawEpisode(ep))
        assertTrue(store.get("show")?.nfoWrittenAt != null)
    }

    @Test
    fun `a series NFO the file already holds is recorded but not rewritten`() = runBlocking {
        val ep = episode("jf-ep")
        val s = series(ep, nfoHash = null)
        NfoWriter.write(s, "", emptyList()).getOrThrow()
        val path = NfoWriter.nfoPath(s)
        setMtime(path)
        store.updateOne(s.copy(nfoHash = "stale"))
        val result = PipelineStepOps.writeNfo(s.copy(nfoHash = "stale"), store, "", emptyList(), allowForeign = true)
        assertEquals(PipelineStepOps.NfoResult.UNCHANGED, result)
        assertEquals(oldMtime, mtime(path))
        assertEquals(hashOf(s), store.get("show")?.nfoHash)
        assertNull(store.get("show")?.nfoWrittenAt)
    }

    @Test
    fun `includeEpisodes writing a missing episode NFO under an unchanged series NFO stamps nfoWrittenAt`() = runBlocking {
        val ep = episode("jf-ep")
        val s = series(ep, nfoHash = null)
        val current = s.copy(nfoHash = hashOf(s))
        store.updateOne(current)
        val result = PipelineStepOps.writeNfo(current, store, "", emptyList(), allowForeign = true, includeEpisodes = true)
        assertEquals(PipelineStepOps.NfoResult.WRITTEN, result)
        assertNotNull(NfoWriter.readRawEpisode(ep))
        assertTrue(store.get("show")?.nfoWrittenAt != null)
    }

    @Test
    fun `includeEpisodes with every episode NFO current writes nothing and stamps nothing`() = runBlocking {
        val ep = episode("jf-ep")
        val path = currentEpisodeNfo(ep)
        val s = series(ep, nfoHash = null)
        val current = s.copy(nfoHash = hashOf(s))
        store.updateOne(current)
        val result = PipelineStepOps.writeNfo(current, store, "", emptyList(), allowForeign = true, includeEpisodes = true)
        assertEquals(PipelineStepOps.NfoResult.UNCHANGED, result)
        assertEquals(oldMtime, mtime(path))
        assertNull(store.get("show")?.nfoWrittenAt)
    }

    @Test
    fun `the series NFO does not depend on the order of cast members that tie`() {
        val a = Person(tmdbId = 1, name = "A", order = 3)
        val b = Person(tmdbId = 2, name = "B", order = 3)
        val ep = episode("jf-ep")
        assertEquals(hashOf(series(ep, null, listOf(a, b))), hashOf(series(ep, null, listOf(b, a))))
    }
}
