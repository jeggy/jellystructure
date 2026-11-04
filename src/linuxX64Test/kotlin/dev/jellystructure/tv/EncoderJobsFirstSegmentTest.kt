package dev.jellystructure.tv

import dev.jellystructure.config.EncoderConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * DEB-3629 (FR-313-12, phase 313's amendment of 2026-11-05) — a stream's first job that is still running and has written no segment by
 * its deadline is refused like an exit with an error: stopped, 410 on its URLs, the file in [RefusedSources]. The deadlines are scaled
 * down here (1.5 s for 5 s, 4 s for 15 s); the real ones are asserted as constants. The "ffmpeg" is a shell script whose `mode` file
 * says `ok` (30 segments at once), `silent` (alive, writes nothing) or `slow` (writes nothing for 0.9 s, then as `ok`).
 */
class EncoderJobsFirstSegmentTest {
    private val base = "/tmp/jellystructure-test-3629-jobs-${getpid()}"
    private val ffmpegDir = "$base/ff"
    private var runs = 0

    @AfterTest fun cleanup() { runCatching { SystemFileSystem.list(Path(base)).forEach { rmTree(it.toString()) } }; platform.posix.rmdir(base) }

    private fun rmTree(p: String) {
        runCatching { SystemFileSystem.list(Path(p)).forEach { rmTree(it.toString()) } }
        platform.posix.unlink(p); platform.posix.rmdir(p)
    }

    private fun write(path: String, text: String) { SystemFileSystem.sink(Path(path)).buffered().use { it.write(text.encodeToByteArray(), 0, text.length) } }

    private fun mode(m: String) = write("$ffmpegDir/mode", m)

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private fun encoder(): Encoder {
        val workDir = "$base/work${runs++}"
        mkdirs(ffmpegDir); mkdirs(workDir)
        write("$ffmpegDir/ffmpeg", """#!/bin/sh
mode=${'$'}(cat "${'$'}(dirname "${'$'}0")/mode" 2>/dev/null || echo ok)
if [ "${'$'}mode" = silent ]; then exec sleep 60; fi
if [ "${'$'}mode" = slow ]; then sleep 0.9; fi
while [ ${'$'}# -gt 0 ]; do
  case "${'$'}1" in
    -hls_segment_filename) pat="${'$'}2"; shift;;
    -var_stream_map) vm="${'$'}2"; shift;;
  esac
  shift
done
n=${'$'}(echo ${'$'}vm | wc -w)
i=0
while [ ${'$'}i -lt 30 ]; do
  v=0
  while [ ${'$'}v -lt ${'$'}n ]; do
    f=${'$'}(echo "${'$'}pat" | sed "s|%v|${'$'}v|; s|%d|${'$'}i|")
    echo x > "${'$'}f.tmp" && mv "${'$'}f.tmp" "${'$'}f"
    v=${'$'}((v+1))
  done
  echo progress=continue
  sleep 0.05
  i=${'$'}((i+1))
done
""")
        platform.posix.chmod("$ffmpegDir/ffmpeg", 493u)
        mode("ok")
        return Encoder({ EncoderConfig(enabled = true, ffmpegDir = ffmpegDir, workDir = workDir, cudaCacheDir = "$base/cuda") },
            cardsOverride = { emptyList() }, ffmpegOverride = { true }, firstSegmentDeadlinesMs = 1_500L to 4_000L).also {
            // The jobs start lazily and sweep the work folder once, in the background: let that finish before a job is made.
            runBlocking { it.running(); delay(300) }
        }
    }

    private fun planOn(card: Int?, path: String = "/m/film.mkv") = EncoderPlan(
        source = EncoderSource(path, 600_000, "h264", 1920, 1080, 8_000_000, hdr = false),
        codec = EncoderCodec.H264, mux = EncoderMux.TS,
        rungs = listOf(EncoderRung(1080, 1920, 1080, 8_000_000), EncoderRung(720, 1280, 720, 4_000_000)),
        startRung = 0, audio = listOf(EncoderAudio(0, 0, "aac", 2, "eng", "English", true)), cudaDevice = card,
    )

    @Test fun `the deadlines are 5 s and 15 s on a cold card`() {
        assertEquals(5_000L, EncoderJobs.FIRST_SEGMENT_MS)
        assertEquals(15_000L, EncoderJobs.COLD_FIRST_SEGMENT_MS)
    }

    @Test fun `a silent first job that stays alive is refused at its deadline and the file goes to Jellyfin`() = runBlocking<Unit> {
        val enc = encoder()
        mode("silent")
        val plan = planOn(null)
        val id = enc.register(plan, "p1", Long.MAX_VALUE)
        val t0 = now()
        assertNull(enc.segment(id, "v", 0, 0))
        val took = now() - t0
        assertTrue(took in 1_400L..5_000L, "refused at the 1.5 s deadline, not after the 15 s wait: $took ms")
        assertTrue(enc.refusedStream(id))
        assertEquals(Encoder.Miss.GONE, enc.miss(id, "v", 0, 0), "410, so the player gives up while it is starting")
        assertNotNull(enc.refused.reason(plan.source.path, now()), "the file is in RefusedSources")
        enc.stopFor("p1")
    }

    @Test fun `the first job on a card gets the long deadline and a warm card the short one`() = runBlocking<Unit> {
        val enc = encoder()
        mode("silent")
        val cold = enc.register(planOn(0, "/m/a.mkv"), "p2", Long.MAX_VALUE)
        val t0 = now()
        assertNull(enc.segment(cold, "v", 0, 0))
        val coldMs = now() - t0
        assertTrue(coldMs in 3_900L..8_000L, "cold card: refused at the 4 s deadline, not 1.5 s: $coldMs ms")
        assertTrue(enc.refusedStream(cold))

        // A card that has written a segment is warm: its next first job is held to the short deadline.
        mode("ok")
        val ok = enc.register(planOn(0, "/m/b.mkv"), "p3", Long.MAX_VALUE)
        assertNotNull(enc.segment(ok, "v", 0, 0))
        mode("silent")
        val warm = enc.register(planOn(0, "/m/c.mkv"), "p4", Long.MAX_VALUE)
        val t1 = now()
        assertNull(enc.segment(warm, "v", 0, 0))
        val warmMs = now() - t1
        assertTrue(warmMs in 1_400L..3_500L, "warm card: refused at 1.5 s: $warmMs ms")
        assertTrue(enc.refusedStream(warm))
        enc.stopFor("p2"); enc.stopFor("p3"); enc.stopFor("p4")
    }

    @Test fun `a first segment inside the deadline is not refused`() = runBlocking<Unit> {
        val enc = encoder()
        mode("slow")
        val plan = planOn(null)
        val id = enc.register(plan, "p5", Long.MAX_VALUE)
        assertNotNull(enc.segment(id, "v", 0, 0), "segment 0 arrives after 0.9 s, inside 1.5 s")
        delay(1_800)
        assertFalse(enc.refusedStream(id))
        assertNull(enc.refused.reason(plan.source.path, now()))
        assertNotNull(enc.segment(id, "v", 0, 3), "the job is still running")
        enc.stopFor("p5")
    }

    @Test fun `a silent job that is not the stream's first is never refused`() = runBlocking<Unit> {
        val enc = encoder()
        val plan = planOn(null)
        val id = enc.register(plan, "p6", Long.MAX_VALUE)
        assertNotNull(enc.segment(id, "v", 0, 0), "the first start made segment 0")
        delay(300)
        mode("silent")
        val seek = async { enc.segment(id, "v", 0, 200) }
        delay(3_000)
        assertFalse(enc.refusedStream(id), "FR-2's rule holds for a seek: it waits, it is not a refusal")
        assertNull(enc.refused.reason(plan.source.path, now()))
        assertEquals(Encoder.Miss.RETRY, enc.miss(id, "v", 0, 200))
        enc.stopFor("p6")
        seek.await()
    }
}
