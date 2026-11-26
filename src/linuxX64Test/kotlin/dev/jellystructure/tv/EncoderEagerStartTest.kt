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
import kotlin.test.assertTrue

/**
 * DEB-5646 (phase 313, FR-313-19, FR-313-20) — a play's first job starts with its ticket, at the ticket's start, and a player's opening read of
 * the head does not replace it: a side job answers the read. The "ffmpeg" is a shell script that makes 100 segments of every variant, 0.3 s
 * apart, so a job is alive for the length of a test and a side job (3 segments) is done after about a second.
 */
class EncoderEagerStartTest {
    private val base = "/tmp/jellystructure-test-5646-jobs-${getpid()}"
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

    private fun encoder(): Encoder {
        workDir = "$base/work${runs++}"
        mkdirs(ffmpegDir); mkdirs(workDir)
        write("$ffmpegDir/ffmpeg", script)
        platform.posix.chmod("$ffmpegDir/ffmpeg", 493u)
        val dir = workDir
        return Encoder({ EncoderConfig(enabled = true, ffmpegDir = ffmpegDir, workDir = dir, cudaCacheDir = "$base/cuda") },
            cardsOverride = { emptyList() }, ffmpegOverride = { true }).also {
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

    // ── FR-313-19 ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a ticket at segment 30 starts one job at 30 before any request and the first request starts no second one`() = runBlocking<Unit> {
        val enc = encoder()
        val id = enc.register(plan, "p1", Long.MAX_VALUE, "dev", "item", startMs = 60_000)
        assertEquals(0, jobCount(), "register starts nothing")
        enc.startWithTicket(id, 30)
        waitFor("the job at 30") { jobDirs(30).size == 1 }
        val started = enc.running().single()
        assertEquals(30, started.startSegment)
        assertTrue(started.lastRequestAtMs - started.startedAtMs < 100, "the idle clock runs from the ticket, not from a request that has not come")
        assertNotNull(enc.segment(id, "v", 0, 30), "the first request is served by the job the ticket started")
        assertEquals(1, jobCount(), "no second job")
        assertEquals(1, enc.running().size)
        enc.stopFor("p1")
    }

    @Test fun `a ticket at segment 0 starts its job at 0 and a start past the last segment is held at it`() = runBlocking<Unit> {
        val enc = encoder()
        val id = enc.register(plan, "p2", Long.MAX_VALUE, "dev", "item")
        enc.startWithTicket(id, 0)
        waitFor("the job at 0") { jobDirs(0).size == 1 }
        val past = enc.register(plan, "p2b", Long.MAX_VALUE, "dev2", "item", startMs = 9_000_000)
        enc.startWithTicket(past, 4_500)
        val last = encoderSegmentCount(plan.source.durationMs) - 1
        waitFor("the job at the last segment") { jobDirs(last).size == 1 }
        enc.stopFor("p2"); enc.stopFor("p2b")
    }

    @Test fun `an adopted prewarm has its job and the ticket starts no new one`() = runBlocking<Unit> {
        val enc = encoder()
        enc.prewarm(plan, "dev", "item", 30, Long.MAX_VALUE)
        waitFor("the prewarm's job at 30") { jobDirs(30).size == 1 }
        val adopted = enc.adoptPrewarm("dev", "item", plan, 30, "p3", Long.MAX_VALUE)
        assertNotNull(adopted)
        assertNotNull(enc.segment(adopted, "v", 0, 30))
        assertEquals(1, jobCount(), "the warm job is the play's job")
        enc.stopFor("p3")
    }

    @Test fun `a ticket starts nothing when the encoder does not kick jobs`() = runBlocking<Unit> {
        val enc = encoder()
        enc.kickJobs = false
        val id = enc.register(plan, "p4", Long.MAX_VALUE, "dev", "item", startMs = 60_000)
        enc.startWithTicket(id, 30)
        delay(300)
        assertEquals(0, jobCount())
        enc.stopFor("p4")
    }

    // ── FR-313-20 ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a read of the head of a stream at 322 is answered by a side job and the job at 322 keeps running`() = runBlocking<Unit> {
        val enc = encoder()
        val id = enc.register(plan, "p5", Long.MAX_VALUE, "dev", "item", startMs = 644_000)
        enc.startWithTicket(id, 322)
        waitFor("the job at 322") { jobDirs(322).size == 1 }
        val head = enc.segment(id, "v", 0, 0)
        assertNotNull(head, "the head is served")
        assertEquals(1, jobDirs(322).size)
        assertEquals(1, jobDirs(0).size, "by a job at 0")
        assertTrue(enc.running().any { it.startSegment == 322 && !it.stopped && !it.side }, "the job at 322 was not replaced")
        assertTrue(enc.running().any { it.startSegment == 0 && it.side })
        assertNotNull(enc.segment(id, "v", 0, 322), "the first read at the start")
        assertEquals(1, jobDirs(322).size, "starts no job")
        assertEquals(2, jobCount())
        // The side job writes 3 segments (6 s) and stops; what it wrote stays for a repeat read.
        waitFor("the side job to stop") { enc.running().none { it.side } }
        val dir = SystemFileSystem.list(Path(workDir)).map { it.toString() }.single { it.contains("-0-") }
        for (n in 0..2) assertEquals(0, access("$dir/0/s$n.ts", F_OK), "segment $n of the side job")
        assertFalse(access("$dir/0/s3.ts", F_OK) == 0, "and no more than three")
        assertEquals(head, enc.segment(id, "v", 0, 0), "a repeat read costs nothing")
        assertEquals(2, jobCount())
        enc.stopFor("p5")
    }

