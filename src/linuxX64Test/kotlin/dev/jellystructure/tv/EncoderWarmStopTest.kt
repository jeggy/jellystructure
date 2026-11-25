package dev.jellystructure.tv

import dev.jellystructure.config.EncoderConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-5647 (phase 313, FR-313-21) — a stopped play's encode stays warm as the device's prewarm: a stop that leaves a resume point keeps the
 * stream, a matching start adopts it and is served from disk, and anything else stops it. The "ffmpeg" is a shell script that makes 100
 * segments of every variant, 0.3 s apart, so a job is alive for the length of a test.
 */
class EncoderWarmStopTest {
    private val base = "/tmp/jellystructure-test-5647-jobs-${getpid()}"
    private val ffmpegDir = "$base/ff"
    private var workDir = "$base/work"
    private var runs = 0

    @AfterTest fun cleanup() { runCatching { SystemFileSystem.list(Path(base)).forEach { rmTree(it.toString()) } }; platform.posix.rmdir(base) }

    private fun rmTree(p: String) {
        runCatching { SystemFileSystem.list(Path(p)).forEach { rmTree(it.toString()) } }
        platform.posix.unlink(p); platform.posix.rmdir(p)
    }

    private fun write(path: String, text: String) { SystemFileSystem.sink(Path(path)).buffered().use { it.write(text.encodeToByteArray(), 0, text.length) } }

    private val script = """#!/bin/sh
while [ ${'$'}# -gt 0 ]; do
  case "${'$'}1" in
    -hls_segment_filename) pat="${'$'}2"; shift;;
    -var_stream_map) vm="${'$'}2"; shift;;
  esac
  shift
done
n=${'$'}(echo ${'$'}vm | wc -w)
i=0
while [ ${'$'}i -lt 100 ]; do
  v=0
  while [ ${'$'}v -lt ${'$'}n ]; do
    f=${'$'}(echo "${'$'}pat" | sed "s|%v|${'$'}v|; s|%d|${'$'}i|")
    echo x > "${'$'}f.tmp" && mv "${'$'}f.tmp" "${'$'}f"
    v=${'$'}((v+1))
  done
  echo progress=continue
  sleep 0.3
  i=${'$'}((i+1))
done
"""

    private fun encoder(idleMs: Long = EncoderJobs.IDLE_MS, sweepMs: Long = EncoderJobs.IDLE_SWEEP_MS): Encoder {
        workDir = "$base/work${runs++}"
        mkdirs(ffmpegDir); mkdirs(workDir)
        write("$ffmpegDir/ffmpeg", script)
        platform.posix.chmod("$ffmpegDir/ffmpeg", 493u)
        val dir = workDir
        return Encoder({ EncoderConfig(enabled = true, ffmpegDir = ffmpegDir, workDir = dir, cudaCacheDir = "$base/cuda") },
            cardsOverride = { emptyList() }, ffmpegOverride = { true }, idleTimingMs = idleMs to sweepMs).also {
            // The jobs sweep the work folder once, in the background: let that finish before a job is made.
            runBlocking { it.running(); delay(300) }
        }
    }

    private val plan = EncoderPlan(
        source = EncoderSource("/m/film.mkv", 800_000, "h264", 1920, 1080, 8_000_000, hdr = false),
        codec = EncoderCodec.H264, mux = EncoderMux.TS,
        rungs = listOf(EncoderRung(1080, 1920, 1080, 8_000_000), EncoderRung(720, 1280, 720, 4_000_000)),
        startRung = 0, audio = listOf(EncoderAudio(0, 0, "aac", 2, "eng", "English", true)), cudaDevice = null,
    )

    /** The job folders under the work folder that start at segment [k] (`<stream>-<k>-<ms>`). */
    private fun jobDirs(k: Int): List<String> = SystemFileSystem.list(Path(workDir)).map { it.name }.filter { it.contains("-$k-") }

    private fun jobCount() = SystemFileSystem.list(Path(workDir)).size

    private suspend fun waitFor(what: String, ms: Long = 5_000, cond: suspend () -> Boolean) {
        val until = kotlin.time.Clock.System.now().toEpochMilliseconds() + ms
        while (kotlin.time.Clock.System.now().toEpochMilliseconds() < until) { if (cond()) return; delay(25) }
        assertTrue(cond(), "gave up waiting for: $what")
    }

    /** A play of (dev, item) at segment 30, its job running and its segment 31 made, as the player would have left it. */
    private suspend fun playing(enc: Encoder, session: String, item: String = "item"): String {
        val id = enc.register(plan, session, Long.MAX_VALUE, "dev", item, startMs = 60_000)
        enc.startWithTicket(id, 30)
        waitFor("the job at 30") { jobDirs(30).size == 1 }
        assertNotNull(enc.segment(id, "v", 0, 31))
        return id
    }

    private fun dirsGone() = SystemFileSystem.list(Path(workDir)).isEmpty()

    @Test fun `a stop mid-item leaves a prewarm entry at the stop's segment and the job keeps running`() = runBlocking<Unit> {
        val enc = encoder()
        playing(enc, "w1")
        assertTrue(enc.keepWarm("w1", 63_500))
        assertEquals(31, enc.prewarmSegmentOf("dev", "item"), "rounded to its segment")
        assertEquals(0, enc.stopFor("w1"), "the stop that follows has nothing of the play's to delete")
        assertEquals(1, enc.running().size, "its job stays")
        assertEquals(1, jobCount(), "and so does what it made")
        enc.cancelPrewarm("dev", "item")
    }

