package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3637 (DEB-3633 FR-2, FR-3, FR-7; acceptance 1–4) — synthetic packet fixtures only: a video, audio tracks and
 * `framecrc` text built here. No title, no filename from the library.
 */
class TimelineHolesTest {
    /** One stream's packet lines: [fromMs, toMs) in steps of [stepMs], skipping every [gaps] range. */
    private fun packets(stream: Int, toMs: Long, stepMs: Long, gaps: List<LongRange> = emptyList(), fromMs: Long = 0): List<String> =
        generateSequence(fromMs) { it + stepMs }.takeWhile { it < toMs }
            .filter { t -> gaps.none { t in it } }
            .map { t -> "$stream, $t, $t, $stepMs, 4000, 0xdeadbeef" }.toList()

    private fun header(vararg streams: Pair<String, String>): List<String> = buildList {
        add("#format: frame checksums"); add("#version: 2"); add("#hash: CRC32")
        streams.forEachIndexed { i, (type, codec) -> add("#tb $i: 1/1000"); add("#media_type $i: $type"); add("#codec_id $i: $codec") }
        add("#stream#, dts,        pts, duration,     size, hash")
    }

    private fun scan(lines: List<String>, hints: Map<Int, StreamHint> = emptyMap()): TimelineReport {
        val s = TimelineScan(hints)
        lines.forEach(s::line)
        return s.report()
    }

    // acceptance 1
    @Test
    fun `a 2 s hole in the video and both audio tracks is one hole of 2 s`() {
        val hole = listOf(10_000L..11_999L)
        val lines = header("video" to "h264", "audio" to "eac3", "audio" to "aac") +
            packets(0, 30_000, 40, hole) + packets(1, 30_000, 20, hole) + packets(2, 30_000, 25, hole)
        val r = scan(lines)
        assertEquals(1, r.holeCount)
        assertEquals(2_000L, r.missingMs)
        assertEquals(2_000L, r.longestMs)
        assertEquals(listOf(TimelineHole(10_000, 2_000)), r.holes)
        assertNull(r.worstTrackMissingMs)
        assertFalse(r.whole)
    }

    @Test
    fun `a 4 s gap in the video alone is not a hole - the sound plays on`() {
        val lines = header("video" to "h264", "audio" to "aac") + packets(0, 30_000, 40, listOf(10_000L..13_999L)) + packets(1, 30_000, 20)
        assertTrue(scan(lines).whole)
    }

    @Test
    fun `a 3 s gap in the sound alone is not a hole - the picture continues`() {
        val lines = header("video" to "h264", "audio" to "aac") + packets(0, 30_000, 40) + packets(1, 30_000, 20, listOf(10_000L..12_999L))
        assertTrue(scan(lines).whole)
    }

    @Test
    fun `a hole of 2_2 s on one track and 1_9 s on another reports the default track's figure and keeps the worst`() {
        // The picture is gone 10.0–12.2. Track 1 (default) is gone the same time, track 2 only from 10.3.
        val video = packets(0, 30_000, 40, listOf(10_000L..12_199L))
        val a1 = packets(1, 30_000, 20, listOf(10_000L..12_199L))
        val a2 = packets(2, 30_000, 20, listOf(10_300L..12_199L))
        val hints = mapOf(2 to StreamHint(default = true))
        // Default is track 2: 1.9 s, and the worst (track 1) is 2.2 s — more than a second apart? No: 0.3 s, so not kept.
        val r2 = scan(header("video" to "h264", "audio" to "eac3", "audio" to "aac") + video + a1 + a2, hints)
        assertEquals(1_900L, r2.missingMs)
        assertNull(r2.worstTrackMissingMs)
        // Make track 1's gap far wider (3 s more across the file): the default's figure stays, the worst is kept.
        val wide = packets(1, 30_000, 20, listOf(10_000L..12_199L, 20_000L..23_999L))
        val video2 = packets(0, 30_000, 40, listOf(10_000L..12_199L, 20_000L..23_999L))
        val a2b = packets(2, 30_000, 20, listOf(10_300L..12_199L)) // the sound plays on in the second gap
        val r3 = scan(header("video" to "h264", "audio" to "eac3", "audio" to "aac") + video2 + wide + a2b, hints)
        assertEquals(1, r3.holeCount)
        assertEquals(1_900L, r3.missingMs)
        assertEquals(1_900L + 4_000L + 300L, r3.worstTrackMissingMs)
    }

    @Test
    fun `the first audio track is the file's figure when none is flagged default`() {
        val video = packets(0, 30_000, 40, listOf(10_000L..12_199L))
        val a1 = packets(1, 30_000, 20, listOf(10_000L..12_199L))
        val a2 = packets(2, 30_000, 20, listOf(10_300L..12_199L))
        val r = scan(header("video" to "h264", "audio" to "eac3", "audio" to "aac") + video + a1 + a2)
        assertEquals(2_200L, r.missingMs)
    }

