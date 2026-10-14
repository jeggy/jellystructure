package dev.jellystructure.filefix

import dev.jellystructure.arr.ArrParseResult
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.WorkFiles
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.torrent.SeedingCheckResult
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 314 — the dry run, the queue rules and the jobs against a fake shell (FR-314-8's tests 4, 7, 8 and the kind C
 * Radarr rule): seeded ⇒ a sidecar and never a write into the video; unreachable ⇒ waits; a file seeded during the job
 * is left alone; playback stops a job before it changes anything; one job at a time within the read cap; switching a
 * kind off stops its waiting jobs; a Radarr reading that could replace the original stops kind C.
 */
class FileFixServiceTest {
    private val dir = "/tmp/jellystructure-test-filefix-${getpid()}"
    private lateinit var db: JellystructureDb
    private lateinit var store: MediaStore
    private lateinit var history: MediaHistory
    private lateinit var shell: FakeShell
    private var seedingAnswer: () -> SeedingCheckResult = { SeedingCheckResult.Allowed }
    private var playing = false
    private var radarrCopy: ArrParseResult? = ArrParseResult("Unknown", 0)
    private var clock = 2_000_000_000L

    private val film = "$dir/Film (2020)/Film (2020).mkv"
    private val dvFilm = "$dir/Other (2021)/Other (2021) - Remux-2160p.mkv"

    private fun d(default: Int = 0) = """{"default": $default, "comment": 0, "forced": 0, "visual_impaired": 0, "attached_pic": 0}"""
    private val ac3 = """{"streams": [
        {"index": 0, "codec_name": "h264", "codec_type": "video", "disposition": ${d(1)}, "tags": {"language": "eng"}},
        {"index": 1, "codec_name": "ac3", "codec_type": "audio", "channels": 6, "disposition": ${d(1)}, "tags": {"language": "dan"}}],
        "format": {"format_name": "matroska,webm", "duration": "100.000000"}}"""
    private val ac3PlusCopy = """{"streams": [
        {"index": 0, "codec_name": "h264", "codec_type": "video", "disposition": ${d(1)}, "tags": {"language": "eng"}},
        {"index": 1, "codec_name": "ac3", "codec_type": "audio", "channels": 6, "disposition": ${d(1)}, "tags": {"language": "dan"}},
        {"index": 2, "codec_name": "aac", "codec_type": "audio", "channels": 2, "disposition": ${d(0)}, "tags": {"language": "dan", "title": "Stereo"}}],
        "format": {"format_name": "matroska,webm", "duration": "100.000000"}}"""
    private val dv7 = """{"streams": [
        {"index": 0, "codec_name": "hevc", "codec_type": "video", "disposition": ${d(1)}, "tags": {"language": "eng"},
         "side_data_list": [{"side_data_type": "DOVI configuration record", "dv_profile": 7, "el_present_flag": 1, "dv_bl_signal_compatibility_id": 6}]},
        {"index": 1, "codec_name": "eac3", "codec_type": "audio", "channels": 6, "disposition": ${d(1)}, "tags": {"language": "eng"}}],
        "format": {"format_name": "matroska,webm", "duration": "100.000000"}}"""
    private val dv81 = dv7.replace("\"dv_profile\": 7, \"el_present_flag\": 1, \"dv_bl_signal_compatibility_id\": 6", "\"dv_profile\": 8, \"el_present_flag\": 0, \"dv_bl_signal_compatibility_id\": 1")

    /** A file system of names and a scripted ffmpeg/mkvmerge: what ran, and what each probe says. */
    inner class FakeShell : FileFixShell {
        val files = HashMap<String, Long>()      // path → size
        val probes = HashMap<String, String>()   // path → ffprobe JSON
        val ran = ArrayList<String>()
        var stopDuring: String? = null           // a command fragment whose run is stopped (playback)
        var identifyJson: String? = null         // phase 314c — what `mkvmerge -J` says (Remove reads our tags from it)
        override suspend fun run(cmd: String): ShellResult = exec(cmd)
        override suspend fun runStoppable(cmd: String, marker: String, shouldStop: () -> Boolean): ShellResult? {
            if (stopDuring != null && cmd.contains(stopDuring!!)) { ran += cmd; return null }
            return exec(cmd)
        }
        private fun arg(cmd: String, after: String): String = cmd.substringAfter(after).substringAfter("'").substringBefore("'")
        private fun exec(cmd: String): ShellResult {
            ran += cmd
            return when {
                cmd.startsWith("ffprobe -v error -print_format json") -> probes[arg(cmd, "-show_format ")]?.let { ShellResult(0, it) } ?: ShellResult(1, "no such file")
                cmd.startsWith("mkvmerge -J") -> ShellResult(0, identifyJson ?: """{"tracks": [{"type": "video", "properties": {"uid": 1}}, {"type": "audio", "properties": {"uid": 99}}]}""")
                cmd.contains(" mkvmerge -q -o ") && cmd.contains("--audio-tracks") -> {   // phase 314c — Remove's remux
                    val out = arg(cmd, "mkvmerge -q -o ")
                    files[out] = 1_500; probes[out] = ac3
                    ShellResult(0, "")
                }
                cmd.contains("ffmpeg -nostdin -y -v error -i") && cmd.contains(" -f matroska ") -> { files[cmd.substringAfterLast(" -f matroska ").trim('\'')] = 1_000; ShellResult(0, "") }
                cmd.contains("-f null -") -> ShellResult(0, "")
                cmd.contains(" mkvmerge -q -o ") -> {
                    val out = arg(cmd, "mkvmerge -q -o ")
                    files[out] = 2_000
                    probes[out] = if (cmd.contains("--global-tags")) dv81 else ac3PlusCopy
                    ShellResult(0, "")
                }
                cmd.contains(" convert --discard ") -> { files[arg(cmd, " -o ")] = 1_000; ShellResult(0, "") }
                cmd.startsWith("mkvextract") -> ShellResult(0, "")
                cmd.contains("-count_packets") -> ShellResult(0, "2400")
                cmd.contains("mv -f ") -> {
                    val src = arg(cmd, "mv -f "); val dst = cmd.substringAfterLast("' '").trimEnd('\'')
                    files[dst] = files.remove(src) ?: 0; probes[src]?.let { probes[dst] = it }
                    ShellResult(0, "")
                }
                cmd.startsWith("df ") -> ShellResult(0, "999999999999999")
                cmd.startsWith("mkdir -p") -> ShellResult(0, "")
                else -> ShellResult(0, "")
            }
        }
        override fun writeText(path: String, text: String): Boolean { files[path] = text.length.toLong(); return true }
        override fun exists(path: String): Boolean = path in files || path == "/usr/local/bin/dovi_tool"
        override fun remove(path: String) { files.remove(path) }
        override fun stat(path: String): Pair<Long, Long>? = files[path]?.let { it to 1L }
        override fun listDir(dir: String): List<String> = files.keys.filter { it.substringBeforeLast('/') == dir }
    }

