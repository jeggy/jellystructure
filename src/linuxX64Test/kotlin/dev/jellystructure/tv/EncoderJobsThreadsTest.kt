package dev.jellystructure.tv

import dev.jellystructure.config.EncoderConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DEB-5702 (DEB-5685, phase 313, FR-313-25) — a job's ffmpeg reader never holds the pool the idle sweep and the first-segment watchers
 * use. The "ffmpeg" writes a segment every 20 ms, so a job runs ahead of a player that asks for nothing and is paused (SIGSTOP) within a
 * second, its reader blocked in `fgets` for as long as the job lives.
 */
class EncoderJobsThreadsTest {
    private val base = "/tmp/jellystructure-test-5702-jobs-${getpid()}"
    private val ffmpegDir = "$base/ff"
    private var workDir = "$base/work"

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
  sleep 0.02
  i=${'$'}((i+1))
done
"""

    private fun encoder(idleMs: Long = EncoderJobs.IDLE_MS, sweepMs: Long = EncoderJobs.IDLE_SWEEP_MS): Encoder {
        workDir = "$base/work"
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

    private fun now() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private suspend fun waitFor(what: String, ms: Long = 8_000, cond: suspend () -> Boolean) {
        val until = now() + ms
        while (now() < until) { if (cond()) return; delay(25) }
        assertTrue(cond(), "gave up waiting for: $what")
    }

    /** Ten plays started and left alone until every job is paused, so ten readers are blocked on ten stopped ffmpegs. */
    private suspend fun tenPausedStreams(enc: Encoder): List<String> {
        val ids = (0 until 10).map { i ->
            enc.register(plan, "t$i", Long.MAX_VALUE, "dev$i", "item$i", startMs = 0).also { enc.startWithTicket(it, 0) }
        }
        waitFor("ten jobs") { enc.running().size == 10 }
        waitFor("every job paused") { enc.running().all { it.paused } }
        return ids
    }

    @Test fun `an eleventh stream's first segment arrives in under 3 s while ten readers are blocked on paused jobs`() = runBlocking<Unit> {
        val enc = encoder()
        tenPausedStreams(enc)
        val id = enc.register(plan, "t10", Long.MAX_VALUE, "dev10", "item10", startMs = 0)
        enc.startWithTicket(id, 0)
        val t0 = now()
        assertNotNull(enc.segment(id, "v", 0, 0), "the eleventh job's first segment")
        assertTrue(now() - t0 < 3_000, "took ${now() - t0} ms")
    }

    @Test fun `an idle stream is still swept while ten readers are blocked on paused jobs`() = runBlocking<Unit> {
        val enc = encoder(idleMs = 2_500, sweepMs = 100)
        tenPausedStreams(enc)
        waitFor("every idle job swept") { enc.running().isEmpty() }
        waitFor("their folders removed") { SystemFileSystem.list(Path(workDir)).isEmpty() }
    }
}