    @Test fun `a start of the same item within one segment adopts it and its first segment is served with no new job`() = runBlocking<Unit> {
        val enc = encoder()
        val id = playing(enc, "w2")
        assertTrue(enc.keepWarm("w2", 62_000))
        val adopted = enc.adoptPrewarm("dev", "item", plan, 32, "w2b", Long.MAX_VALUE)
        assertEquals(id, adopted, "the same stream")
        assertNull(enc.prewarmSegmentOf("dev", "item"), "no longer a prewarm")
        assertNotNull(enc.segment(adopted!!, "v", 0, 31), "served from disk")
        assertEquals(1, jobCount(), "no new job")
        assertEquals(1, enc.running().size)
        assertEquals(1, enc.stopFor("w2b"), "the adopting play's stop ends it")
        waitFor("the folder to go") { dirsGone() }
    }

    @Test fun `a start 5 segments away stops it and starts fresh`() = runBlocking<Unit> {
        val enc = encoder()
        playing(enc, "w3")
        assertTrue(enc.keepWarm("w3", 62_000))
        assertNull(enc.adoptPrewarm("dev", "item", plan, 36, "w3b", Long.MAX_VALUE))
        waitFor("the kept stream's folder to go") { dirsGone() }
        assertNull(enc.prewarmSegmentOf("dev", "item"))
        assertEquals(0, enc.running().size)
    }

    @Test fun `a start of another item by the device stops it and the same item does not`() = runBlocking<Unit> {
        val enc = encoder()
        playing(enc, "w4")
        assertTrue(enc.keepWarm("w4", 62_000))
        assertEquals(0, enc.stopKeptWarmExcept("dev", "item"))
        assertEquals(0, enc.stopKeptWarmExcept("other-dev", "other-item"), "another device's start is not this device's")
        assertEquals(31, enc.prewarmSegmentOf("dev", "item"))
        assertEquals(1, enc.stopKeptWarmExcept("dev", "other-item"))
        waitFor("the folder to go") { dirsGone() }
        assertNull(enc.prewarmSegmentOf("dev", "item"))
    }

    @Test fun `a prewarm post for the item keeps it alive and cancelPrewarm stops it`() = runBlocking<Unit> {
        val enc = encoder()
        val id = playing(enc, "w5")
        assertTrue(enc.keepWarm("w5", 62_000))
        assertEquals(id, enc.prewarm(plan, "dev", "item", 31, Long.MAX_VALUE), "the page's post touches the kept stream")
        delay(500)   // the post's own kick of the job (a launch) has run: a cancel straight after it would race it
        assertEquals(1, jobCount())
        assertEquals(1, enc.cancelPrewarm("dev", "item"))
        waitFor("the folder to go") { dirsGone() }
    }

    @Test fun `untouched for the idle time it stops and its folder is deleted`() = runBlocking<Unit> {
        val enc = encoder(idleMs = 1_200, sweepMs = 150)
        playing(enc, "w6")
        assertTrue(enc.keepWarm("w6", 62_000))
        assertEquals(1, jobCount())
        waitFor("the idle stop", ms = 6_000) { dirsGone() }
        assertNull(enc.prewarmSegmentOf("dev", "item"), "nothing left to adopt")
        assertNull(enc.adoptPrewarm("dev", "item", plan, 31, "w6b", Long.MAX_VALUE))
    }

    @Test fun `the warm time runs from the stop and not from the last segment the player read`() = runBlocking<Unit> {
        val enc = encoder(idleMs = 2_500, sweepMs = 150)
        playing(enc, "w7")
        delay(1_800)   // the player's last read is now 1.8 s old
        assertTrue(enc.keepWarm("w7", 62_000))
        delay(1_500)   // 3.3 s after the last read, 1.5 s after the stop
        assertEquals(1, enc.running().size, "still warm")
        waitFor("the idle stop", ms = 6_000) { dirsGone() }
    }

    @Test fun `a stream with no job and a download and an unknown play are not kept and the plain stop deletes at once`() = runBlocking<Unit> {
        val enc = encoder()
        enc.register(plan, "w8", Long.MAX_VALUE, "dev", "item", startMs = 60_000)
        assertFalse(enc.keepWarm("w8", 62_000), "no job was ever started for it")
        assertEquals(1, enc.stopFor("w8"))
        assertNull(enc.prewarmSegmentOf("dev", "item"))
        assertFalse(enc.keepWarm("nobody", 62_000))
        val dl = enc.registerDownload(plan, "phone", "item", Long.MAX_VALUE)
        assertFalse(enc.keepWarm(dl, 62_000))
        // The restream's and the replacement's release is stopFor with no keep: deleted at once, as before.
        playing(enc, "w9")
        assertEquals(1, enc.stopFor("w9"))
        waitFor("the folder to go") { dirsGone() }
        assertNull(enc.prewarmSegmentOf("dev", "item"))
    }

    @Test fun `a stop keeps a stream warm only when it leaves a resume point`() {
        val film = SessionPlan(durationMs = 7_200_000, creditsStartMs = 5_000_000)
        val noCredits = SessionPlan(durationMs = 7_200_000)
        fun keeps(pos: Long, p: SessionPlan?) = stopLeavesResumePoint(pos, p, resolveStop(pos, p))
        assertTrue(keeps(1_800_000, film))
        assertFalse(keeps(5_100_000, film), "at the credits is finished")
        assertFalse(keeps(6_600_000, noCredits), "past 90 % is finished")
        assertFalse(keeps(1_800_000, null), "an id outside the library")
    }
}
