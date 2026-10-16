package dev.jellystructure.tv

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-1432 (FR-313-7, build review item 11) — a restarted job's fMP4 on the file's own clock. */
class EncoderTimelineTest {
    private fun be(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * (n - 1 - it))).toByte() }
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return be(8L + body.size, 4) + type.encodeToByteArray() + body
    }

    /** A one-track init as ffmpeg writes it for a job that began [delayMs] into the film (movie timescale 1000). */
    private fun init(trackScale: Long, delayMs: Long?): ByteArray {
        val mvhd = box("mvhd", be(0, 4), be(0, 4), be(0, 4), be(1000, 4), be(0, 4))
        val mdhd = box("mdhd", be(0, 4), be(0, 4), be(0, 4), be(trackScale, 4), be(0, 4))
        val edts = delayMs?.let {
            box("edts", box("elst", be(0, 4), be(2, 4), be(it, 4), be(0xFFFFFFFFL, 4), be(0x10000, 4), be(5000, 4), be(0, 4), be(0x10000, 4)))
        } ?: ByteArray(0)
        return box("ftyp", "iso6".encodeToByteArray(), be(0, 4)) + box("moov", mvhd, box("trak", edts, box("mdia", mdhd)))
    }

    private fun segment(base: Long, version: Int = 1): ByteArray {
        val tfdt = if (version == 1) box("tfdt", be(0x01000000, 4), be(base, 8)) else box("tfdt", be(0, 4), be(base, 4))
        return box("styp", "msdh".encodeToByteArray(), be(0, 4)) + box("moof", box("mfhd", be(0, 4), be(1, 4)), box("traf", box("tfhd", be(0x20000, 4), be(1, 4)), tfdt)) + box("mdat", ByteArray(40))
    }

    private fun baseOf(seg: ByteArray): Long {
        val i = (0..seg.size - 4).first { seg[it] == 't'.code.toByte() && seg[it + 1] == 'f'.code.toByte() && seg[it + 2] == 'd'.code.toByte() && seg[it + 3] == 't'.code.toByte() }
        val v = seg[i + 4].toInt()
        return (0 until if (v == 1) 8 else 4).fold(0L) { a, k -> (a shl 8) or (seg[i + 8 + k].toLong() and 0xFF) }
    }

    @Test fun `the leading empty edit is the track's start in the track's own ticks`() {
        // 1 147 973 ms of a 48 kHz track, and 1 148 000 ms of a 12 288 Hz one: exactly what ffmpeg wrote in the live repro.
        assertEquals(1_147_973L * 48_000 / 1000, fmp4TrackStartTicks(init(48_000, 1_147_973)))
        assertEquals(1_148_000L * 12_288 / 1000, fmp4TrackStartTicks(init(12_288, 1_148_000)))
    }

    @Test fun `a track with no edit list or no empty edit starts at 0`() {
        assertEquals(0L, fmp4TrackStartTicks(init(48_000, null)))
        assertEquals(0L, fmp4TrackStartTicks(ByteArray(0)))
        assertEquals(0L, fmp4TrackStartTicks("not an mp4".encodeToByteArray()))
    }

    @Test fun `the edit list is hidden without moving a byte of the init`() {
        val i = init(48_000, 1_147_973)
        val out = fmp4WithoutEditList(i)
        assertEquals(i.size, out.size)
        assertEquals(0L, fmp4TrackStartTicks(out))
        assertFalse(out.decodeToString().contains("edts"))
        assertTrue(out.decodeToString().contains("free"))
        // an init with none is returned as it is
        assertContentEquals(init(48_000, null), fmp4WithoutEditList(init(48_000, null)))
    }

    @Test fun `a segment's tfdt moves by the start and nothing else changes`() {
        val seg = segment(96_256)
        val head = seg.copyOf(128)
        val ticks = 1_147_973L * 48_000 / 1000
        assertTrue(fmp4ShiftBaseTime(head, ticks))
        val shifted = head + seg.copyOfRange(128, seg.size)
        assertEquals(96_256 + ticks, baseOf(shifted))
        assertEquals(seg.size, shifted.size)
        // only the eight bytes of the time differ
        assertTrue(seg.indices.count { seg[it] != shifted[it] } in 1..8)
    }

    @Test fun `a version 0 tfdt shifts when it fits and is left alone when it does not`() {
        val head = segment(1_000, version = 0).copyOf()
        assertTrue(fmp4ShiftBaseTime(head, 5_000))
        assertEquals(6_000L, baseOf(head))
        val tooBig = segment(1_000, version = 0).copyOf()
        assertFalse(fmp4ShiftBaseTime(tooBig, 0xFFFFFFFFL))
        assertEquals(1_000L, baseOf(tooBig))
    }

    @Test fun `a head with no moof is not touched`() {
        assertFalse(fmp4ShiftBaseTime(ByteArray(0), 10))
        assertFalse(fmp4ShiftBaseTime(box("mdat", ByteArray(40)), 10))
    }
}
