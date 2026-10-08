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

    private fun service() = FileFixService(
        db = db, store = store, config = { error("unused") }, history = history,
        seeding = { seedingAnswer() },
        radarrParse = { title -> if (title.endsWith(" - Dolby Vision")) radarrCopy else ArrParseResult("Remux-2160p", 0) },
        afterWrite = {}, playbackActive = { playing }, shell = shell, now = { clock }, today = { "2026-10-08" },
        renameOriginal = renamer,
    )

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
        store.addOrUpdate(MediaItem(id = "other", title = "Other", year = 2021, kind = MediaKind.MOVIE, path = dvFilm, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList()))
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
}
