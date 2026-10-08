package dev.jellystructure.tv

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
        } finally {
            sh("rm -rf '$root'")
        }
    }
}
