package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.music.FanartTvClient
import dev.jellystructure.music.MusicLibraryRows
import dev.jellystructure.music.MusicMediaService
import dev.jellystructure.music.MusicStore
import dev.jellystructure.music.MusicTagWriter
import dev.jellystructure.subtitles.SubtitleCheckService
import dev.jellystructure.subtitles.SubtitleReferences
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-5537 (a-permanent-failure-is-not-retried-every-hour.md, Acceptance 1) — the four hourly loops, one at a time:
 * a failure that will repeat is remembered against its input and left alone by the scheduled pass until the input
 * changes or 7 days pass; an operator action asks again; `.m4a`/`.mp4` are unsupported and not failed; a file that
 * is gone enqueues nothing.
 */
class PermanentFailureNotRetriedTest {
    private val dir = "/tmp/jellystructure-test-permfail-${getpid()}"
    private lateinit var db: JellystructureDb
    private lateinit var config: ConfigStore
    private var clock = 1_000_000L
    private lateinit var known: KnownFailures

    @BeforeTest
    fun setUp() {
        platform.posix.system("rm -rf '$dir'")
        SystemFileSystem.createDirectories(Path(dir))
        db = createDatabase("$dir/test.db")
        config = ConfigStore("$dir/config.toml")
        known = KnownFailures(db) { clock }
    }

    @AfterTest
    fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun file(name: String, text: String = "content"): String = "$dir/$name".also { FileIo.writeText(Path(it), text) }

    // ── FR-1: the record ──

    @Test
    fun `a failed input is skipped retried after 7 days and retried at once when its mtime changes`() {
        assertTrue(known.record("k", "/a", 10, 100, "why"))
        assertTrue(known.skip("k", "/a", 10, 100))
        clock += KnownFailures.RETRY_AFTER_SEC - 1
        assertTrue(known.skip("k", "/a", 10, 100), "still inside the 7 days")
        assertFalse(known.skip("k", "/a", 10, 101), "a different mtime is a different input")
        assertFalse(known.skip("k", "/a", 11, 100), "a different size is a different input")
        clock += 1
        assertFalse(known.skip("k", "/a", 10, 100), "7 days have passed")
        assertFalse(known.skip("other-kind", "/a", 10, 100), "another loop's record is not this one's")
    }

    @Test
    fun `a failure already on record is not new a changed input or an aged one is`() {
        assertTrue(known.record("k", "/a", 10, 100, "why"))
        assertFalse(known.record("k", "/a", 10, 100, "why"), "FR-4: the WARN is printed once")
        assertTrue(known.record("k", "/a", 10, 200, "why"))
        clock += KnownFailures.RETRY_AFTER_SEC
        assertTrue(known.record("k", "/a", 10, 200, "why"))
    }

    @Test
    fun `a success forgets the record`() {
        known.record("k", "/a", 10, 100, "why")
        known.forget("k", "/a")
        assertFalse(known.skip("k", "/a", 10, 100))
    }

    @Test
    fun `a pass counts what it skipped and names the earliest retry`() {
        known.record("k", "/a", 1, 1, "why"); clock += 60; known.record("k", "/b", 1, 1, "why")
        val pass = known.pass("k", "things")
        known.skip("k", "/a", 1, 1, pass); known.skip("k", "/b", 1, 1, pass); known.skip("k", "/c", 1, 1, pass)
        assertEquals(2, pass.count)
    }

    // ── cover art ──

    private fun musicService(fetched: MutableList<String>, answer: ArtworkFetch): Pair<MusicMediaService, MusicStore> {
        val store = MusicStore(db)
        val folder = "$dir/album"
        SystemFileSystem.createDirectories(Path(folder))
        val album = MusicAlbum(id = "alb", libraryId = "l", title = "Album", path = folder, matchState = MusicMatch.MATCHED, releaseGroupMbid = "rg-1")
        runBlocking { store.replaceLibrary(MusicLibraryRows("l", emptyList(), listOf(album), emptyList())) }
        val svc = MusicMediaService(store, ArtworkDownloader(TmdbClient(config), Screengrabber()), config, JellyfinClient(), FanartTvClient { "" }, null, known)
        svc.fetchCover = { url, _ -> fetched += url; answer }
        svc.noFrontOnCaa = { true }
        return svc to store
    }

