package dev.jellystructure.tv

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R291 (FR-R291-12, found live 2026-10-09) — a rendition is written in the video's own container: MPEG-TS audio beside
 * the fMP4 video Jellyfin makes for an HEVC-capable Chromecast failed in Shaka with 3018 TRANSMUXING_FAILED.
 */
class AudioRenditionFmp4Test {
    private val src = RenditionSource("/m/film.mkv", audioOrder = 1, channels = 6, durationMs = 7_500, fmp4 = true)

    @Test
    fun `the video's container is read from Jellyfin's transcoding URL`() {
        assertTrue(segmentsAreFmp4("/videos/x/master.m3u8?VideoCodec=hevc,h264&SegmentContainer=mp4&ApiKey=k"))
        assertTrue(segmentsAreFmp4("/videos/x/master.m3u8?segmentcontainer=fmp4"))
        assertFalse(segmentsAreFmp4("/videos/x/master.m3u8?SegmentContainer=ts&VideoCodec=h264"))
        assertFalse(segmentsAreFmp4("/videos/x/master.m3u8?VideoCodec=h264"), "no SegmentContainer: MPEG-TS, Jellyfin's default")
        assertFalse(segmentsAreFmp4("/videos/x/master.m3u8?NotSegmentContainer=mp4"))
    }

    @Test
    fun `an fMP4 rendition playlist names its init segment and m4s fragments`() {
        val p = renditionPlaylist(7_500, fmp4 = true)
        assertContains(p, "#EXT-X-VERSION:7\n")
        assertContains(p, "#EXT-X-MAP:URI=\"init.mp4\"\n")
        assertEquals(listOf("0.m4s", "1.m4s", "2.m4s"), p.lines().filter { it.endsWith(".m4s") })
        assertFalse(p.contains(".ts\n"))
        assertTrue(p.indexOf("#EXT-X-MAP") < p.indexOf("#EXTINF"), "the map comes before the first segment")
        val ts = renditionPlaylist(7_500)
        assertFalse(ts.contains("EXT-X-MAP"), "an MPEG-TS rendition keeps its old form")
        assertContains(ts, "#EXT-X-VERSION:3\n")
    }

    @Test
    fun `an fMP4 job writes fragments and one init every job shares`() {
        val cmd = renditionCommand(src, "aac", 100, "/r/x")
        assertContains(cmd, "-hls_segment_type fmp4 -hls_fmp4_init_filename init.mp4 -hls_segment_options use_editlist=0 ")
        assertContains(cmd, "-hls_segment_filename '/r/x/s%d.m4s'")
        assertContains(cmd, "-start_number 100 ")
        assertContains(cmd, "-copyts -avoid_negative_ts disabled")
        assertFalse(cmd.contains("mpegts"))
        assertContains(renditionCommand(src.copy(fmp4 = false), "aac", 100, "/r/x"), "-hls_segment_type mpegts ")
    }

    @Test
    fun `a seek job's fragments are moved to the file's time`() {
        // styp, sidx (v1, timescale 48000, earliest 0), moof(mfhd, traf(tfhd, tfdt v1 = 0)), mdat — as ffmpeg 5.1 writes
        // a job started at 300 s with use_editlist=0.
        val seg = box("styp", bytes("msdh") + u32(0) + bytes("msdhmsix")) +
            box("sidx", byteArrayOf(1, 0, 0, 0) + u32(1) + u32(48_000) + u64(0) + u64(0) + u32(1) + u32(100) + u32(144_384) + u32(0x90000000.toInt())) +
            box("moof", box("mfhd", u32(0) + u32(1)) + box("traf", box("tfhd", u32(0x20000) + u32(1)) + box("tfdt", byteArrayOf(1, 0, 0, 0) + u64(0)))) +
            box("mdat", ByteArray(16) { 7 })
        val shift = 100 * RENDITION_SEGMENT_MS * 48_000 / 1000   // segment 100 = 300 s
        val out = shiftTfdt(seg, shift)
        assertEquals(seg.size, out.size)
        assertEquals(14_400_000L, readU64(out, indexOf(out, "tfdt") + 12), "tfdt = 300 s at 48 kHz")
        assertEquals(14_400_000L, readU64(out, indexOf(out, "sidx") + 20), "the sidx's earliest presentation time too")
        assertContentEquals(seg.copyOfRange(seg.size - 24, seg.size), out.copyOfRange(out.size - 24, out.size), "the media itself is untouched")
        assertContentEquals(seg, shiftTfdt(seg, 0), "a job from 0 is served as written")
    }

    @Test
    fun `a 32-bit tfdt is moved too`() {
        val seg = box("moof", box("traf", box("tfdt", byteArrayOf(0, 0, 0, 0) + u32(1_000))))
        assertEquals(1_000L + 48_000L, readU32(shiftTfdt(seg, 48_000), indexOf(seg, "tfdt") + 12))
    }

    @Test
    fun `the init's timescale comes from its mdhd`() {
        val v0 = box("moov", box("trak", box("mdia", box("mdhd", u32(0) + u32(0) + u32(0) + u32(44_100) + u32(0) + u32(0)))))
        assertEquals(44_100L, mdhdTimescale(v0))
        val v1 = box("mdhd", byteArrayOf(1, 0, 0, 0) + u64(0) + u64(0) + u32(48_000) + u64(0))
        assertEquals(48_000L, mdhdTimescale(v1))
        assertNull(mdhdTimescale(box("moov", ByteArray(8))))
    }

    private fun bytes(s: String) = s.encodeToByteArray()
    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u64(v: Long) = ByteArray(8) { (v ushr (8 * (7 - it))).toByte() }
    private fun box(type: String, body: ByteArray) = u32(8 + body.size) + bytes(type) + body
    private fun indexOf(b: ByteArray, type: String): Int {
        val t = bytes(type)
        return (0..b.size - 4).first { i -> t.indices.all { b[i + it] == t[it] } } - 4
    }
    private fun readU64(b: ByteArray, at: Int) = (0 until 8).fold(0L) { v, i -> (v shl 8) or (b[at + i].toLong() and 0xFF) }
    private fun readU32(b: ByteArray, at: Int) = (0 until 4).fold(0L) { v, i -> (v shl 8) or (b[at + i].toLong() and 0xFF) }
}
