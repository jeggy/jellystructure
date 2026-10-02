package dev.jellystructure.bazarr

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.BazarrConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.SubtitleCheckConfig
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.media.FileIntegrityService
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.subtitles.Cue
import dev.jellystructure.subtitles.CueCodec
import dev.jellystructure.subtitles.FitGroup
import dev.jellystructure.subtitles.SubtitleCheckService
import dev.jellystructure.subtitles.SubtitleReferences
import dev.jellystructure.subtitles.SubtitleVerdicts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 273 (§C) — the steering loop against a stand-in Bazarr that records what it is asked to do. Synthetic cue
 * tracks only; the "video" files are placeholders the check never reads.
 */
class BazarrSteeringTest {
    private val dir = "/tmp/jellystructure-test-steer-${getpid()}"
    private lateinit var db: JellystructureDb
    private lateinit var config: ConfigStore
    private lateinit var checks: SubtitleCheckService
    private lateinit var steering: BazarrSteering
    private lateinit var fake: FakeBazarr
    private lateinit var scope: CoroutineScope

    class FakeBazarr : BazarrOps {
        val calls = ArrayList<String>()
        var movies = listOf<BazarrMovie>()
        var series = listOf<BazarrSeries>()
        var episodes = listOf<BazarrEpisode>()
        var history = listOf<BazarrHistoryRow>()
        val uploads = ArrayList<Pair<String, ByteArray>>()
        override suspend fun allMovies(url: String, apiKey: String) = movies
        override suspend fun allSeries(url: String, apiKey: String) = series
        override suspend fun episodesFor(url: String, apiKey: String, sonarrSeriesId: Int) = episodes.filter { it.sonarrSeriesId == sonarrSeriesId }
        override suspend fun episodeById(url: String, apiKey: String, sonarrEpisodeId: Int) = episodes.firstOrNull { it.sonarrEpisodeId == sonarrEpisodeId }
        override suspend fun movieById(url: String, apiKey: String, radarrId: Int) = movies.firstOrNull { it.radarrId == radarrId }
        override suspend fun movieHistoryRows(url: String, apiKey: String, radarrId: Int?, start: Int, length: Int) = history.filter { it.radarrId == radarrId }
        override suspend fun episodeHistoryRows(url: String, apiKey: String, episodeId: Int?, start: Int, length: Int) = history.filter { it.sonarrEpisodeId == episodeId }
        override suspend fun syncSubtitle(url: String, apiKey: String, type: String, id: Int, language: String, path: String, reference: String?, maxOffsetSeconds: Int?, noFixFramerate: Boolean?, gss: Boolean?, forced: Boolean, hi: Boolean): Boolean {
            calls += "sync $type $id $language ${path.substringAfterLast('/')} ref=$reference max=$maxOffsetSeconds nofix=$noFixFramerate"; return true
        }
        override suspend fun blacklistEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, provider: String, subsId: String, language: String, subtitlesPath: String): Boolean {
            calls += "blacklist episode $episodeId $provider $subsId ${subtitlesPath.substringAfterLast('/')}"; return true
        }
        override suspend fun blacklistMovieSubtitle(url: String, apiKey: String, radarrId: Int, provider: String, subsId: String, language: String, subtitlesPath: String): Boolean {
            calls += "blacklist movie $radarrId $provider $subsId ${subtitlesPath.substringAfterLast('/')}"; return true
        }
        override suspend fun uploadEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, language: String, forced: Boolean, hi: Boolean, fileName: String, content: ByteArray): Boolean {
            calls += "upload episode $episodeId $language"; uploads += fileName to content; return true
        }
        override suspend fun uploadMovieSubtitle(url: String, apiKey: String, radarrId: Int, language: String, forced: Boolean, hi: Boolean, fileName: String, content: ByteArray): Boolean {
            calls += "upload movie $radarrId $language"; uploads += fileName to content; return true
        }
        override suspend fun deleteMovieSubtitle(url: String, apiKey: String, radarrId: Int, language: String, forced: Boolean, hi: Boolean, path: String): Boolean {
            calls += "delete movie $radarrId ${path.substringAfterLast('/')}"; return true
        }
        override suspend fun deleteEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, language: String, forced: Boolean, hi: Boolean, path: String): Boolean {
            calls += "delete episode $episodeId ${path.substringAfterLast('/')}"; return true
        }
        override suspend fun searchProvidersEpisode(url: String, apiKey: String, episodeId: Int) = emptyList<BazarrProviderResult>()
        override suspend fun downloadProviderEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, hi: Boolean, forced: Boolean, provider: String, subtitle: String) = false
    }

    private fun episodeCues(seed: Int): List<Cue> {
        val r = Random(seed)
        val out = ArrayList<Cue>()
        var t = 8_000L
        while (t < 21 * 60_000L) {
            val len = r.nextLong(1_000, 5_000)
            out += Cue(t, t + len)
            t += len + if (r.nextInt(12) == 0) r.nextLong(20_000, 50_000) else r.nextLong(200, 6_000)
        }
        return out
    }

    private fun srt(cues: List<Cue>): String = cues.mapIndexed { i, c ->
        fun two(v: Long) = v.toString().padStart(2, '0')
        fun clock(ms: Long) = "${two(ms / 3_600_000)}:${two(ms / 60_000 % 60)}:${two(ms / 1000 % 60)},${(ms % 1000).toString().padStart(3, '0')}"
        "${i + 1}\n${clock(c.startMs)} --> ${clock(c.endMs)}\nline\n"
    }.joinToString("\n")

    private fun write(path: String, text: String) = runBlocking { FileIo.writeText(Path(path), text) }

    private fun embed(video: String, cues: List<Cue>) {
        val st = FileIntegrityService.stampOf(video)!!
        db.subtitleCheckQueries.putReference(video, SubtitleReferences.EMBEDDED, st.size, st.mtime, "s:0", 10, CueCodec.encode(cues), 0)
    }

    private val videoTrack = Track(0, "0:v:0", TrackKind.VIDEO, "h264", null, null, true, false, durationMs = 22 * 60_000L)

    @BeforeTest
    fun setUp() = runBlocking {
        SystemFileSystem.createDirectories(Path("$dir/Film (2020)"))
        SystemFileSystem.createDirectories(Path("$dir/Show/Season 1"))
        db = createDatabase("$dir/test.db")
        config = ConfigStore("$dir/config.toml").also { it.load() }
        config.update(config.current.copy(bazarr = BazarrConfig(enabled = true, url = "http://bazarr.test", apiKey = "k"), subtitleCheck = SubtitleCheckConfig(action = "fix")))
        val store = MediaStore(db, JsTagStore("$dir/tags.json"), config)
        checks = SubtitleCheckService(db, store, config, SubtitleReferences(db, JellyfinClient(), config), MediaHistory(db))
        SubtitleVerdicts.reportOnly = { config.current.subtitleCheck.reportOnly }
        checks.init()
        fake = FakeBazarr()
        scope = CoroutineScope(SupervisorJob())
        steering = BazarrSteering(db, store, config, fake, BazarrService(config, fake), JellyfinClient(), MediaHistory(db), scope)
        steering.checks = checks
        checks.steering = steering
        store.addOrUpdate(film())
        store.addOrUpdate(show())
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        platform.posix.system("rm -rf '$dir'")
    }

    private val filmVideo get() = "$dir/Film (2020)/Film.mkv"
    private fun film() = MediaItem(
        id = "film", title = "Film", year = 2020, kind = MediaKind.MOVIE, path = filmVideo, tmdbId = null, imdbId = "tt0000001",
        originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = listOf(videoTrack),
    )
    private fun ep(n: Int) = "$dir/Show/Season 1/Show.S01E0$n.mkv"
    private fun show() = MediaItem(
        id = "show", title = "Show", year = 2020, kind = MediaKind.TV_SHOW, path = "$dir/Show", tmdbId = null, tvdbId = 42,
        originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList(),
        episodes = (1..2).map { n -> Episode(filename = "Show.S01E0$n.mkv", path = ep(n), seasonNumber = 1, episodeNumber = n, tracks = listOf(videoTrack), issueCount = 0) },
    )

    /** A film with a right Danish, a 50 s late English and a wrong Croatian subtitle; Bazarr placed the Croatian one. */
    private fun filmWithThree() {
        write(filmVideo, "placeholder")
        val ref = episodeCues(1)
        write("$dir/Film (2020)/Film.da.srt", srt(ref))
        write("$dir/Film (2020)/Film.en.srt", srt(ref.map { Cue(it.startMs + 50_000, it.endMs + 50_000) }))
        write("$dir/Film (2020)/Film.hr.srt", srt(episodeCues(2)))
        embed(filmVideo, ref)
        fake.movies = listOf(BazarrMovie(title = "Film", path = "/media/movies/Film (2020)/Film.mkv", imdbId = "tt0000001", radarrId = 7))
        fake.history = listOf(BazarrHistoryRow(action = 1, provider = "opensubtitlescom", subsId = "123", subtitlesPath = "/media/movies/Film (2020)/Film.hr.srt", radarrId = 7))
    }

    @Test
    fun `fix syncs the late one and blacklists the wrong one only once`() = runBlocking {
        filmWithThree()
        checks.checkVideo(film(), filmVideo, SubtitleCheckService.Mode.INLINE)
        steering.act("film", filmVideo)
        assertTrue(fake.calls.any { it == "sync movie 7 en Film.en.srt ref=s:0 max=120 nofix=false" }, "${fake.calls}")
        assertTrue(fake.calls.any { it == "blacklist movie 7 opensubtitlescom 123 Film.hr.srt" }, "${fake.calls}")
        assertTrue(fake.calls.none { it.contains("Film.da.srt") }, "${fake.calls}")
        assertTrue(db.subtitleCheckQueries.settledByPath("$dir/Film (2020)/Film.da.srt").executeAsOneOrNull() != null, "the right one is kept")
        val before = fake.calls.size
        steering.act("film", filmVideo)
        assertEquals(before, fake.calls.size, "nothing is done twice for the same file: ${fake.calls}")
    }

    @Test
    fun `ask proposes and an approval does it`() = runBlocking {
        config.update(config.current.copy(subtitleCheck = config.current.subtitleCheck.copy(action = "ask")))
        filmWithThree()
        checks.checkVideo(film(), filmVideo, SubtitleCheckService.Mode.INLINE)
        steering.act("film", filmVideo)
        assertEquals(emptyList(), fake.calls)
        val waiting = steering.waiting()
        assertEquals(setOf("sync", "blacklist"), waiting.map { it.action }.toSet())
        assertTrue(steering.approve(waiting.first { it.action == "blacklist" }.id))
        assertTrue(fake.calls.any { it.startsWith("blacklist movie 7") }, "${fake.calls}")
        assertTrue(steering.dismiss(waiting.first { it.action == "sync" }.id))
        assertEquals(emptyList(), steering.waiting())
    }

    @Test
    fun `report does nothing and hides nothing`() = runBlocking {
        config.update(config.current.copy(subtitleCheck = config.current.subtitleCheck.copy(action = "report")))
        filmWithThree()
        checks.checkVideo(film(), filmVideo, SubtitleCheckService.Mode.INLINE)
        steering.act("film", filmVideo)
        assertEquals(emptyList(), fake.calls)
        assertTrue(SubtitleVerdicts.isOffered("Film.hr.srt"))
        val would = BazarrSteering.preview(db.subtitleCheckQueries.allChecks().executeAsList())
        assertEquals(BazarrSteering.FixPreview(sync = 1, replace = 1, move = 0, ask = 0, hide = 2), would, "the report run says what Fix it would do: the 50 s late one is hidden too until synced")
    }

    @Test
    fun `with no budget left nothing is blacklisted`() = runBlocking {
        config.update(config.current.copy(subtitleCheck = config.current.subtitleCheck.copy(dailyDownloadBudget = 0)))
        filmWithThree()
        checks.checkVideo(film(), filmVideo, SubtitleCheckService.Mode.INLINE)
        steering.act("film", filmVideo)
        assertTrue(fake.calls.none { it.startsWith("blacklist") }, "${fake.calls}")
        assertTrue(fake.calls.any { it.startsWith("sync") }, "a sync costs no download: ${fake.calls}")
    }

    @Test
    fun `a blind upgrade over a settled subtitle is undone with the kept copy`() = runBlocking {
        write(filmVideo, "placeholder")
        val ref = episodeCues(3)
        val good = srt(ref)
        write("$dir/Film (2020)/Film.da.srt", good)
        embed(filmVideo, ref)
        fake.movies = listOf(BazarrMovie(title = "Film", path = "/media/movies/Film (2020)/Film.mkv", imdbId = "tt0000001", radarrId = 7))
        checks.checkVideo(film(), filmVideo, SubtitleCheckService.Mode.INLINE)
        steering.act("film", filmVideo)
        assertTrue(fake.calls.isEmpty())
        // Bazarr's upgrade writes another episode's subtitle over the same file name.
        write("$dir/Film (2020)/Film.da.srt", srt(episodeCues(4)) + "\n")
        checks.checkVideo(film(), filmVideo, SubtitleCheckService.Mode.INLINE)
        steering.act("film", filmVideo)
        assertTrue(fake.calls.any { it == "upload movie 7 da" }, "${fake.calls}")
        assertEquals(good, fake.uploads.single().second.decodeToString())
        assertTrue(fake.calls.none { it.startsWith("blacklist") || it.startsWith("delete") }, "a restore is not followed by a removal: ${fake.calls}")
    }

    @Test
    fun `a subtitle that belongs to the next episode is given to it`() = runBlocking {
        write(ep(1), "one"); write(ep(2), "two")
        val c1 = episodeCues(11); val c2 = episodeCues(12)
        embed(ep(1), c1); embed(ep(2), c2)
        write("$dir/Show/Season 1/Show.S01E01.da.srt", srt(c2))
        fake.series = listOf(BazarrSeries(title = "Show", path = "/media/series/Show", tvdbId = 42, sonarrSeriesId = 5))
        fake.episodes = listOf(
            BazarrEpisode(title = "One", path = "/media/series/Show/Season 1/Show.S01E01.mkv", season = 1, episode = 1, sonarrSeriesId = 5, sonarrEpisodeId = 101),
            BazarrEpisode(title = "Two", path = "/media/series/Show/Season 1/Show.S01E02.mkv", season = 1, episode = 2, sonarrSeriesId = 5, sonarrEpisodeId = 102),
        )
        val out = checks.checkVideo(show(), ep(1), SubtitleCheckService.Mode.INLINE)
        assertEquals("other_episode", out.rows.single().verdict)
        assertEquals("S01E02", out.rows.single().match_label)
        steering.act("show", ep(1))
        assertTrue(fake.calls.any { it == "upload episode 102 da" }, "${fake.calls}")
        assertTrue(fake.calls.any { it == "delete episode 101 Show.S01E01.da.srt" }, "not from Bazarr, so deleted rather than blacklisted: ${fake.calls}")
    }

    // ── Phase 302 ─────────────────────────────────────────────────────────────────────────────

    private fun rows() = db.subtitleCheckQueries.allChecks().executeAsList()

    @Test
    fun `each verdict is one cause and severity is what a viewer meets`() = runBlocking {
        filmWithThree()
        write("$dir/Film (2020)/Film.sv.srt", srt(episodeCues(1).map { Cue(it.startMs + 1_200, it.endMs + 1_200) }))
        config.update(config.current.copy(subtitleCheck = config.current.subtitleCheck.copy(action = "report")))
        checks.checkVideo(film(), filmVideo, SubtitleCheckService.Mode.INLINE)
        val by = rows().associate { it.language to FitGroup.of(it) }
        assertEquals(mapOf<String?, FitGroup?>("da" to null, "en" to FitGroup.SHIFT, "hr" to FitGroup.WRONG, "sv" to FitGroup.SLIGHT), by)
        val shift = rows().filter { FitGroup.of(it) == FitGroup.SHIFT }
        assertEquals("critical", FitGroup.severity(FitGroup.SHIFT, shift, reportOnly = true), "on Only report every one is offered")
        assertEquals("warning", FitGroup.severity(FitGroup.SHIFT, shift, reportOnly = false), "hidden once the switch says Fix it")
        assertEquals("info", FitGroup.severity(FitGroup.SLIGHT, rows().filter { FitGroup.of(it) == FitGroup.SLIGHT }, reportOnly = true))
    }

    private suspend fun waitFor(group: FitGroup) { repeat(200) { if (steering.run(group.id) == null) return; delay(20) } }

    @Test
    fun `a press fixes the group through Bazarr whatever the mode`() = runBlocking {
        config.update(config.current.copy(subtitleCheck = config.current.subtitleCheck.copy(action = "report")))
        filmWithThree()
        checks.checkVideo(film(), filmVideo, SubtitleCheckService.Mode.INLINE)
        steering.act("film", filmVideo)
        assertEquals(emptyList(), fake.calls, "Only report does nothing by itself")
        val run = steering.fixGroup(FitGroup.SHIFT)
        assertEquals(1, run?.total)
        waitFor(FitGroup.SHIFT)
        assertEquals(listOf("sync movie 7 en Film.en.srt ref=s:0 max=120 nofix=false"), fake.calls)
        // The wrong one is a group of its own, replaced through Bazarr's blacklist.
        steering.fixGroup(FitGroup.WRONG); waitFor(FitGroup.WRONG)
        assertTrue(fake.calls.contains("blacklist movie 7 opensubtitlescom 123 Film.hr.srt"), "${fake.calls}")
    }

    @Test
    fun `a synced subtitle that is still off is replaced and never synced twice`() = runBlocking {
        config.update(config.current.copy(subtitleCheck = config.current.subtitleCheck.copy(action = "ask")))
        filmWithThree()
        checks.checkVideo(film(), filmVideo, SubtitleCheckService.Mode.INLINE)
        steering.act("film", filmVideo)
        assertTrue(steering.waiting().any { it.action == "sync" }, "Ask me first proposes the sync")
        val late = rows().single { it.language == "en" }
        assertTrue(steering.fixRow(late), "a press is not held back by the waiting proposal")
        assertTrue(steering.waiting().none { it.sidecar_path == late.sidecar_path }, "the proposal is answered")
        // The check after the sync has not run: nothing yet.
        assertTrue(!steering.fixRow(late))
        // It ran, and the file is still off: replaced (deleted through Bazarr: it did not come from Bazarr).
        assertTrue(steering.fixRow(late.copy(checked_at = late.checked_at + 100)))
        assertEquals(1, fake.calls.count { it.startsWith("sync") }, "${fake.calls}")
        assertTrue(fake.calls.contains("delete movie 7 Film.en.srt"), "${fake.calls}")
        assertNull(steering.run(FitGroup.SHIFT.id))
    }
}