    @Test
    fun `a cover the archive answered 404 is not asked for again by the scheduled pass and is by an operator`() = runBlocking {
        val asked = mutableListOf<String>()
        val (svc, _) = musicService(asked, ArtworkFetch.NOT_FOUND)
        svc.fetchArtwork(scopeAll = false, scheduled = true)
        svc.fetchArtwork(scopeAll = false, scheduled = true)
        assertEquals(1, asked.size, "the second scheduled pass skips it")
        svc.fetchArtwork(scopeAll = true, albumIds = setOf("alb"))
        assertEquals(2, asked.size, "the Library's Fetch covers is an operator action")
        clock += KnownFailures.RETRY_AFTER_SEC
        svc.fetchArtwork(scopeAll = false, scheduled = true)
        assertEquals(3, asked.size, "retried after 7 days")
    }

    @Test
    fun `a failure that may pass - not a 404 - is asked again at once`() = runBlocking {
        val asked = mutableListOf<String>()
        val (svc, _) = musicService(asked, ArtworkFetch.FAILED)
        svc.fetchArtwork(scopeAll = false, scheduled = true)
        svc.fetchArtwork(scopeAll = false, scheduled = true)
        assertEquals(2, asked.size)
    }

    // ── write_tags ──

    private fun writer(sent: MutableList<List<String>>, error: (String) -> String?): MusicTagWriter {
        val w = MusicTagWriter(MusicStore(db), config, null, JellyfinClient(), null, null, known)
        w.sendBatch = { _, plan ->
            sent += plan.files.map { it.path }
            plan.files.joinToString("\n") { f ->
                val e = error(f.path)
                if (e == null) """{"path":"${f.path}","written":true}""" else """{"path":"${f.path}","error":"$e"}"""
            }
        }
        return w
    }

    @Test
    fun `an m4a and an mp3 in the plan send only the mp3 and the m4a is unsupported not failed`() = runBlocking {
        val sent = mutableListOf<List<String>>()
        val w = writer(sent) { null }
        val m4a = file("a.m4a"); val mp4 = file("b.MP4"); val mp3 = file("c.mp3")
        val outcome = w.run(listOf(m4a, mp4, mp3).map { MusicTagWriter.PlanFile(it, emptyMap()) }, config.current, {}, { false })
        assertEquals(listOf(listOf(mp3)), sent)
        assertEquals(2, outcome.unsupported)
        assertEquals(1, outcome.written)
        assertTrue(outcome.failed.isEmpty())
    }

    @Test
    fun `a tag that did not read back is remembered and the scheduled pass leaves the file until it changes`() = runBlocking {
        val sent = mutableListOf<List<String>>()
        val w = writer(sent) { "left as it was: title did not read back" }
        val mp3 = file("c.mp3")
        val plan = listOf(MusicTagWriter.PlanFile(mp3, emptyMap()))
        val first = w.run(plan, config.current, {}, { false }, scheduled = true)
        assertEquals(1, first.failed.size)
        val second = w.run(plan, config.current, {}, { false }, scheduled = true)
        assertEquals(1, sent.size, "the second scheduled pass sends nothing")
        assertEquals(1, second.skipped)
        assertTrue(second.failed.isEmpty())
        // An operator's write asks again and reports the failure.
        val operator = w.run(plan, config.current, {}, { false })
        assertEquals(2, sent.size)
        assertEquals(1, operator.failed.size)
        // The file changes: the scheduled pass tries it.
        FileIo.writeText(Path(mp3), "content, longer now")
        w.run(plan, config.current, {}, { false }, scheduled = true)
        assertEquals(3, sent.size)
    }

