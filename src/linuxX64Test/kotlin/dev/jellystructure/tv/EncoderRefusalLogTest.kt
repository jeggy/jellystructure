package dev.jellystructure.tv

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** DEB-3093 (FR-8) — a refusal keeps its reason: the exit code decoded, and the tail of ffmpeg's own log. */
class EncoderRefusalLogTest {
    @Test fun waitStatusIsDecoded() {
        assertEquals("exit 234", ffmpegExit(59904))
        assertEquals("exit 1", ffmpegExit(256))
        assertEquals("signal 9", ffmpegExit(9))
    }

    @Test fun logTailKeepsTheLastTwentyLinesWithoutProgress() {
        val text = (1..30).joinToString("\n") { "line $it" + if (it % 10 == 0) "\n" else "" } +
            "\nframe=  10 fps=0.0 q=0.0 size=0kB\rframe=  20 fps=0.0\rsize=   0kB time=00:00:01\n"
        val tail = ffmpegLogTail(text)
        assertEquals((11..30).map { "line $it" }, tail)
    }

    @Test fun refusalReasonReadsTheExitCode() {
        val r = RefusedSources(ttlMs = 1_000)
        r.markRefused("/m/a.mkv", EncoderCodec.HEVC, 59904, nowMs = 0)
        val why = r.reason("/m/a.mkv", 1)!!
        assertContains(why, "exit 234")
        assertFalse(why.contains("59904"))
    }
}