    @Test
    fun `0_4 s is not a hole and 0_5 s is`() {
        fun gapOf(ms: Long) = scan(header("video" to "h264", "audio" to "aac") +
            packets(0, 10_000, 40, listOf(5_000L..(5_000L + ms - 1))) + packets(1, 10_000, 20, listOf(5_000L..(5_000L + ms - 1)))).holeCount
        assertEquals(0, gapOf(400))
        assertEquals(1, gapOf(500))
    }

    @Test
    fun `a cover picture with one packet is never the main video`() {
        val hole = listOf(10_000L..11_999L)
        val lines = header("video" to "mjpeg", "video" to "h264", "audio" to "aac") +
            listOf("0, 0, 0, 1, 90000, 0x1") + packets(1, 30_000, 40, hole) + packets(2, 30_000, 20, hole)
        val r = scan(lines, mapOf(0 to StreamHint(attachedPic = true)))
        assertEquals(1, r.holeCount)
        assertEquals(emptyList(), r.emptyStreams)
    }

    @Test
    fun `a picture reordered by B-frames leaves no hole`() {
        // dts ascends, pts jumps back and forth inside a few frames — the order frames arrive in is not their time.
        val video = (0 until 750).flatMap { g ->
            val base = g * 120L
            listOf(base + 120, base + 40, base + 80, base).mapIndexed { i, pts -> "1, ${base + i * 40L}, $pts, 40, 4000, 0x2" }
        }
        val r = scan(header("audio" to "aac", "video" to "h264") + packets(0, 90_000, 20) + video)
        assertTrue(r.whole)
    }

    @Test
    fun `streams with no packet are listed - video audio and subtitle alike`() {
        val lines = header("video" to "h264", "audio" to "aac", "audio" to "aac", "subtitle" to "subrip", "attachment" to "ttf") +
            packets(0, 5_000, 40) + packets(1, 5_000, 20)
        val r = scan(lines)
        assertEquals(listOf(2, 3), r.emptyStreams)
        assertTrue(r.whole)
    }

    @Test
    fun `a video with no duration covers one frame at the stream's frame rate`() {
        val video = (0 until 500).map { "0, ${it * 40}, ${it * 40}, 0, 4000, 0x3" }
        val r = scan(header("video" to "h264", "audio" to "aac") + video + packets(1, 20_000, 20), mapOf(0 to StreamHint(frameRate = 25.0)))
        assertTrue(r.whole)
    }

    @Test
    fun `a file with no sound has no hole to report`() {
        assertTrue(scan(header("video" to "h264") + packets(0, 30_000, 40, listOf(10_000L..14_999L))).whole)
    }

    @Test
    fun `a long film folds without trouble`() {
        // 2 hours at 24 fps and 48 kHz AAC frames: ~290 000 packets, never held.
        val s = TimelineScan()
        header("video" to "h264", "audio" to "aac").forEach(s::line)
        var t = 0L
        while (t < 7_200_000) { s.line("0, $t, $t, 41, 4000, 0x1"); t += 42 }
        t = 0
        while (t < 7_200_000) { s.line("1, $t, $t, 21, 400, 0x1"); t += 21 }
        assertTrue(s.report().whole)
    }

    // acceptance 2 — FR-3
    private val dir = "/tmp/jellystructure-test-timeline-${getpid()}"

    @BeforeTest
    fun setUp() { SystemFileSystem.createDirectories(Path(dir)) }

    @AfterTest
    fun tearDown() { platform.posix.system("rm -rf '$dir'") }

    private fun file(name: String, mtime: Long = 1_700_000_000, bytes: Int = 8): String {
        val p = "$dir/$name"
        platform.posix.system("head -c $bytes /dev/zero > '$p' && touch -d @$mtime '$p'")
        return p
    }

    @Test
    fun `a stored row stops being current when size or mtime changes and a pass that did not finish stores nothing`() {
        val db = createDatabase("$dir/test.db")
        val timeline = FileTimeline(db)
        val path = file("a.mkv")
        val stamp = assertNotNull(FileIntegrityService.stampOf(path))
        assertEquals(TimelineState.UNCHECKED, timeline.resultFor(path).state)   // nothing stored: unchecked, never whole

        timeline.store(path, stamp.size, stamp.mtime, TimelineReport(1, 2_000, 2_000, null, listOf(TimelineHole(10_000, 2_000)), emptyList()))
        assertEquals(TimelineState.HOLES, timeline.resultFor(path).state)
        timeline.store(path, stamp.size, stamp.mtime, TimelineReport(0, 0, 0, null, emptyList(), listOf(2)))
        val whole = timeline.resultFor(path)
        assertEquals(TimelineState.WHOLE, whole.state)
        assertEquals(listOf(2), whole.report?.emptyStreams)

        platform.posix.system("touch -d @1700000500 '$path'")
        assertEquals(TimelineState.UNCHECKED, timeline.resultFor(path).state)   // mtime moved
        platform.posix.system("touch -d @1700000000 '$path' && echo more >> '$path'")
        assertEquals(TimelineState.UNCHECKED, timeline.resultFor(path).state)   // size moved

        // A killed pass: the reader never saw an exit code, so there is no result to store.
        val killed = DeepCheckReader()
        header("video" to "h264", "audio" to "aac").forEach(killed::line)
        assertNull(killed.result())
    }