    @Test
    fun `an answer that never came is not a failure that will repeat`() = runBlocking {
        val sent = mutableListOf<List<String>>()
        val w = MusicTagWriter(MusicStore(db), config, null, JellyfinClient(), null, null, known)
        w.sendBatch = { _, plan -> sent += plan.files.map { it.path }; null }
        val mp3 = file("c.mp3")
        val plan = listOf(MusicTagWriter.PlanFile(mp3, emptyMap()))
        w.run(plan, config.current, {}, { false }, scheduled = true)
        w.run(plan, config.current, {}, { false }, scheduled = true)
        assertEquals(2, sent.size)
    }

    // ── per-file checks ──

    private fun film(id: String, path: String) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = path, tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, runtime = 90, tracks = emptyList(),
    )

    private fun queue(subtitles: SubtitleCheckService? = null): Pair<MediaJobQueue, TrackCoverageService> {
        val store = MediaStore(db, JsTagStore("$dir/tags.json"), config)
        val integrity = FileIntegrityService(db, SeedingSnapshot(config, QBittorrentClient()))
        val coverage = TrackCoverageService(db, integrity)
        val q = MediaJobQueue(
            db, store, WsBroadcaster(), JellyfinClient(), config, MediaHistory(db), SeedingGuard(SeedingSnapshot(config, QBittorrentClient())),
            null, CoroutineScope(Dispatchers.Default), fileIntegrity = integrity, trackCoverage = coverage, subtitleChecks = subtitles, knownFailures = known,
        )
        return q to coverage
    }

    private fun queued() = db.mediaJobQueries.listQueuedByLane("segments").executeAsList()

    @Test
    fun `a file the check could not read is queued once then left alone by the scheduled pass until it changes`() = runBlocking {
        val (q, _) = queue()
        val path = file("episode.mkv")
        val item = film("ep", path)
        val step = PipelineStep(step = FileCheckSteps.LENGTHS)
        assertEquals(1, q.enqueueDueFileChecks(step, listOf(item)).queued)
        // The job ran and failed (what runFileCheck records), and its row left the queue.
        val stamp = FileIntegrityService.stampOf(path)!!
        known.record(FileCheckSchedule.LENGTHS_JOB, path, stamp.size, stamp.mtime, "ffprobe could not read the file")
        queued().forEach { db.mediaJobQueries.markRunning(1L, it.id); db.mediaJobQueries.markFinished("failed", 2L, "ffprobe could not read the file", 0, it.id) }
        assertEquals(0, q.enqueueDueFileChecks(step, listOf(item)).queued, "known failure, unchanged")
        assertEquals(0, queued().size)
        FileIo.writeText(Path(path), "the file was replaced with something longer")
        assertEquals(1, q.enqueueDueFileChecks(step, listOf(item)).queued, "a changed file is a new input")
    }

    @Test
    fun `a subtitle check for a video that is gone enqueues nothing and drops the stored rows`() = runBlocking {
        val subtitles = SubtitleCheckService(db, MediaStore(db, JsTagStore("$dir/tags.json"), config), config, SubtitleReferences(db, JellyfinClient(), config), MediaHistory(db))
        val (q, _) = queue(subtitles)
        val gone = "$dir/gone-film/film.mkv"
        db.subtitleCheckQueries.putCheck(
            "$dir/gone-film/film.en.srt", gone, "gone", "en", 0, 0, 11, 12, 13, 14, "in_sync", null, null, null, null, null, null, null, null,
            null, null, 0, null, "h", null, null, "", 1L, 0,
        )
        assertTrue(subtitles.isDue(gone), "before: the stale rows made it due, which is the loop")
        val pass = q.enqueueDueFileChecks(PipelineStep(step = FileCheckSteps.SUBTITLES), listOf(film("gone", gone)))
        assertEquals(0, pass.queued)
        assertEquals(0, queued().size)
        assertTrue(db.subtitleCheckQueries.checksForVideo(gone).executeAsList().isEmpty(), "the rows that named the path are reconciled")
        assertFalse(subtitles.isDue(gone))
    }
}
