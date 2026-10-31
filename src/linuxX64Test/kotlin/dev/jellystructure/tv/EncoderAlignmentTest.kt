package dev.jellystructure.tv

import dev.jellystructure.io.FileIo
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import kotlin.math.abs
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 313 (Test 9) — a real encode, on the CPU (libx264, as CI has no GPU): segment k of every rung starts at the same
 * frame, on the file's own 2 s grid, and a job restarted at a seek makes segments that start exactly where the first
 * job's did — so a player switches rung, or crosses from kept segments into new ones, without a gap. Skipped when the
 * machine has no ffmpeg.
 */
@OptIn(ExperimentalForeignApi::class)
class EncoderAlignmentTest {
    private fun sh(cmd: String): String = memScoped {
        val pipe = popen(cmd, "r") ?: return@memScoped ""
        val buf = allocArray<ByteVar>(1024)
        val sb = StringBuilder()
        while (fgets(buf, 1024, pipe) != null) sb.append(buf.toKString())
        pclose(pipe)
        sb.toString()
    }

    private fun firstPts(file: String): Double =
        sh("ffprobe -v error -select_streams v:0 -show_entries packet=pts_time -read_intervals '%+#1' -of csv=p=0 '$file' 2>/dev/null").trim().lines().first().substringBefore(',').trim().toDouble()

    private fun tfdtOf(head: ByteArray): Long {
        val i = (0..head.size - 4).first { head[it] == 't'.code.toByte() && head[it + 1] == 'f'.code.toByte() && head[it + 2] == 'd'.code.toByte() && head[it + 3] == 't'.code.toByte() }
        return (0 until 8).fold(0L) { a, k -> (a shl 8) or (head[i + 8 + k].toLong() and 0xFF) }
    }