    private var renamer: (suspend (String) -> dev.jellystructure.model.FileFixRenameResult)? = null

    /**
     * FR-314-15 — the config the service reads. Only `[file_fix]` matters here: blank `dv_versions_root` is the
     * beside-the-original placement every test below the FR-314-15 ones expects.
     */
    private var appConfig = dev.jellystructure.config.AppConfig()

    /** FR-314-15 — (film id, version path) per grouping the service asked for, and what the fake answered. */
    private val grouped = ArrayList<Pair<String, String>>()
    private var groupAnswer: () -> VersionGrouping = { VersionGrouping(true, "grouped with the film as a version") }

    /**
     * FR-314-21 (DEB-1009) — was each grouping call the patient one (after a write, FR-314-15's own 8 × 15 s) or the
     * retry's impatient one? A retry that waited minutes per film would outlast its own interval.
     */
    private val groupPatience = ArrayList<Boolean>()

    /**
     * FR-314-21 — what Jellyfin answers for the films the retry asks about: source paths in its own view, keyed by
     * Jellyfin item id. A film **absent** from this map is one Jellyfin does not know, which is a different fact from
     * a film with no sources, so the fake only answers for the ids it has.
     */
    private var jellyfinSources: Map<String, Set<String>> = emptyMap()
    private var sourceReads = 0

    /** FR-314-15 — the version paths the service reported as gone. */
    private val ungrouped = ArrayList<String>()

    /** FR-314-15 — what Radarr/Sonarr have as root folders, for the overlap refusal. */
    private var arrRoots: List<String> = listOf("/films")

    private fun service() = FileFixService(
        db = db, store = store, config = { appConfig }, history = history,
        seeding = { seedingAnswer() },
        radarrParse = { title -> if (title.endsWith(" - Dolby Vision")) radarrCopy else ArrParseResult("Remux-2160p", 0) },
        afterWrite = { item, nudgeArr -> afterWrites += item.id to nudgeArr }, reassertAssets = { item -> reasserted += item.id; emptyList() },
        playbackActive = { playing }, shell = shell, now = { clock }, today = { "2026-10-08" },
        renameOriginal = renamer, radarrLink = radarrLink,
        groupVersion = { item, versionPath, patient -> grouped += item.id to versionPath; groupPatience += patient; groupAnswer() },
        groupedSourcePaths = { films ->
            sourceReads++
            films.mapNotNull { it.jellyfinId }.filter { it in jellyfinSources }.associateWith { jellyfinSources.getValue(it) }
        },
        ungroupVersion = { versionPath -> ungrouped += versionPath },
        arrRootFolders = { arrRoots },
        assertViewerVisibility = { visibilityPasses++ },
        restrictedViewers = { restrictedViewers },
    )

    /** FR-314-19 — what the FR-314-15 pass last found: the accounts that cannot see the versions library. */
    private var restrictedViewers: List<String> = emptyList()

    /** FR-314-22 (DEB-1033) — how many times the service invited the viewer visibility pass. */
    private var visibilityPasses = 0

    /**
     * FR-314-15 — the versions root this suite writes into, as the owner would configure it.
     *
     * FR-314-16 (DEB-898) — with the library mapping that carries Jellyfin's name for the same folder. A root no
     * mapping covers refuses the job before anything is written, so the owner's two admin steps are one call here
     * exactly as they are two steps on the host. [jellyfinPath] is Jellyfin's side of that mapping.
     */
    private fun withVersionsRoot(root: String = "$dir/versions", jellyfinPath: String = "/media/versions") {
        appConfig = appConfig.copy(
            fileFix = dev.jellystructure.config.FileFixConfig(dvVersionsRoot = root),
            libraries = listOf(dev.jellystructure.config.LibraryMapping(
                jellyfinId = "versions-lib", name = "library-versions", collectionType = "movies",
                jellyfinPath = jellyfinPath, localPath = root, skip = true)),
        )
    }

    /** Phase 314 (FR-314-13) — the titles whose images and NFO the write path wrote back itself. */
    private val reasserted = ArrayList<String>()

    /** Phase 314 (FR-314-12) — every post-write sync and whether it asked for a Radarr/Sonarr rescan. */
    private val afterWrites = ArrayList<Pair<String, Boolean>>()

    /** FR-314-12 — a Radarr that has a film, a link to one of its `moviefile` rows, and the rows themselves. */
    inner class FakeRadarrLink : RadarrLink {
        var fileId: Int = 652
        val rows = arrayListOf(dev.jellystructure.arr.RadarrFileRow(652, "/films/Other (2021)/Other (2021) - Remux-2160p.mkv"))
        override suspend fun read(item: dev.jellystructure.model.MediaItem) = RadarrMovieLink(676, fileId)
        override suspend fun files(movieId: Int) = rows.toList()
    }

    private var radarrLink: RadarrLink? = null

    @BeforeTest fun setUp() = runBlocking {
        platform.posix.mkdir(dir, 0x1ffu)
        for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dir/test.db$s") }
        db = createDatabase("$dir/test.db")
        val cfg = ConfigStore("$dir/config.toml").also { it.load() }
        store = MediaStore(db, JsTagStore("$dir/tags.json"), cfg)
        history = MediaHistory(db)
        shell = FakeShell()
        shell.files[film] = 10_000_000; shell.probes[film] = ac3
        shell.files[dvFilm] = 50_000_000; shell.probes[dvFilm] = dv7
        store.addOrUpdate(MediaItem(id = "film", title = "Film", year = 2020, kind = MediaKind.MOVIE, path = film, tmdbId = null,
            originalLanguage = "da", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList()))
        // FR-314-21 — the kind C film carries a Jellyfin id, because the grouping retry asks Jellyfin about the film
        // and a film Jellyfin has no id for is one it cannot be asked about.
        store.addOrUpdate(MediaItem(id = "other", title = "Other", year = 2021, kind = MediaKind.MOVIE, path = dvFilm, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList(),
            jellyfinId = "jf-other"))
    }

