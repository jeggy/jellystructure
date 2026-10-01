package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R351 — the receiver's H.264 / bitrate / channel declaration from the device's answers. */
class CastDecodeProbeTest {
    @Test fun a_720p_decoder_declares_720p_at_its_level() {
        // A Nest Hub-like device: H.264 up to 1280 × 720, level 4.1.
        val a = CastDecodeProbe.decide(h264 = { it.width <= 1280 && it.level <= 41 }, bitrate = null, sixChannels = null)
        assertEquals(1280, a.maxWidth); assertEquals(720, a.maxHeight); assertEquals(41, a.maxLevel)
        assertEquals(0, a.maxBitrate); assertEquals(6, a.maxAudioChannels)
    }

    @Test fun a_4k_device_keeps_4k_and_no_ceiling() {
        val a = CastDecodeProbe.decide(h264 = { true }, bitrate = { _, _ -> true }, sixChannels = { true })
        assertEquals(3840, a.maxWidth); assertEquals(51, a.maxLevel); assertEquals(0, a.maxBitrate); assertEquals(6, a.maxAudioChannels)
    }

    @Test fun a_1080p30_dongle_gets_level_41() {
        val a = CastDecodeProbe.decide(h264 = { it.width <= 1920 && it.level <= 41 }, bitrate = null, sixChannels = null)
        assertEquals(1920, a.maxWidth); assertEquals(41, a.maxLevel)
    }

    @Test fun nothing_answered_keeps_the_old_declaration() {
        val a = CastDecodeProbe.decide(h264 = { false }, bitrate = { _, _ -> true }, sixChannels = null)
        assertEquals(1920, a.maxWidth); assertEquals(1080, a.maxHeight); assertEquals(0, a.maxLevel); assertEquals(0, a.maxBitrate)
    }

    @Test fun a_throwing_probe_counts_as_no() {
        val a = CastDecodeProbe.decide(h264 = { if (it.width > 1280) error("boom") else true }, bitrate = null, sixChannels = { error("x") })
        assertEquals(1280, a.maxWidth); assertEquals(6, a.maxAudioChannels)
    }

    @Test fun the_bitrate_ceiling_is_the_highest_passing_step() {
        val a = CastDecodeProbe.decide(h264 = { it.width <= 1280 }, bitrate = { _, bps -> bps <= 20_000_000 }, sixChannels = { false })
        assertEquals(20_000_000, a.maxBitrate); assertEquals(2, a.maxAudioChannels)
        val none = CastDecodeProbe.decide(h264 = { true }, bitrate = { _, _ -> false }, sixChannels = null)
        assertEquals(0, none.maxBitrate)
    }

    @Test fun the_extended_type_is_shakas_shape() {
        assertEquals(
            "video/mp4; codecs=\"avc1.640029\"; width=1280; height=720; framerate=30; bitrate=20000000",
            CastDecodeProbe.extendedType("video/mp4", "avc1.640029", 1280, 720, 30, 20_000_000),
        )
        assertEquals("audio/mp4; codecs=\"mp4a.40.2\"; channels=6", CastDecodeProbe.extendedType("audio/mp4", "mp4a.40.2", channels = 6))
    }

    @Test fun the_stream_summary_never_carries_a_token() {
        val s = CastDecodeProbe.streamSummary(
            "/videos/abc/master.m3u8?DeviceId=x&MediaSourceId=y&VideoCodec=h264&AudioCodec=aac&ApiKey=SECRET&MaxWidth=1280" +
                "&MaxHeight=720&TranscodeReasons=VideoResolutionNotSupported&api_key=SECRET2",
        )
        assertTrue(s.startsWith("master.m3u8"))
        assertTrue("MaxWidth=1280" in s); assertTrue("TranscodeReasons=VideoResolutionNotSupported" in s)
        assertFalse("SECRET" in s); assertFalse("DeviceId" in s)
    }

    /** R351 (FR-R351-10) — the ticket line says the channel limit the stream was made with. */
    @Test fun the_stream_summary_carries_the_channel_limit() {
        val s = CastDecodeProbe.streamSummary(
            "/videos/abc/master.m3u8?VideoCodec=h264&AudioCodec=aac&MaxAudioChannels=2&TranscodingMaxAudioChannels=2&aac-audiochannels=2&ApiKey=SECRET",
        )
        assertTrue(" MaxAudioChannels=2" in s); assertTrue("TranscodingMaxAudioChannels=2" in s); assertTrue("aac-audiochannels=2" in s)
        assertFalse("SECRET" in s)
    }
}