    @Test fun `every rung and a restarted job share segment boundaries`() {
        val probe = sh("command -v ffmpeg; command -v ffprobe")
        if (probe.lines().count { it.endsWith("/ffmpeg") || it.endsWith("/ffprobe") } < 2) return
        val root = "/tmp/js-encoder-align-${secureHexId().take(8)}"
        sh("mkdir -p $root")
        try {
            val input = "$root/in.mkv"
            sh("ffmpeg -nostdin -v error -f lavfi -i testsrc2=size=1280x720:rate=24 -f lavfi -i sine=f=440:sample_rate=48000 -t 16 " +
                "-c:v libx264 -preset ultrafast -g 48 -pix_fmt yuv420p -c:a aac -shortest -y '$input'")
            assertEquals(0, access(input, F_OK))
            val src = EncoderSource(input, 16_000, "h264", 1280, 720, 4_000_000, hdr = false)
            val rungs = listOf(EncoderRung(720, 1280, 720, 2_000_000), EncoderRung(480, 854, 480, 800_000))
            val plan = EncoderPlan(src, EncoderCodec.H264, EncoderMux.TS, rungs, 0, listOf(EncoderAudio(0, 0, "aac", 2, null, null, true)), cudaDevice = null)
            for ((dir, start) in listOf("$root/a" to 0, "$root/b" to 3)) {
                sh("mkdir -p $dir/0 $dir/1 $dir/2")
                sh(encoderCommand(plan, start, dir, "ffmpeg") + " >/dev/null 2>$dir/log")
            }
            for (k in 3..6) {
                val a0 = firstPts("$root/a/0/s$k.ts")
                val a1 = firstPts("$root/a/1/s$k.ts")
                // The restarted job (from segment 3) numbers its files from 0: segment k is its file k - 3.
                val b0 = firstPts("$root/b/0/s${k - 3}.ts")
                val b1 = firstPts("$root/b/1/s${k - 3}.ts")
                assertTrue(abs(a0 - a1) < 0.001, "segment $k: rungs differ ($a0 vs $a1)")
                assertTrue(abs(a0 - b0) < 0.001 && abs(a0 - b1) < 0.001, "segment $k: the restarted job differs ($a0 vs $b0/$b1)")
                if (k > 3) assertTrue(abs(a0 - firstPts("$root/a/0/s${k - 1}.ts") - 2.0) < 0.05, "segment $k is not 2 s after ${k - 1}")
            }
            // The audio rendition is cut on the same grid and exists for the same segments.
            assertEquals(0, access("$root/a/2/s6.ts", F_OK))
            // fMP4: an init segment per variant and the segments beside it.
            val fmp4 = plan.copy(mux = EncoderMux.FMP4)
            sh("mkdir -p $root/c/0 $root/c/1 $root/c/2")
            sh(encoderCommand(fmp4, 0, "$root/c", "ffmpeg") + " >/dev/null 2>$root/c/log")
            assertTrue(sh("ls $root/c/0").lines().any { it.startsWith("init") && it.endsWith(".mp4") }, sh("cat $root/c/log"))
            assertEquals(0, access("$root/c/0/s2.m4s", F_OK))
            assertEquals(0, access("$root/c/1/s2.m4s", F_OK))
            // DEB-1432 — fMP4 from a restarted job is on the file's clock too: ffmpeg writes that job's `tfdt` from 0 and
            // keeps where it began in the init's edit list, which hls.js ignores. Served through the timeline fix, segment k
            // of the restarted job (file k − 3) starts where segment k of the first job does, for the video and the audio.
            sh("mkdir -p $root/d/0 $root/d/1 $root/d/2")
            sh(encoderCommand(fmp4, 3, "$root/d", "ffmpeg") + " >/dev/null 2>$root/d/log")
            for (v in 0..2) {
                val initC = FileIo.readBytes(Path(sh("ls $root/c/$v/init*.mp4").trim()))
                val initD = FileIo.readBytes(Path(sh("ls $root/d/$v/init*.mp4").trim()))
                val ticks = fmp4TrackStartTicks(initD)
                assertTrue(fmp4TrackStartTicks(fmp4WithoutEditList(initD)) == 0L && fmp4TrackStartTicks(fmp4WithoutEditList(initC)) == 0L, "variant $v: an edit list is left")
                val scale = if (v == 2) 48_000.0 else 12_288.0
                if (v < 2) assertEquals(3 * 2 * 12_288L, ticks, "variant $v: the restarted job's start")
                for (k in 3..5) {
                    val first = FileIo.readHead(Path("$root/c/$v/s$k.m4s"), 2048)
                    val again = FileIo.readHead(Path("$root/d/$v/s${k - 3}.m4s"), 2048)
                    assertTrue(fmp4ShiftBaseTime(first, fmp4TrackStartTicks(initC)) && fmp4ShiftBaseTime(again, ticks))
                    // Each job cuts the audio on AAC frames of its own, starting where its decoder's first sample is: the two can sit
                    // a few frames (measured 59 ms) apart at the seam — the same as with the edit list, and inaudible; the video is exact.
                    val tolerance = if (v == 2) 0.1 else 0.001
                    assertTrue(abs(tfdtOf(first) - tfdtOf(again)) / scale < tolerance, "variant $v segment $k: ${tfdtOf(first) / scale} s vs ${tfdtOf(again) / scale} s")
                    assertTrue(abs(tfdtOf(again) / scale - k * 2.0) < 0.1, "variant $v segment $k is not at ${k * 2} s: ${tfdtOf(again) / scale}")
                }
            }
        } finally {
            sh("rm -rf '$root'")
        }
    }

    /** The bytes of [file]'s first audio stream, as a raw E-AC-3 elementary stream (no container, no timestamps). */
    private fun eac3Bytes(input: String, out: String): ByteArray {
        sh("ffmpeg -nostdin -v error -i '$input' -vn -map 0:a:0 -c:a copy -f eac3 -y '$out' 2>/dev/null")
        return FileIo.readBytes(Path(out))
    }