    @Test
    fun `the Triage set holds only files whose row is current and has holes`() {
        val db = createDatabase("$dir/test2.db")
        val timeline = FileTimeline(db)
        assertNull(timeline.holePathsOrNull())   // nothing stored yet: unknown, not "none"
        val a = file("a.mkv"); val b = file("b.mkv")
        val sa = assertNotNull(FileIntegrityService.stampOf(a)); val sb = assertNotNull(FileIntegrityService.stampOf(b))
        timeline.store(a, sa.size, sa.mtime, TimelineReport(3, 9_000, 4_000, null, emptyList(), emptyList()))
        timeline.store(b, sb.size, sb.mtime, TimelineReport(0, 0, 0, null, emptyList(), emptyList()))
        assertEquals(setOf(a), timeline.holePathsOrNull())
        platform.posix.system("echo more >> '$a'")
        assertEquals(emptySet(), timeline.holePathsOrNull())
    }

    // acceptance 4 — one pass, both findings
    @Test
    fun `one deep-check pass yields the demuxer's damage and the file's hole`() {
        val hole = listOf(10_000L..11_999L)
        val out = header("video" to "h264", "audio" to "aac") + packets(0, 30_000, 40, hole) + packets(1, 30_000, 20, hole) + listOf(
            "JS_EXIT=0", "JS_STDERR",
            "[matroska,webm @ 0x55fe3014d280] Unknown-sized element at 0x16c81 inside parent with finite size",
            "[eac3 @ 0x557e3eaa0100] exponent -2 is out-of-range",
        )
        val reader = DeepCheckReader()
        out.forEach { reader.line(it + "\n") }
        val r = assertNotNull(reader.result())
        assertEquals(listOf("[matroska,webm @ 0x55fe3014d280] Unknown-sized element at 0x16c81 inside parent with finite size"), r.damage)
        assertEquals(1, assertNotNull(r.timeline).holeCount)
    }

    @Test
    fun `a pass that ffmpeg could not open is damage with its last line and no timeline while no ffmpeg is nothing`() {
        val failed = DeepCheckReader()
        listOf("JS_EXIT=1", "JS_STDERR", "x.mkv: Invalid data found when processing input").forEach(failed::line)
        val r = assertNotNull(failed.result())
        assertEquals(listOf("x.mkv: Invalid data found when processing input"), r.damage)
        assertNull(r.timeline)

        val missing = DeepCheckReader()
        listOf("JS_EXIT=127", "JS_STDERR", "sh: ffmpeg: not found").forEach(missing::line)
        assertNull(missing.result())
    }

    @Test
    fun `the deep-check command reads the file once and keeps the complaints off the packet stream`() {
        val cmd = FileIntegrity.timelineCommand("/m/it's.mkv")
        assertEquals(1, Regex("ffmpeg").findAll(cmd).count())
        assertTrue("-f framecrc -" in cmd)
        assertTrue("'/m/it'\\''s.mkv'" in cmd)
        assertTrue("2>\"\$e\"" in cmd)
    }

    // acceptance 3 — FR-7
    private fun report(n: Int = 58) = TimelineReport(n, 91_000, 3_900, null, listOf(TimelineHole(24_000, 3_900), TimelineHole(40_000, 2_000)), emptyList())

    @Test
    fun `the advice says what is missing in the spec's words`() {
        val a = TimelineHolesAdvice.advise(report(), otherFilesOfSameReleaseHaveHoles = false)
        assertEquals("Picture and sound are missing in 58 places, 1:31 in all. The longest gap is 3.9 s, at 0:24.", a.what)
        assertEquals(TimelineHolesAdvice.ONE_FILE, a.suggestion)
        assertEquals("0:24 (3.9 s)", TimelineHolesAdvice.holeLine(TimelineHole(24_000, 3_900)))
    }