    @Test fun `after the start has been served a read before it is a seek and restarts the job`() = runBlocking<Unit> {
        val enc = encoder()
        val id = enc.register(plan, "p6", Long.MAX_VALUE, "dev", "item", startMs = 644_000)
        enc.startWithTicket(id, 322)
        waitFor("the job at 322") { jobDirs(322).size == 1 }
        assertNotNull(enc.segment(id, "v", 0, 322))
        assertNotNull(enc.segment(id, "v", 0, 0))
        assertEquals(1, jobDirs(0).size, "a job at 0")
        assertTrue(enc.running().none { it.side }, "a seek's job, not a side job")
        assertTrue(enc.running().none { it.startSegment == 322 }, "the job at 322 was replaced")
        enc.stopFor("p6")
    }

    @Test fun `a stream at 0 is unchanged and a request with no ticket starts its job where it asks`() = runBlocking<Unit> {
        val enc = encoder()
        val id = enc.register(plan, "p7", Long.MAX_VALUE, "dev", "item")
        enc.startWithTicket(id, 0)
        waitFor("the job at 0") { jobDirs(0).size == 1 }
        assertNotNull(enc.segment(id, "v", 0, 0))
        assertNotNull(enc.segment(id, "v", 0, 200), "a seek replaces the job as before")
        assertEquals(1, jobDirs(200).size)
        assertTrue(enc.running().none { it.side })
        val lazy = enc.register(plan, "p7b", Long.MAX_VALUE, "dev2", "item", startMs = 644_000)
        assertNotNull(enc.segment(lazy, "v", 0, 322), "no ticket kick: the first request starts the job")
        assertNotNull(enc.segment(lazy, "v", 0, 0), "and its stream has no head to protect")
        assertTrue(enc.running().none { it.side && it.streamId == lazy })
        enc.stopFor("p7"); enc.stopFor("p7b")
    }

    @Test fun `a side job is stopped for a slot and keeps what it wrote`() = runBlocking<Unit> {
        val enc = encoder()
        val id = enc.register(plan, "p8", Long.MAX_VALUE, "dev", "item", startMs = 644_000)
        enc.startWithTicket(id, 322)
        waitFor("the job at 322") { jobDirs(322).size == 1 }
        assertNotNull(enc.segment(id, "v", 0, 0))
        assertEquals(1, enc.stopSideJobs())
        assertTrue(enc.running().none { it.side })
        assertNotNull(enc.segment(id, "v", 0, 0), "segment 0 was made before the stop")
        enc.stopFor("p8")
    }

    // ── FR-313-26 ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a prewarm pauses 5 segments past its start and a re-post leaves it there until a read resumes it to 20 ahead`() = runBlocking<Unit> {
        val enc = encoder()
        enc.prewarm(plan, "dev", "item", 30, Long.MAX_VALUE)
        waitFor("the prewarm's job at 30") { jobDirs(30).size == 1 }
        waitFor("the warm job to pause") { enc.running().single().paused }
        val job = enc.running().single()
        assertTrue(job.highest - job.startSegment <= EncoderJobs.WARM_AHEAD, "paused ${job.highest - job.startSegment} segments past its start")
        enc.prewarm(plan, "dev", "item", 30, Long.MAX_VALUE)
        delay(600)
        assertTrue(job.paused, "a re-post is no read")
        assertTrue(job.highest - job.startSegment <= EncoderJobs.WARM_AHEAD)
        val id = enc.adoptPrewarm("dev", "item", plan, 30, "p9", Long.MAX_VALUE)
        assertNotNull(id)
        assertNotNull(enc.segment(id, "v", 0, 30), "the first real read")
        waitFor("the job to resume and run to 20 ahead") { job.highest - job.startSegment > EncoderJobs.PAUSE_AHEAD - 2 }
        waitFor("the job to pause again") { job.paused }
        assertTrue(job.highest - job.startSegment > EncoderJobs.WARM_AHEAD + 5, "it keeps up to 20 ahead once read")
        enc.stopFor("p9")
    }

    @Test fun `a ticket's job pauses 5 segments past its start before its first read and runs on after it`() = runBlocking<Unit> {
        val enc = encoder()
        val id = enc.register(plan, "p10", Long.MAX_VALUE, "dev", "item", startMs = 60_000)
        enc.startWithTicket(id, 30)
        waitFor("the job at 30") { jobDirs(30).size == 1 }
        waitFor("the warm job to pause") { enc.running().single().paused }
        val job = enc.running().single()
        assertTrue(job.highest - job.startSegment <= EncoderJobs.WARM_AHEAD)
        assertNotNull(enc.segment(id, "v", 0, 30))
        waitFor("the job to run past the warm limit") { job.highest - job.startSegment > EncoderJobs.WARM_AHEAD + 5 }
        enc.stopFor("p10")
    }
}