    private fun indexOfRun(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            if (hay[i] != needle[0]) continue
            for (k in 1 until needle.size) if (hay[i + k] != needle[k]) continue@outer
            return i
        }
        return -1
    }

    /**
     * DEB-3101 (FR-313-5b) — an E-AC-3 input copied: the audio rendition's segments, joined, hold the file's own E-AC-3
     * frames byte for byte, in TS and in fMP4, from the first job and from one restarted at a seek, and a copied
     * rendition is cut on the same 2 s grid as the video.
     */
    @Test fun `an E-AC-3 track copied keeps the file's bytes in TS and fMP4 on the same grid and after a restarted job`() {
        val probe = sh("command -v ffmpeg; command -v ffprobe")
        if (probe.lines().count { it.endsWith("/ffmpeg") || it.endsWith("/ffprobe") } < 2) return
        if (!sh("ffmpeg -hide_banner -encoders 2>/dev/null").contains(" eac3 ")) return
        val root = "/tmp/js-encoder-eac3-${secureHexId().take(8)}"
        sh("mkdir -p $root")
        try {
            val input = "$root/in.mkv"
            sh("ffmpeg -nostdin -v error -f lavfi -i testsrc2=size=1280x720:rate=24 -f lavfi -i sine=f=440:sample_rate=48000 -t 16 " +
                "-c:v libx264 -preset ultrafast -g 48 -pix_fmt yuv420p -ac 6 -c:a eac3 -b:a 640k -shortest -y '$input'")
            assertEquals(0, access(input, F_OK))
            val whole = eac3Bytes(input, "$root/in.eac3")
            assertTrue(whole.size > 100_000, "the input has an E-AC-3 track (${whole.size} bytes)")
            val src = EncoderSource(input, 16_000, "h264", 1280, 720, 4_000_000, hdr = false)
            val rungs = listOf(EncoderRung(720, 1280, 720, 2_000_000), EncoderRung(480, 854, 480, 800_000))
            val audio = EncoderAudio(0, 0, "eac3", 6, null, null, true, copy = true, sourceBps = 640_000L)
            val plan = EncoderPlan(src, EncoderCodec.H264, EncoderMux.TS, rungs, 0, listOf(audio), cudaDevice = null)
            for (mux in listOf(EncoderMux.TS, EncoderMux.FMP4)) {
                val p = plan.copy(mux = mux)
                val ext = if (mux == EncoderMux.TS) "ts" else "m4s"
                for ((name, start) in listOf("whole" to 0, "restart" to 3)) {
                    val dir = "$root/${mux.name}-$name"
                    sh("mkdir -p $dir/0 $dir/1 $dir/2")
                    sh(encoderCommand(p, start, dir, "ffmpeg") + " >/dev/null 2>$dir/log")
                    val segs = (0..7 - start).filter { access("$dir/2/s$it.$ext", F_OK) == 0 }
                    assertTrue(segs.size >= 7 - start, "${mux.name} $name: the audio rendition's segments (${segs.size}); " + sh("tail -5 $dir/log"))
                    if (mux == EncoderMux.TS) {
                        // The copied rendition's first segment starts where the video's does (a seek must not leave it a cluster early).
                        fun minPts(f: String) = sh("ffprobe -v error -show_entries packet=pts_time -of csv=p=0 '$f' 2>/dev/null").lines().mapNotNull { it.trim().trimEnd(',').toDoubleOrNull() }.min()
                        assertTrue(abs(minPts("$dir/2/s0.ts") - minPts("$dir/0/s0.ts")) < 0.1, "TS $name: audio starts at ${minPts("$dir/2/s0.ts")} s, video at ${minPts("$dir/0/s0.ts")} s")
                    }
                    val joined = "$dir/audio.joined"
                    val init = if (mux == EncoderMux.FMP4) "$(ls $dir/2/init*.mp4) " else ""
                    sh("cat $init" + segs.joinToString(" ") { "$dir/2/s$it.$ext" } + " > $joined")
                    // ffprobe lists a stream once per joined segment's PMT in some builds: the first line is the stream.
                    val shape = sh("ffprobe -v error -select_streams a:0 -show_entries stream=codec_name,channels -of csv=p=0 '$joined' 2>&1").trim().lines().first().trim().trimEnd(',')
                    assertEquals("eac3,6", shape, "${mux.name} $name: the rendition is still 5.1 E-AC-3")
                    val got = eac3Bytes(joined, "$dir/audio.eac3")
                    val at = indexOfRun(whole, got.copyOf(4096))
                    assertTrue(at >= 0, "${mux.name} $name: the copied frames are the file's own frames")
                    // The rendition is a contiguous run of the file's own bytes: from the start for a whole job, from 6 s on after a restart.
                    assertTrue(at + got.size <= whole.size && (0 until got.size).all { whole[at + it] == got[it] }, "${mux.name} $name: not byte-identical")
                    // Measured: ffmpeg's muxer leaves out the first frame (2560 bytes = 32 ms) of the track at the very start of a job.
                    if (start == 0) assertTrue(at <= 2 * 2560, "${mux.name}: the first job starts at the file's first frames (byte $at)")
                    else assertTrue(at in 400_000..520_000, "${mux.name}: the restarted job starts near 6 s (byte $at of ${whole.size})")
                }
            }
        } finally {
            sh("rm -rf '$root'")
        }
    }
}