    @Test
    fun `the release suggestion is chosen only when another file of the same release has holes`() {
        val one = TimelineHolesAdvice.advise(report(), false)
        val many = TimelineHolesAdvice.advise(report(), true)
        assertEquals(TimelineHolesAdvice.ONE_FILE, one.suggestion)
        assertEquals(TimelineHolesAdvice.SAME_RELEASE, many.suggestion)
        assertTrue("command" !in many.suggestion.lowercase() && "ffmpeg" !in many.suggestion)
    }

    @Test
    fun `same release is the group in the name or else the same directory and name pattern`() {
        assertTrue(TimelineHolesAdvice.sameRelease("/tv/A/S01/A.S01E01.1080p.WEB-DL-GRP.mkv", "/tv/A/S01/A.S01E02.1080p.WEB-DL-GRP.mkv"))
        assertFalse(TimelineHolesAdvice.sameRelease("/tv/A/S01/A.S01E01.1080p.WEB-DL-GRP.mkv", "/tv/A/S01/A.S01E02.1080p.WEB-DL-OTHER.mkv"))
        assertTrue(TimelineHolesAdvice.sameRelease("/tv/A/S01/Ep 01.mkv", "/tv/A/S01/Ep 02.mkv"))
        assertFalse(TimelineHolesAdvice.sameRelease("/tv/A/S01/Ep 01.mkv", "/tv/B/S01/Ep 02.mkv"))
        assertFalse(TimelineHolesAdvice.sameRelease("/tv/A/S01/x-GRP.mkv", "/tv/A/S01/x-GRP.mkv"))   // a file is not its own sibling
    }

    @Test
    fun `a filename with a quote is safe`() {
        val p = "/tv/It's a Show/S01/It's.S01E01.WEB-GR'P.mkv"
        assertNotNull(TimelineHolesAdvice.releaseKey(p))
        val a = TimelineHolesAdvice.advise(report(1), TimelineHolesAdvice.sameRelease(p, "/tv/It's a Show/S01/It's.S01E02.WEB-GR'P.mkv"))
        assertTrue("1 place," in a.what)
    }

    @Test
    fun `a season reads as the spec's line`() {
        assertEquals("S01: 5 episodes, 2 to 9 minutes missing each", TimelineHolesAdvice.seasonLine("S01", listOf(120_000, 300_000, 400_000, 500_000, 513_000)))
        assertEquals("S02: 1 episode, about 1 minute missing", TimelineHolesAdvice.seasonLine("S02", listOf(58_000)))
    }

    // FR-1 against the real ffmpeg: one read yields the hole, and a whole file of the same make reads whole.
    private fun ffmpegAvailable() = platform.posix.system("ffmpeg -version >/dev/null 2>&1") == 0

    private fun synth(name: String, cut: Boolean): String {
        val p = "$dir/$name"
        val filter = if (cut)
            "-filter_complex \"[0:v]select='not(between(t,4,6))',setpts=PTS[v];[1:a]aselect='not(between(t,4,6))',asetpts=PTS[a]\" -map \"[v]\" -map \"[a]\" -fps_mode passthrough"
        else "-map 0:v -map 1:a"
        val code = platform.posix.system(
            "ffmpeg -v error -y -f lavfi -i testsrc=size=160x90:rate=25:duration=12 -f lavfi -i sine=frequency=440:duration=12:sample_rate=48000 " +
                "$filter -c:v libx264 -preset ultrafast -c:a aac '$p'",
        )
        assertEquals(0, code, "ffmpeg could not build the fixture")
        return p
    }

    @Test
    fun `the real deep check finds a 2 s hole in one read and reads a whole file whole`() = runBlocking {
        if (!ffmpegAvailable()) return@runBlocking
        val db = createDatabase("$dir/real.db")
        val configStore = ConfigStore("$dir/config.toml")
        val service = FileIntegrityService(db, SeedingSnapshot(configStore, QBittorrentClient()))
        val holed = synth("holed.mkv", cut = true)
        val whole = synth("whole.mkv", cut = false)

        assertEquals(FileIntegrityState.CLEAN, service.check(holed)?.state)   // the container is valid: no demuxer damage
        val r = assertNotNull(service.timeline.resultFor(holed).report)
        assertEquals(TimelineState.HOLES, service.timeline.resultFor(holed).state)
        assertEquals(1, r.holeCount)
        assertTrue(r.missingMs in 1_900L..2_100L, "missing ${r.missingMs} ms")
        assertTrue(r.holes.single().startMs in 3_900L..4_100L)

        assertEquals(FileIntegrityState.CLEAN, service.check(whole)?.state)
        assertEquals(TimelineState.WHOLE, service.timeline.resultFor(whole).state)
        assertEquals(emptyList(), service.timeline.resultFor(whole).report?.emptyStreams)
        assertEquals(setOf(holed), service.timeline.holePathsOrNull())
        Unit
    }
}
