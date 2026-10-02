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

    // ── Phase 301 ─────────────────────────────────────────────────────────────────────────────

    private fun sub(index: Int, spec: String, lang: String?, external: Boolean = false, title: String? = null) =
        Track(index, spec, TrackKind.SUBTITLE, "subrip", lang, title, false, false, external = external)

    private fun jf(index: Int, type: String, lang: String?, external: Boolean = false) =
        dev.jellystructure.auth.JellyfinMediaStream(type = type, index = index, codec = "subrip", language = lang, isExternal = external)

    /** One production episode's numbering: ffprobe has the two English tracks at 2 and 3, Jellyfin lists the
     *  three sidecars first and the English tracks at 5 and 6. */
    private val tracks301 = listOf(
        Track(0, "0:v:0", TrackKind.VIDEO, "h264", null, null, true, false),
        Track(1, "0:a:0", TrackKind.AUDIO, "eac3", "eng", null, true, false),
        sub(2, "0:s:0", "eng"), sub(3, "0:s:1", "eng", title = "SDH"),
        sub(4, "ext:s:0", "da", external = true), sub(5, "ext:s:1", "hr", external = true), sub(6, "ext:s:2", "sr", external = true),
    )
    private val streams301 = listOf(
        jf(0, "Subtitle", "dan", true), jf(1, "Subtitle", "hrv", true), jf(2, "Subtitle", "srp", true),
        jf(3, "Video", null), jf(4, "Audio", "eng"), jf(5, "Subtitle", "eng"), jf(6, "Subtitle", "eng"),
    )

    @Test
    fun `an internal subtitle is fetched by Jellyfin's own number and not ffprobe's`() {
        assertEquals(5, SubtitleReferences.jellyfinIndexFor(tracks301[2], tracks301, streams301))
        assertEquals(6, SubtitleReferences.jellyfinIndexFor(tracks301[3], tracks301, streams301))
        // The hand-over maps Jellyfin's stream back to the track it really is.
        assertEquals(tracks301[2], SubtitleReferences.trackForJellyfinStream(streams301[5], tracks301, streams301))
        assertEquals(null, SubtitleReferences.trackForJellyfinStream(streams301[2], tracks301, streams301), "a sidecar is never a reference")
        // The two sides disagree on how many subtitles are inside the file: nothing is trusted.
        assertEquals(null, SubtitleReferences.jellyfinIndexFor(tracks301[2], tracks301, streams301.dropLast(1)))
        // …or on the language at that position.
        val swedish = streams301.map { if (it.index == 5) it.copy(language = "swe") else it }
        assertEquals(null, SubtitleReferences.jellyfinIndexFor(tracks301[2], tracks301, swedish))
    }

    private fun film(video: String) = MediaItem(
        id = "film", title = "Film", year = 2020, kind = MediaKind.MOVIE, path = video, tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L,
        tracks = listOf(Track(0, "0:v:0", TrackKind.VIDEO, "h264", null, null, true, false, durationMs = 22 * 60_000L)),
    )

    @Test
    fun `an ad after the end or a line that lasts all night is not a longer video`() = runBlocking {
        val video = "$dir/Film (2020)/Film.mkv"
        FileIo.writeText(Path(video), "not really a video")
        val ref = episode(1)
        val end = 22 * 60_000L
        // One ad line two minutes after the video ends.
        FileIo.writeText(Path("$dir/Film (2020)/Film.da.srt"), srt(ref + Cue(end + 120_000, end + 124_000)))
        // A last line that starts in the credits and "ends" 22 hours in.
        FileIo.writeText(Path("$dir/Film (2020)/Film.sr.srt"), srt(ref + Cue(end - 10_000, 22 * 3_600_000L)))
        // Lines for twice the video: still a longer video.
        FileIo.writeText(Path("$dir/Film (2020)/Film.hr.srt"), srt(ref + ref.map { Cue(it.startMs + end, it.endMs + end) }))
        val stamp = FileIntegrityService.stampOf(video)!!
        db.subtitleCheckQueries.putReference(video, SubtitleReferences.EMBEDDED, stamp.size, stamp.mtime, "s:0", 10, CueCodec.encode(ref), 0)

        val byLang = service.checkVideo(film(video), video, SubtitleCheckService.Mode.INLINE).rows.associateBy { it.language }
        assertEquals("in_sync", byLang["da"]?.verdict, "${byLang["da"]}")
        assertEquals(1L, byLang["da"]?.cues_past_end)
        assertTrue(service.verdictWords(byLang["da"]!!).contains("1 line after the video ends"))
        assertEquals("in_sync", byLang["sr"]?.verdict, "${byLang["sr"]}")
        assertTrue((byLang["sr"]?.last_cue_ms ?: 0) < end + 60_000, "the long line counts as 20 s")
        assertEquals("longer_video", byLang["hr"]?.verdict)
        assertEquals(0L, byLang["hr"]?.cues_past_end)
    }

    @Test
    fun `cue clean-up`() {
        val cues = listOf(Cue(1_000, 3_000), Cue(5_000, 90_000), Cue(100_000, 102_000))
        val c = VerdictRules.clean(cues, 60_000)
        assertEquals(listOf(Cue(1_000, 3_000), Cue(5_000, 25_000)), c.cues)
        assertEquals(1, c.pastEnd)
        // Four lines after the end are not an ad: kept, and judged by the longer-video rule.
        val four = cues + listOf(Cue(110_000, 111_000), Cue(120_000, 121_000), Cue(130_000, 131_000))
        assertEquals(0, VerdictRules.clean(four, 60_000).pastEnd)
        assertEquals(6, VerdictRules.clean(four, 60_000).cues.size)
        assertEquals(0, VerdictRules.clean(cues, null).pastEnd)
    }

    @Test
    fun `cue storage round-trips`() {
        val cues = listOf(Cue(0, 1_500), Cue(60_000, 61_000), Cue(7_200_000, 7_203_000))
        assertEquals(cues, CueCodec.decode(CueCodec.encode(cues)))
    }
}
