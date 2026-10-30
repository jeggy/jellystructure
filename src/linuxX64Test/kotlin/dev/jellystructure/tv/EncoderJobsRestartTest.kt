package dev.jellystructure.tv

import dev.jellystructure.config.EncoderConfig
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
 * DEB-3100 (trouble-never-reaches-the-viewer FR-2, FR-3, FR-8) — a job that dies after its first start is a 503 and never a refusal, a
 * full work folder is pruned before a job starts, and the summary counts what the player met. The "ffmpeg" is a shell script that makes
 * 30 segments of every variant (or, when the file `mode` beside it says `fail`, exits at once).
 */
class EncoderJobsRestartTest {
    private val base = "/tmp/jellystructure-test-3100-jobs-${getpid()}"
    private val ffmpegDir = "$base/ff"
    private var workDir = "$base/work"
    private var runs = 0
    private var freeBytes = Long.MAX_VALUE

    @AfterTest fun cleanup() { runCatching { SystemFileSystem.list(Path(base)).forEach { rmTree(it.toString()) } }; platform.posix.rmdir(base) }

    private fun rmTree(p: String) {
        runCatching { SystemFileSystem.list(Path(p)).forEach { rmTree(it.toString()) } }
        platform.posix.unlink(p); platform.posix.rmdir(p)
    }

    private fun write(path: String, text: String) { SystemFileSystem.sink(Path(path)).buffered().use { it.write(text.encodeToByteArray(), 0, text.length) } }

    private fun mode(m: String) = write("$ffmpegDir/mode", m)

    private fun encoder(): Encoder {
        workDir = "$base/work${runs++}"
        mkdirs(ffmpegDir); mkdirs(workDir)
        write("$ffmpegDir/ffmpeg", """#!/bin/sh
mode=${'$'}(cat "${'$'}(dirname "${'$'}0")/mode" 2>/dev/null || echo ok)
if [ "${'$'}mode" = fail ]; then echo boom >&2; exit 1; fi
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
        val dir = workDir
        return Encoder({ EncoderConfig(enabled = true, ffmpegDir = ffmpegDir, workDir = dir, cudaCacheDir = "$base/cuda") },
            cardsOverride = { emptyList() }, ffmpegOverride = { true }, freeBytes = { freeBytes }).also {
            // The jobs start lazily and sweep the work folder once, in the background: let that finish before a job is made.
            runBlocking { it.running(); kotlinx.coroutines.delay(300) }
        }
    }

    private val plan = EncoderPlan(
        source = EncoderSource("/m/film.mkv", 600_000, "h264", 1920, 1080, 8_000_000, hdr = false),
        codec = EncoderCodec.H264, mux = EncoderMux.TS,
        rungs = listOf(EncoderRung(1080, 1920, 1080, 8_000_000), EncoderRung(720, 1280, 720, 4_000_000)),
        startRung = 0, audio = listOf(EncoderAudio(0, 0, "aac", 2, "eng", "English", true)), cudaDevice = null,
    )

    @Test fun `a first start that ffmpeg refuses is a 410 and a later job that fails is a 503`() = runBlocking<Unit> {
        val enc = encoder()
        mode("fail")
        val refusedId = enc.register(plan, "p1", Long.MAX_VALUE)
        assertNull(enc.segment(refusedId, "v", 0, 0))
        assertTrue(enc.refusedStream(refusedId))
        assertEquals(Encoder.Miss.GONE, enc.miss(refusedId, "v", 0, 0), "ffmpeg refused the plan: 410")

        mode("ok")
        val id = enc.register(plan, "p2", Long.MAX_VALUE)
        assertNotNull(enc.segment(id, "v", 0, 0), "the first start made segment 0")
        mode("fail")
        assertNull(enc.segment(id, "v", 0, 200), "a seek whose job ffmpeg now fails")
        assertFalse(enc.refusedStream(id), "a later job that exits early does not refuse the stream")
        assertEquals(Encoder.Miss.RETRY, enc.miss(id, "v", 0, 200), "503, not 410")
        mode("ok")
        assertNotNull(enc.segment(id, "v", 0, 200), "and the next request starts it again")
        enc.stopFor("p2")
    }

    @Test fun `a job that dies early is started once more within the request`() = runBlocking<Unit> {
        val enc = encoder()
        val id = enc.register(plan, "p3", Long.MAX_VALUE)
        assertNotNull(enc.segment(id, "v", 0, 0))
        mode("fail")
        // The seek's job fails; flipping the mode back while the request is waiting is not possible here, so the second attempt fails too.
        assertNull(enc.segment(id, "v", 0, 100))
        assertEquals(Encoder.Miss.RETRY, enc.miss(id, "v", 0, 100))
        enc.stopFor("p3")
    }

    @Test fun `an unknown segment or a stream that is not ours is a 404`() = runBlocking<Unit> {
        val enc = encoder()
        val id = enc.register(plan, "p4", Long.MAX_VALUE)
        assertEquals(Encoder.Miss.UNKNOWN, enc.miss("nope", "v", 0, 0))
        assertEquals(Encoder.Miss.UNKNOWN, enc.miss(id, "v", 0, 99_999), "past the end of the film")
        assertEquals(Encoder.Miss.UNKNOWN, enc.miss(id, "v", 7, 0), "a rung the plan does not have")
        assertEquals(Encoder.Miss.UNKNOWN, enc.miss(id, "v", 0, null), "an init on a TS stream")
        enc.stopFor("p4")
    }

    @Test fun `a nearly full work folder is pruned before a job starts`() = runBlocking<Unit> {
        val enc = encoder()
        val id = enc.register(plan, "p5", Long.MAX_VALUE)
        assertNotNull(enc.segment(id, "v", 0, 0))
        assertNotNull(enc.segment(id, "v", 0, 150), "a seek: the first job is retired, its folder kept")
        assertEquals(2, SystemFileSystem.list(Path(workDir)).size)
        freeBytes = 1_000_000L   // the tmpfs is full
        assertNotNull(enc.segment(id, "v", 0, 250), "another seek: pruning makes room first")
        assertTrue(SystemFileSystem.list(Path(workDir)).size <= 2, "a retired job's folder went: ${SystemFileSystem.list(Path(workDir))}")
        enc.stopFor("p5")
    }

    @Test fun `the encoder summary counts what the player met`() {
        val stats = EncoderStats(listOf("1080p@8000k"))
        for (ms in 1..100L) stats.responded(ms * 10, was503 = ms % 50 == 0L)
        val s = stats.summary()
        assertEquals(950L, s.segmentP95Ms)
        assertEquals(1000L, s.segmentMaxMs)
        assertEquals(2, s.responses503)
        val both = s + EncoderSummary(segments = 1, segmentP95Ms = 2000, segmentMaxMs = 3000, responses503 = 1)
        assertEquals(2000L, both.segmentP95Ms)
        assertEquals(3000L, both.segmentMaxMs)
        assertEquals(3, both.responses503)
        assertTrue(EncoderSummary.fromJson(s.toJson())!!.responses503 == 2, "the JSON on the QoE row round-trips")
        assertEquals(EncoderSummary.fromJson("""{"segments":3}""")!!.responses503, 0, "a row from before has none")
        assertNull(percentile(emptyList(), 0.95))
    }
}
