package dev.jellystructure.subtitles

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.media.FileIntegrityService
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 273 — the service end to end on a temp folder: one video, a stored embedded reference, three sidecars
 * (in sync, 50 s late, another episode's). Synthetic cue tracks only.
 */
class SubtitleCheckServiceTest {
    private val dir = "/tmp/jellystructure-test-subcheck-${getpid()}"
    private lateinit var dbPath: String
    private lateinit var db: JellystructureDb
    private lateinit var service: SubtitleCheckService

    private fun episode(seed: Int): List<Cue> {
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
        fun clock(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        "${i + 1}\n${clock(c.startMs)} --> ${clock(c.endMs)}\nline ${i + 1}\n"
    }.joinToString("\n")

    private fun String.format(vararg args: Any): String {
        var i = 0
        return Regex("%0(\\d)d").replace(this) { m -> args[i++].toString().padStart(m.groupValues[1].toInt(), '0') }
    }

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(Path("$dir/Film (2020)"))
        dbPath = "$dir/test.db"
        db = createDatabase(dbPath)
        val config = ConfigStore("$dir/config.toml")
        val store = MediaStore(db, JsTagStore("$dir/tags.json"), config)
        service = SubtitleCheckService(db, store, config, SubtitleReferences(db, JellyfinClient(), config), MediaHistory(db))
        SubtitleVerdicts.reportOnly = { false }
        service.init()
    }

    @AfterTest
    fun tearDown() {
        platform.posix.system("rm -rf '$dir'")
    }

    @Test
    fun `three sidecars against an embedded reference`() = runBlocking {
        val video = "$dir/Film (2020)/Film.mkv"
        FileIo.writeText(Path(video), "not really a video")
        val ref = episode(1)
        FileIo.writeText(Path("$dir/Film (2020)/Film.da.srt"), srt(ref.map { Cue(it.startMs + 120, it.endMs - 80) }))
        FileIo.writeText(Path("$dir/Film (2020)/Film.en.srt"), srt(ref.map { Cue(it.startMs + 50_000, it.endMs + 50_000) }))
        FileIo.writeText(Path("$dir/Film (2020)/Film.hr.srt"), srt(episode(2)))
        val stamp = FileIntegrityService.stampOf(video)!!
        db.subtitleCheckQueries.putReference(video, SubtitleReferences.EMBEDDED, stamp.size, stamp.mtime, "s:0", 10, CueCodec.encode(ref), 0)

        val item = MediaItem(
            id = "film", title = "Film", year = 2020, kind = MediaKind.MOVIE, path = video, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L,
            tracks = listOf(Track(0, "0:v:0", TrackKind.VIDEO, "h264", null, null, true, false, durationMs = 22 * 60_000L)),
        )
        assertTrue(service.isDue(video))
        val out = service.checkVideo(item, video, SubtitleCheckService.Mode.INLINE)
        val byLang = out.rows.associateBy { it.language }
        assertEquals("in_sync", byLang["da"]?.verdict, "${byLang["da"]}")
        assertEquals("off", byLang["en"]?.verdict)
        assertTrue(kotlin.math.abs((byLang["en"]?.shift_ms ?: 0) + 50_000) <= 300)
        assertEquals("not_this_video", byLang["hr"]?.verdict)
        assertTrue(byLang.values.all { it.reference == "embedded:s:0" })

        // FR-273-17 — the late one and the wrong one are not offered; the right one is.
        assertTrue(SubtitleVerdicts.isOffered("Film.da.srt"))
        assertFalse(SubtitleVerdicts.isOffered("$dir/Film (2020)/Film.en.srt"))
        assertFalse(SubtitleVerdicts.isOffered("Film.hr.srt"))
        // Only report shows everything.
        SubtitleVerdicts.reportOnly = { true }
        assertTrue(SubtitleVerdicts.isOffered("Film.hr.srt"))
        SubtitleVerdicts.reportOnly = { false }

        // A second pass has nothing to do until a file changes.
        assertFalse(service.isDue(video))
        assertEquals(0, service.checkVideo(item, video, SubtitleCheckService.Mode.INLINE).checked)
        // A sidecar that disappears takes its verdict with it.
        platform.posix.remove("$dir/Film (2020)/Film.hr.srt")
        assertTrue(service.isDue(video))
        service.checkVideo(item, video, SubtitleCheckService.Mode.INLINE)
        assertTrue(SubtitleVerdicts.isOffered("Film.hr.srt"))
        assertEquals(2, service.checksForItem("film").size)
    }

    @Test
    fun `with no reference and no job the verdict is cant tell and the hook asks for a job`() = runBlocking {
        val video = "$dir/Film (2020)/Film.mkv"
        FileIo.writeText(Path(video), "x")
        FileIo.writeText(Path("$dir/Film (2020)/Film.da.srt"), srt(episode(3)))
        val item = MediaItem(
            id = "film", title = "Film", year = 2020, kind = MediaKind.MOVIE, path = video, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L,
            tracks = listOf(
                Track(0, "0:v:0", TrackKind.VIDEO, "h264", null, null, true, false, durationMs = 22 * 60_000L),
                Track(1, "0:a:0", TrackKind.AUDIO, "aac", "eng", null, true, false),
            ),
        )
        val out = service.checkVideo(item, video, SubtitleCheckService.Mode.INLINE)
        assertEquals("cant_tell", out.rows.single().verdict)
        assertEquals(CantTell.NO_REFERENCE, out.rows.single().reason)
        assertTrue(out.needsJob)
        assertTrue(SubtitleVerdicts.isOffered("Film.da.srt"), "an unchecked subtitle stays offered")
    }

    @Test
    fun `check_subtitles joins a pipeline after prewarm once`() {
        val pipeline = listOf(PipelineStep("scan_files"), PipelineStep("prewarm_subtitles"), PipelineStep("verify_files"), PipelineStep("notify"))
        val seeded = FileCheckSteps.seedSubtitles(pipeline)
        assertEquals(listOf("scan_files", "prewarm_subtitles", FileCheckSteps.SUBTITLES, "verify_files", "notify"), seeded.map { it.step })
        assertEquals(seeded, FileCheckSteps.seedSubtitles(seeded))
        assertEquals(emptyList(), FileCheckSteps.seedSubtitles(emptyList()))
        val noPrewarm = FileCheckSteps.seedSubtitles(listOf(PipelineStep("scan_files"), PipelineStep("wait"), PipelineStep("notify")))
        assertEquals(listOf("scan_files", FileCheckSteps.SUBTITLES, "wait", "notify"), noPrewarm.map { it.step })
    }

    @Test
    fun `the speech accumulator hears centred bursts and not wide noise`() {
        val acc = SpeechAccumulator()
        val r = Random(9)
        val sr = SpeechAccumulator.SAMPLE_RATE
        val bytes = ArrayList<Byte>()
        fun sample(v: Int) { bytes += v.toByte(); bytes += (v shr 8).toByte() }
        // 20 s: speech (the same in both channels) during even seconds, wide noise (independent channels) during odd.
        for (s in 0 until 20 * sr) {
            val speech = (s / sr) % 2 == 0
            if (speech) { val v = r.nextInt(-9000, 9000); sample(v); sample(v) }
            else { sample(r.nextInt(-6000, 6000)); sample(r.nextInt(-6000, 6000)) }
        }
        acc.feed(bytes.toByteArray())
        val res = acc.finish()!!
        assertFalse(res.mono)
        assertEquals(200, res.frames.size)
        val speechSteps = (0 until 200).filter { (it / 10) % 2 == 0 }.map { res.frames[it].toInt() }
        val noiseSteps = (0 until 200).filter { (it / 10) % 2 == 1 }.map { res.frames[it].toInt() }
        assertTrue(speechSteps.average() > 80, "speech ${speechSteps.average()}")
        assertTrue(noiseSteps.average() < 5, "noise ${noiseSteps.average()}")
    }

    @Test
    fun `cue storage round-trips`() {
        val cues = listOf(Cue(0, 1_500), Cue(60_000, 61_000), Cue(7_200_000, 7_203_000))
        assertEquals(cues, CueCodec.decode(CueCodec.encode(cues)))
    }
}