    @AfterTest fun tearDown() { for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dir/test.db$s") } }

    private fun row(path: String, kind: FixKind) = db.fileFixQueries.find(path, kind.id).executeAsOneOrNull()

    @Test fun `the dry run lists an unseeded file as would add - a seeded one as a sidecar - an unknown one as waiting`() = runBlocking {
        val s = service()
        s.buildPlan()
        assertEquals("would_add", row(film, FixKind.STEREO)?.state)
        assertEquals("would_add", row(dvFilm, FixKind.DOLBY_VISION)?.state)
        assertNull(row(film, FixKind.SURROUND), "AC-3 5.1 needs no surround copy")
        seedingAnswer = { SeedingCheckResult.Blocked("", hardLink = true) }
        s.buildPlan()
        assertEquals("sidecar", row(film, FixKind.STEREO)?.state)
        seedingAnswer = { SeedingCheckResult.Unreachable("down") }
        s.buildPlan()
        assertEquals("waiting", row(film, FixKind.STEREO)?.state)
        assertFalse(shell.ran.any { it.contains("mkvmerge -q") || it.contains("mv -f") }, "a dry run writes nothing")
    }

    @Test fun `an unseeded file gets the track inside it - after verification - with one rename`() = runBlocking {
        val s = service()
        s.buildPlan()
        s.setSetting(FixKind.STEREO, enabled = true, autoNew = false)
        assertEquals(2, s.apply(FixKind.STEREO), "both films lack a track a Chromecast plays (AC-3 5.1 · E-AC-3 5.1)")
        assertEquals("pending", row(film, FixKind.STEREO)?.state)
        assertIs<FixOutcome.Done>(s.runJob(film, "a", { false }))
        val swap = shell.ran.single { it.contains("mv -f ") }
        assertTrue(swap.contains("/.jellystructure/filefix_Film (2020).mkv' '$film'"), swap)
        assertEquals("done", row(film, FixKind.STEREO)?.state)
        assertTrue(shell.ran.indexOfFirst { it.contains(" mkvmerge -q -o ") } < shell.ran.indexOf(swap))
        assertTrue(shell.files.keys.none { it.contains("/.jellystructure/") }, "no work file left: ${shell.files.keys}")
    }

    @Test fun `a seeded file is never written - its track goes beside it as a mka`() = runBlocking {
        seedingAnswer = { SeedingCheckResult.Blocked("", hardLink = true) }
        val s = service()
        s.buildPlan()
        assertIs<FixOutcome.Done>(s.runJob(film, "a", { false }))
        assertTrue(shell.ran.none { it.contains(" mkvmerge -q -o ") }, "no mux of a seeded file")
        val move = shell.ran.single { it.contains("mv -f ") }
        assertTrue(move.endsWith("'$dir/Film (2020)/Film (2020).dan.Stereo.mka'"), move)
        assertTrue(shell.ran.none { it.contains("mv -f") && it.endsWith("'$film'") })
        // Done once: the next dry run sees the sidecar.
        s.buildPlan()
        assertEquals("done", row(film, FixKind.STEREO)?.state)
    }

    @Test fun `a file that becomes seeded during the job is left alone`() = runBlocking {
        val s = service()
        s.buildPlan()
        var calls = 0
        seedingAnswer = { if (++calls >= 2) SeedingCheckResult.Blocked("", hardLink = true) else SeedingCheckResult.Allowed }
        val r = s.runJob(film, "a", { false })   // call 1 at the start (unseeded), call 2 before the swap (seeded)
        assertIs<FixOutcome.Stopped>(r)
        assertTrue(shell.ran.none { it.contains("mv -f") && it.endsWith("'$film'") }, "the video was not replaced")
        assertEquals("pending", row(film, FixKind.STEREO)?.state)
    }

    @Test fun `playback stops a job before anything changes - and mid-encode`() = runBlocking {
        val s = service()
        s.buildPlan()
        playing = true
        assertIs<FixOutcome.Stopped>(s.runJob(film, "a", { false }))
        assertTrue(shell.ran.none { it.contains("ffmpeg -nostdin -y") })
        assertEquals("pending", row(film, FixKind.STEREO)?.state)
        playing = false
        shell.stopDuring = "-c:a aac"
        assertIs<FixOutcome.Stopped>(s.runJob(film, "a", { false }))
        assertTrue(shell.ran.none { it.contains("mv -f") })
        assertEquals("pending", row(film, FixKind.STEREO)?.state)
    }

    @Test fun `one job at a time - within the read cap - switching a kind off stops its waiting jobs`() = runBlocking {
        val s = service()
        s.buildPlan()
        s.setSetting(FixKind.STEREO, true, false)
        s.apply(FixKind.STEREO)
        assertNotNull(s.nextToQueue(jobActive = false))
        assertNull(s.nextToQueue(jobActive = true))
        db.fileFixQueries.setState("done", "", FileFixService.DEFAULT_NIGHTLY_READ_CAP, clock, dvFilm, FixKind.DOLBY_VISION.id)
        assertNull(s.nextToQueue(jobActive = false), "tonight's read cap is reached")
        clock += 90_000
        assertNotNull(s.nextToQueue(jobActive = false))
        s.setSetting(FixKind.STEREO, false, false)
        assertEquals("would_add", row(film, FixKind.STEREO)?.state)
        assertNull(s.nextToQueue(jobActive = false))
        assertNull(s.apply(FixKind.STEREO), "Apply needs the switch on")
    }

    @Test fun `kind C writes a version beside the original after Radarr's reading - never touching the original`() = runBlocking {
        val s = service()
        s.buildPlan()
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        val move = shell.ran.single { it.contains("mv -f ") }
        assertTrue(move.endsWith("'$dir/Other (2021)/Other (2021) - Dolby Vision.mkv'"), move)
        assertTrue(shell.ran.none { it.contains("mv -f") && it.endsWith("'$dvFilm'") })
        assertEquals("done", row(dvFilm, FixKind.DOLBY_VISION)?.state)
    }

    /** FR-314-13 test 10 — Radarr deletes the extras it adopted when a `moviefile` row changes under it (DEB-869), so
     *  the write path puts the film's images and NFO back itself. Kind C only: A and B change neither. */
    @Test fun `a kind C version written and removed puts the film's images and NFO back - kind A does not`() = runBlocking {
        val s = service()
        s.buildPlan()
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        assertEquals(listOf("other"), reasserted)
        val version = "$dir/Other (2021)/Other (2021) - Dolby Vision.mkv"
        shell.probes[version] = dv81.replace("\"duration\": \"100.000000\"}", "\"duration\": \"100.000000\", \"tags\": {\"JELLYSTRUCTURE_DV\": \"8.1-from-7\"}}")
        s.removeTitle(FixKind.DOLBY_VISION, "other")
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        assertEquals(listOf("other", "other"), reasserted, "after the version is written and after it is removed")
        assertIs<FixOutcome.Done>(s.runJob(film, "a", { false }))
        assertEquals(listOf("other", "other"), reasserted, "kind A leaves the film's own files and Radarr's row alone")
    }

    /**
     * FR-314-12 test 9 (revised 2026-10-13) — Radarr is never asked to rescan a folder holding a kind C version, on
     * the write or on *Remove*, and what Radarr's own scan did to the film's file link is recorded against the title
     * either side of it. Radarr's API cannot put the link back (spec amendment 2026-10-13, second), so what the
     * history says is the whole of the measure. Kinds A and B keep their nudge.
     */
    @Test fun `kind C asks Radarr for no rescan - and what Radarr did to the film's file is recorded either side of Remove`() = runBlocking {
        val link = FakeRadarrLink()
        radarrLink = link
        val s = service()
        s.buildPlan()
        val version = "$dir/Other (2021)/Other (2021) - Dolby Vision.mkv"
        val ourVersionTag = { p: String -> shell.probes[p] = dv81.replace("\"duration\": \"100.000000\"}", "\"duration\": \"100.000000\", \"tags\": {\"JELLYSTRUCTURE_DV\": \"8.1-from-7\"}}") }
        // 1 — the version is written. Jellyfin is told, Radarr is not, and Radarr is still on the original.
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        assertEquals(listOf("other" to false), afterWrites)
        assertTrue(history.forItem("other").none { it.detail.contains("Radarr") }, "the link is the original: nothing to say")
        // 2 — Radarr's own scan (its daily RefreshMovie, or a refresh someone pressed) takes the version for the film.
        link.rows += dev.jellystructure.arr.RadarrFileRow(999, "/films/Other (2021)/Other (2021) - Dolby Vision.mkv")
        link.fileId = 999
        ourVersionTag(version)
        s.removeTitle(FixKind.DOLBY_VISION, "other")
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        assertEquals(listOf("other" to false, "other" to false), afterWrites, "neither the write nor the Remove nudges Radarr")
        assertTrue(history.forItem("other").any { it.detail.contains("took jellystructure's version") && it.detail.contains("row 999") },
            history.forItem("other").joinToString { it.detail })
        // 3 — Radarr catches up on its own: the version it was pointing at is gone, and it leaves the film unlinked
        //     with its own row still naming the original. DEB-868's second half, reported on the next kind C write.
        link.rows.removeAll { it.id == 999 }
        link.fileId = 0
        assertEquals(1, s.applyTitle(FixKind.DOLBY_VISION, "other"))
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        assertTrue(history.forItem("other").any { it.detail.contains("movieFileId 0") && it.detail.contains("row 652") },
            history.forItem("other").joinToString { it.detail })
        assertTrue(history.forItem("other").any { it.detail.contains("Radarr's own UI") }, "it says who can repair it")
        // 4 — kinds A and B are untouched: their file's identity doesn't change, so Radarr still learns of the write.
        assertIs<FixOutcome.Done>(s.runJob(film, "a", { false }))
        assertEquals("film" to true, afterWrites.last())
        assertTrue(history.forItem("film").none { it.detail.contains("Radarr") }, "kind A asks Radarr nothing about its link")
    }

    @Test fun `kind C stops when Radarr could take the copy for an upgrade - or doesn't answer`() = runBlocking {
        val s = service()
        s.buildPlan()
        radarrCopy = ArrParseResult("Bluray-2160p", 0)
        val r = s.runJob(dvFilm, "c", { false })
        assertIs<FixOutcome.Skipped>(r)
        assertTrue(shell.ran.none { it.contains("convert --discard") })
        radarrCopy = null
        assertIs<FixOutcome.Stopped>(s.runJob(dvFilm, "c", { false }))
        assertTrue(shell.ran.none { it.contains("convert --discard") })
        assertTrue(WorkFiles.libraryPathOf(WorkFiles.pathFor(dvFilm, WorkFiles.Kind.DV_VERSION)) == dvFilm)
    }

    // ── Phase 314c — the rename ticks, and Remove ──

    private val looseDv = "$dir/Third (2022)/Third.2022.2160p.UHD.BluRay.REMUX.mkv"
    private val renamedDv = "$dir/Third (2022)/Third (2022).mkv"

    private suspend fun addLooseDv() {
        shell.files[looseDv] = 40_000_000; shell.probes[looseDv] = dv7
        store.addOrUpdate(MediaItem(id = "third", title = "Third", year = 2022, kind = MediaKind.MOVIE, path = looseDv, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList()))
    }

    @Test fun `a Dolby Vision original not named after its folder waits for a rename tick - nothing else gets one`() = runBlocking {
        addLooseDv()
        val s = service()
        s.buildPlan()
        assertEquals("needs_rename", row(looseDv, FixKind.DOLBY_VISION)?.state)
        assertTrue(row(looseDv, FixKind.DOLBY_VISION)!!.detail.startsWith("rename to Third (2022).mkv first"))
        assertEquals("would_add", row(dvFilm, FixKind.DOLBY_VISION)?.state, "named after its folder already")
        s.setSetting(FixKind.DOLBY_VISION, true, false)
        s.apply(FixKind.DOLBY_VISION)
        assertEquals("needs_rename", row(looseDv, FixKind.DOLBY_VISION)?.state, "Apply never renames: only a tick does")
        val t = s.title("third").rows.single { it.kind == "c" }
        assertTrue(t.canAdd && !t.canRemove)
    }

    @Test fun `a ticked film is renamed - planned again under its new name - and its version queued`() = runBlocking {
        addLooseDv()
        renamer = { path ->
            // What 316's mover does in place: the file keeps its inode under the folder's name.
            shell.files[renamedDv] = shell.files.remove(path)!!; shell.probes[renamedDv] = shell.probes.remove(path)!!
            store.addOrUpdate(store.resolve("third")!!.copy(path = renamedDv))
            dev.jellystructure.model.FileFixRenameResult(path, renamedDv, "renamed")
        }
        val s = service()
        s.buildPlan()
        val out = s.renameAndQueue(listOf(looseDv, film))
        assertEquals(listOf("renamed", "failed"), out.map { it.state }, "only a needs_rename row can be renamed")
        assertNull(row(looseDv, FixKind.DOLBY_VISION))
        assertEquals("pending", row(renamedDv, FixKind.DOLBY_VISION)?.state)
        assertTrue(history.forItem("third").any { it.detail.contains("Renamed to Third (2022).mkv") })
    }

    @Test fun `a rename the mover refuses changes nothing`() = runBlocking {
        addLooseDv()
        renamer = { path -> dev.jellystructure.model.FileFixRenameResult(path, state = "failed", detail = "a torrent seeds this file in the library itself") }
        val s = service()
        s.buildPlan()
        assertEquals("failed", s.renameAndQueue(listOf(looseDv)).single().state)
        assertEquals("needs_rename", row(looseDv, FixKind.DOLBY_VISION)?.state)
    }

    private val ourCopy = """{"tracks": [
        {"id": 0, "type": "video", "properties": {"codec_id": "V_MPEG4/ISO/AVC", "tag_bps": "1"}},
        {"id": 1, "type": "audio", "properties": {"codec_id": "A_AC3", "track_name": "Dansk", "tag_bps": "1"}},
        {"id": 2, "type": "audio", "properties": {"codec_id": "A_AAC", "track_name": "Stereo", "tag_bps": "1", "tag_jellystructure_added": "2026-10-08"}}]}"""

    @Test fun `Remove takes our copy out of the file - verified - and the row stays removed until the title's own Add`() = runBlocking {
        val s = service()
        s.buildPlan()
        // 314a's job ran: the file now holds the copy (a new size, so the next probe reads it again).
        shell.probes[film] = ac3PlusCopy; shell.files[film] = 10_000_001
        db.fileFixQueries.setState("done", "", 0, clock, film, FixKind.STEREO.id)
        shell.identifyJson = ourCopy
        assertEquals(1, s.removeTitle(FixKind.STEREO, "film"))
        assertEquals("remove_pending", row(film, FixKind.STEREO)?.state)
        s.buildPlan()
        assertEquals("remove_pending", row(film, FixKind.STEREO)?.state, "a dry run leaves a queued Remove alone")
        assertEquals(film, s.nextToQueue(jobActive = false)?.path)
        assertIs<FixOutcome.Done>(s.runJob(film, "a", { false }))
        val mux = shell.ran.single { it.contains("--audio-tracks") }
        assertTrue(mux.contains("'!2'"), mux)
        assertTrue(shell.ran.any { it.contains("mv -f ") && it.endsWith("'$film'") })
        assertEquals("removed", row(film, FixKind.STEREO)?.state)
        assertTrue(history.forItem("film").any { it.detail.contains("removed") })
        // Switched on with new files automatically: the owner's Remove still sticks.
        s.setSetting(FixKind.STEREO, true, true)
        s.buildPlan()
        s.apply(FixKind.STEREO)
        assertEquals("removed", row(film, FixKind.STEREO)?.state)
        assertTrue(s.title("film").rows.single { it.kind == "a" }.canAdd)
        assertEquals(1, s.applyTitle(FixKind.STEREO, "film"))
        assertEquals("pending", row(film, FixKind.STEREO)?.state)
    }

    @Test fun `Remove on a seeded file only deletes the mka beside it`() = runBlocking {
        val s = service()
        s.buildPlan()
        val mka = "$dir/Film (2020)/Film (2020).dan.Stereo.mka"
        shell.files[mka] = 1_000
        db.fileFixQueries.setState("done", "seeded", 0, clock, film, FixKind.STEREO.id)
        s.removeTitle(FixKind.STEREO, "film")
        assertIs<FixOutcome.Done>(s.runJob(film, "a", { false }))
        assertTrue(mka !in shell.files)
        assertTrue(shell.ran.none { it.contains("--audio-tracks") || it.contains("mv -f") }, "the video itself is never touched")
    }

    @Test fun `Remove deletes our Dolby Vision version - never a file beside it that isn't ours`() = runBlocking {
        val s = service()
        s.buildPlan()
        val version = "$dir/Other (2021)/Other (2021) - Dolby Vision.mkv"
        shell.files[version] = 30_000_000
        shell.probes[version] = dv81
        db.fileFixQueries.setState("done", "", 0, clock, dvFilm, FixKind.DOLBY_VISION.id)
        s.removeTitle(FixKind.DOLBY_VISION, "other")
        assertIs<FixOutcome.Failed>(s.runJob(dvFilm, "c", { false }))
        assertTrue(version in shell.files, "untagged: not ours, left alone")
        assertEquals("done", row(dvFilm, FixKind.DOLBY_VISION)?.state)
        shell.probes[version] = dv81.replace("\"duration\": \"100.000000\"}", "\"duration\": \"100.000000\", \"tags\": {\"JELLYSTRUCTURE_DV\": \"8.1-from-7\"}}")
        s.removeTitle(FixKind.DOLBY_VISION, "other")
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        assertTrue(version !in shell.files)
        assertTrue(dvFilm in shell.files, "the original stays")
        assertEquals("removed", row(dvFilm, FixKind.DOLBY_VISION)?.state)
    }

    // ── FR-314-15 (DEB-875) — the version lives in the versions library, grouped by a merge ──────────────────────

    @Test fun `with a versions root the version is written there and grouped with the film - and Remove reports it gone`() = runBlocking {
        withVersionsRoot()
        val s = service()
        s.buildPlan()
        val version = "$dir/versions/Other (2021)/Other (2021) - Dolby Vision.mkv"
        assertEquals("would_add", row(dvFilm, FixKind.DOLBY_VISION)?.state)
        assertTrue(row(dvFilm, FixKind.DOLBY_VISION)!!.detail.contains("in the versions library"), row(dvFilm, FixKind.DOLBY_VISION)!!.detail)
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        assertTrue(version in shell.files, "the version is under the root, not beside the original: ${shell.files.keys}")
        assertTrue("$dir/Other (2021)/Other (2021) - Dolby Vision.mkv" !in shell.files, "nothing is written beside the original")
        assertTrue(dvFilm in shell.files, "the original is untouched")
        assertTrue(shell.ran.any { it.startsWith("mkdir -p") && it.contains("$dir/versions/Other (2021)") }, "the film's folder under the root is made")
        assertEquals(listOf("other" to version), grouped, "the version is grouped with the film")
        // Jellyfin is told; Radarr is not asked to rescan anything (FR-314-12 still).
        assertEquals(listOf("other" to false), afterWrites)
        assertTrue(shell.ran.none { it.contains("/Library/Refresh") }, "no library scan anywhere on this path")
        // Remove: the file goes and Jellyfin is told the path is gone — the film keeps its own file.
        shell.probes[version] = dv81.replace("\"duration\": \"100.000000\"}", "\"duration\": \"100.000000\", \"tags\": {\"JELLYSTRUCTURE_DV\": \"8.1-from-7\"}}")
        s.removeTitle(FixKind.DOLBY_VISION, "other")
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        assertTrue(version !in shell.files)
        assertEquals(listOf(version), ungrouped, "the deletion is reported, and nothing unmerges a film's other versions")
        assertTrue(dvFilm in shell.files, "the original stays")
    }

    @Test fun `a versions root inside a Radarr root folder refuses the job rather than writing a file Radarr will adopt`() = runBlocking {
        // FR-314-16 — the overlap is in Jellyfin's namespace: the backend writes `/mnt/films/versions` and that is
        // `/films/versions` to Jellyfin and to Radarr, whose root folder is `/films`. Compared raw it never fires.
        withVersionsRoot("/mnt/films/versions", jellyfinPath = "/films/versions")
        arrRoots = listOf("/films")
        val s = service()
        s.buildPlan()
        val outcome = s.runJob(dvFilm, "c", { false })
        assertIs<FixOutcome.Failed>(outcome)
        assertTrue(outcome.reason.contains("/films"), outcome.reason)
        assertTrue(shell.files.keys.none { it.contains("Dolby Vision") }, "nothing was written")
        assertTrue(grouped.isEmpty())
    }

    /** FR-314-16 acceptance 11 (DEB-898) — the forgotten `local_path` fails at the root and names itself. */
    @Test fun `a versions root no library mapping covers refuses the job up front and names the missing local path`() = runBlocking {
        appConfig = appConfig.copy(fileFix = dev.jellystructure.config.FileFixConfig(dvVersionsRoot = "$dir/versions"))
        val s = service()
        s.buildPlan()
        val outcome = s.runJob(dvFilm, "c", { false })
        assertIs<FixOutcome.Failed>(outcome)
        assertTrue(outcome.reason.contains("local_path"), outcome.reason)
        assertTrue(shell.files.keys.none { it.contains("Dolby Vision") }, "nothing was written")
        assertTrue(grouped.isEmpty(), "and Jellyfin was never told about a path it has never heard of")
    }

    @Test fun `a version Jellyfin has not resolved yet is reported and asserted again on the next pass`() = runBlocking {
        withVersionsRoot()
        groupAnswer = { VersionGrouping(false, "Jellyfin has not resolved it into an item yet") }
        val s = service()
        s.buildPlan()
        val version = "$dir/versions/Other (2021)/Other (2021) - Dolby Vision.mkv"
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }), "an ungrouped version is still a written version")
        assertTrue(history.forItem("other").any { it.detail.contains("has not grouped it with the film yet") },
            history.forItem("other").joinToString { it.detail })
        assertEquals("done", row(dvFilm, FixKind.DOLBY_VISION)?.state)
        // The next dry run finds the file on disk and asserts the merge again — nothing is remembered between passes.
        grouped.clear()
        groupAnswer = { VersionGrouping(true, "grouped with the film as a version") }
        s.buildPlan()
        assertEquals(listOf("other" to version), grouped)
        assertEquals("done", row(dvFilm, FixKind.DOLBY_VISION)?.state)
    }

    // ── FR-314-21 (DEB-1009) — the grouping is retried on a timer, not only from a press ─────────────────────────

    /**
     * Test 19 — acceptance 18's first half. The write's own window passes with nothing resolved (on prod: a versions
     * library Jellyfin had never scanned, which drops `Library/Media/Updated` with a 204 and no log line), and the
     * merge then lands off the timer with **no dry run in between** — no `buildPlan()` is called anywhere between the
     * write and the merge below, which is the whole point: until FR-314-21 the only pass was a *Find files* press.
     */
    @Test fun `a version whose grouping window passed is grouped by the retry - with no dry run in between`() = runBlocking {
        withVersionsRoot()
        groupAnswer = { VersionGrouping(false, "Jellyfin has not resolved it into an item yet") }
        val s = service()
        s.buildPlan()
        val version = "$dir/versions/Other (2021)/Other (2021) - Dolby Vision.mkv"
        val versionInJellyfinsView = "/media/versions/Other (2021)/Other (2021) - Dolby Vision.mkv"
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }), "an ungrouped version is still a written version")
        assertEquals(listOf(true), groupPatience, "the write itself waits for the item — FR-314-15's own window")
        grouped.clear(); groupPatience.clear()

        // The retry. Jellyfin knows the film and shows only the film's own file as a source.
        jellyfinSources = mapOf("jf-other" to setOf("/media/films/Other (2021)/Other (2021).mkv"))
        groupAnswer = { VersionGrouping(true, "grouped with the film as a version") }
        assertEquals(1, s.assertVersionGrouping(), "the version on disk with no matching source is merged")
        assertEquals(listOf("other" to version), grouped)
        assertEquals(listOf(false), groupPatience, "the retry asks impatiently: it comes back in a quarter of an hour")
        assertTrue(history.forItem("other").any { it.detail.contains("it is grouped now") },
            history.forItem("other").joinToString { it.detail })
        assertEquals("done", row(dvFilm, FixKind.DOLBY_VISION)?.state)

        // And once Jellyfin shows it, a tick is one read and no write at all.
        jellyfinSources = mapOf("jf-other" to setOf("/media/films/Other (2021)/Other (2021).mkv", versionInJellyfinsView))
        grouped.clear()
        val reads = sourceReads
        assertEquals(0, s.assertVersionGrouping())
        assertTrue(grouped.isEmpty(), "a merge that already holds is not written again every quarter of an hour")
        assertEquals(reads + 1, sourceReads, "one read for the whole set, per tick")
    }

    /** Test 19 — acceptance 18's second half: the bound, the report at it, and the report taken back. */
    @Test fun `the retry stops at its bound and reports the title - and takes that back when Jellyfin does group it`() = runBlocking {
        withVersionsRoot()
        groupAnswer = { VersionGrouping(false, "Jellyfin has not resolved it into an item yet") }
        val s = service()
        s.buildPlan()
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        jellyfinSources = mapOf("jf-other" to setOf("/media/films/Other (2021)/Other (2021).mkv"))
        grouped.clear()
        assertEquals(0, s.assertVersionGrouping(), "inside the bound it keeps asking")
        assertEquals(1, grouped.size)
        assertTrue(!row(dvFilm, FixKind.DOLBY_VISION)!!.detail.endsWith(FileFixService.UNGROUPED_NOTE), "and says nothing yet")

        clock += FileFixService.GROUPING_RETRY_WINDOW_SEC + 1
        grouped.clear()
        assertEquals(0, s.assertVersionGrouping())
        assertTrue(grouped.isEmpty(), "past the bound, asking again is not what is missing")
        val detail = row(dvFilm, FixKind.DOLBY_VISION)!!.detail
        assertTrue(detail.endsWith(FileFixService.UNGROUPED_NOTE), detail)
        assertTrue(detail.startsWith("the Dolby Vision 8.1 version is"), "the row still says what it did: $detail")
        assertEquals(1, history.forItem("other").count { it.detail.contains("stopped being asked") },
            history.forItem("other").joinToString { it.detail })

        // Said once, not once a tick.
        assertEquals(0, s.assertVersionGrouping())
        assertEquals(1, history.forItem("other").count { it.detail.contains("stopped being asked") })

        // And taken back the moment Jellyfin does show the version — the note can never outlive what it describes.
        jellyfinSources = mapOf("jf-other" to setOf("/media/films/Other (2021)/Other (2021).mkv",
            "/media/versions/Other (2021)/Other (2021) - Dolby Vision.mkv"))
        assertEquals(0, s.assertVersionGrouping())
        assertTrue(!row(dvFilm, FixKind.DOLBY_VISION)!!.detail.endsWith(FileFixService.UNGROUPED_NOTE),
            row(dvFilm, FixKind.DOLBY_VISION)!!.detail)
    }

    @Test fun `the retry merges nothing without a versions root - and nothing for a film Jellyfin does not know`() = runBlocking {
        val s = service()
        s.buildPlan()
        assertEquals(0, s.assertVersionGrouping(), "no root configured: kind C writes beside the original and this is not its problem")
        assertEquals(0, sourceReads, "and Jellyfin is not asked anything")

        withVersionsRoot()
        val withRoot = service()
        withRoot.buildPlan()
        assertIs<FixOutcome.Done>(withRoot.runJob(dvFilm, "c", { false }))
        grouped.clear()
        jellyfinSources = emptyMap()   // Jellyfin answers for no film: the id it had is gone
        assertEquals(0, withRoot.assertVersionGrouping())
        assertTrue(grouped.isEmpty(), "a film Jellyfin does not know is not a film to merge a version onto")
    }

    // ── FR-314-22 (DEB-1033) — the viewer visibility pass runs on the grouping tick, not on a merge ───────────────

    /**
     * Test 20 — acceptance 19. The merge has already happened and will not happen again (kind C with `auto_new = 0`
     * and one grouped film is prod's own steady state), and the restart that empties FR-314-19's field is modelled by
     * a fresh counter: the tick that merges nothing still invites the pass, every tick, because that field is the
     * only thing the kind C card reads.
     */
    @Test fun `a grouping tick that merges nothing still asserts viewer visibility - every tick`() = runBlocking {
        withVersionsRoot()
        val s = service()
        s.buildPlan()
        val version = "$dir/versions/Other (2021)/Other (2021) - Dolby Vision.mkv"
        val versionInJellyfinsView = "/media/versions/Other (2021)/Other (2021) - Dolby Vision.mkv"
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        assertTrue(shell.exists(version), "the version is on disk, which is what makes it a target")

        // The steady state: Jellyfin already shows the version, so the tick merges nothing at all.
        jellyfinSources = mapOf("jf-other" to setOf("/media/films/Other (2021)/Other (2021).mkv", versionInJellyfinsView))
        grouped.clear()
        visibilityPasses = 0   // the restart: FR-314-19's field goes back to empty and only a pass refills it
        assertEquals(0, s.assertVersionGrouping())
        assertTrue(grouped.isEmpty(), "nothing merged, which is the whole condition this covers")
        assertEquals(1, visibilityPasses, "a tick with a version on disk asks who can see it")

        assertEquals(0, s.assertVersionGrouping())
        assertEquals(2, visibilityPasses, "every tick, not once — the pass itself holds the rate limit")
    }

    /** Test 20's other half: no target, no pass — *nobody is shut out* and *nobody has looked* stay different. */
    @Test fun `a grouping tick with no version on disk asserts no viewer visibility`() = runBlocking {
        val noRoot = service()
        noRoot.buildPlan()
        visibilityPasses = 0
        assertEquals(0, noRoot.assertVersionGrouping(), "no versions root: there is no versions library to be shut out of")
        assertEquals(0, visibilityPasses)

        withVersionsRoot()
        val s = service()
        s.buildPlan()
        assertIs<FixOutcome.Done>(s.runJob(dvFilm, "c", { false }))
        shell.remove("$dir/versions/Other (2021)/Other (2021) - Dolby Vision.mkv")
        jellyfinSources = mapOf("jf-other" to setOf("/media/films/Other (2021)/Other (2021).mkv"))
        visibilityPasses = 0
        val reads = sourceReads
        assertEquals(0, s.assertVersionGrouping())
        assertEquals(0, visibilityPasses, "a done row whose version is gone is not a version anyone can be shut out of")
        assertEquals(reads, sourceReads, "and Jellyfin is asked nothing either")
    }

    @Test fun `with a versions root no film waits for a rename - and without one it still does`() = runBlocking {
        // A scene-named original in its own folder: Jellyfin's folder grouping would not group a version beside it.
        val scene = "$dir/Third (2022)/Third.2022.2160p.DV.mkv"
        shell.files[scene] = 40_000_000; shell.probes[scene] = dv7
        store.addOrUpdate(MediaItem(id = "third", title = "Third", year = 2022, kind = MediaKind.MOVIE, path = scene, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList()))
        renamer = { dev.jellystructure.model.FileFixRenameResult(scene, state = "failed", detail = "not asked") }
        service().buildPlan()
        assertEquals("needs_rename", row(scene, FixKind.DOLBY_VISION)?.state, "beside the original, grouping needs the rename")
        withVersionsRoot()
        service().buildPlan()
        assertEquals("would_add", row(scene, FixKind.DOLBY_VISION)?.state, "a merged version groups whatever the original is called")
    }

    // ── FR-314-18 (DEB-946) — a stored verdict is only as good as the configuration it was computed under ──

    /**
     * Test 16 — the gate the owner's tick goes through is re-derived from the configuration, not read off the row.
     * The rows stay as the dry run left them (prod never re-plans by itself), the root is configured underneath
     * them, and the rename that would have renamed 13 library files for nothing is refused.
     */
    @Test fun `a rename ticked on a verdict from before the versions root renames nothing - and corrects the row`() = runBlocking {
        addLooseDv()
        var asked = 0
        renamer = { path -> asked++; dev.jellystructure.model.FileFixRenameResult(path, renamedDv, "renamed") }
        service().buildPlan()
        assertEquals("needs_rename", row(looseDv, FixKind.DOLBY_VISION)?.state, "planned with no root, as prod's rows were")

        withVersionsRoot()
        val out = service().renameAndQueue(listOf(looseDv)).single()
        assertEquals(0, asked, "the mover is never called: nothing in the library is renamed")
        assertEquals("failed", out.state)
        assertTrue(out.detail!!.startsWith("no rename is needed any more"), out.detail!!)
        assertEquals("would_add", row(looseDv, FixKind.DOLBY_VISION)?.state, "the refusal replans the film where the live rules put it")
        assertTrue(looseDv in shell.files, "the original keeps its name")
    }

    /** Test 16, the other half — with no root configured the same tick still renames. */
    @Test fun `with no versions root a ticked rename still happens`() = runBlocking {
        addLooseDv()
        var asked = 0
        renamer = { path ->
            asked++
            shell.files[renamedDv] = shell.files.remove(path)!!; shell.probes[renamedDv] = shell.probes.remove(path)!!
            store.addOrUpdate(store.resolve("third")!!.copy(path = renamedDv))
            dev.jellystructure.model.FileFixRenameResult(path, renamedDv, "renamed")
        }
        val s = service()
        s.buildPlan()
        assertEquals("renamed", s.renameAndQueue(listOf(looseDv)).single().state)
        assertEquals(1, asked)
    }

    /**
     * Test 15 — the card does not report a count its own rules can no longer produce, and its headline sentence
     * names the placement the configuration implies. Both states that only the beside-the-original placement can
     * write are counted: the rename wait, and the skip for the folder's naming.
     */
    @Test fun `the card says its kind C counts predate the versions root - and names the configured placement`() = runBlocking {
        addLooseDv()
        val s = service()
        s.buildPlan()
        val before = s.overview().kinds.single { it.kind == "c" }
        assertNull(before.staleNote, "with no root configured the verdicts are the live rules'")
        assertTrue(before.sentence.contains("beside a profile 7 film"), before.sentence)

        withVersionsRoot()
        val after = service().overview().kinds.single { it.kind == "c" }
        assertTrue(after.sentence.contains("into the versions library"), after.sentence)
        assertTrue(after.sentence.contains("The original stays exactly as it is."), after.sentence)
        assertTrue(after.staleNote!!.startsWith("These counts were worked out before the versions library was configured"), after.staleNote!!)
        assertTrue(after.staleNote!!.contains("1 of the ${after.counts.values.sum()} films"), after.staleNote!!)
        assertNull(service().overview().kinds.single { it.kind == "a" }.staleNote, "kinds A and B place nothing a root can move")

        // The dry run is what clears it: the row leaves `needs_rename` and nothing predates the configuration.
        service().buildPlan()
        assertNull(service().overview().kinds.single { it.kind == "c" }.staleNote)
    }

    /** A kind C film skipped for its folder's naming is the other verdict the versions library can never produce. */
    @Test fun `a skip for the folder naming also predates a configured versions root`() = runBlocking {
        // A second video in the film's folder that does not start with the folder's name: with no root, Jellyfin
        // would not group anything in that folder, so the film is skipped rather than offered.
        shell.files["$dir/Other (2021)/Extra.1080p.mkv"] = 20_000_000
        service().buildPlan()
        assertEquals("skipped", row(dvFilm, FixKind.DOLBY_VISION)?.state)
        assertEquals(DV_SKIP_OTHER_VIDEO_NOT_NAMED, row(dvFilm, FixKind.DOLBY_VISION)?.detail)
        withVersionsRoot()
        assertTrue(service().overview().kinds.single { it.kind == "c" }.staleNote!!.contains("1 of the"))
    }

    /**
     * Test 17 (FR-314-19, DEB-986) — acceptance 10 and 16: the restricted accounts the FR-314-15 pass found are on
     * the kind C card, not only in a log line. Asserted on the view model the card renders, so what is tested is the
     * thing the owner reads rather than a string in the journal.
     *
     * The owner answered *report, never grant* on 2026-10-13 (DEB-949), so those accounts stay on the original
     * deliberately and indefinitely — which is exactly why none of this is allowed to be write-shaped: the overview
     * reads the pass's finding and never asks Jellyfin anything of its own.
     */
    @Test fun `the kind C card names the accounts that cannot see the versions library - and nothing when there are none`() = runBlocking {
        withVersionsRoot()
        service().buildPlan()

        // No such account: the card says nothing at all. A zero would read as a finding.
        assertEquals(emptyList<String>(), service().overview().kinds.single { it.kind == "c" }.restrictedViewers)

        restrictedViewers = listOf("viewer-kid", "viewer-guest")
        val kinds = service().overview().kinds
        assertEquals(listOf("viewer-kid", "viewer-guest"), kinds.single { it.kind == "c" }.restrictedViewers)
        // Only kind C writes into the versions library, so only its card can carry the report.
        assertEquals(emptyList<String>(), kinds.single { it.kind == "a" }.restrictedViewers)
        assertEquals(emptyList<String>(), kinds.single { it.kind == "b" }.restrictedViewers)
    }
}
