package dev.jellystructure.tv

import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.ClientCapabilities
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 313 — the encoder's rules: rungs, codec, the command, the master, the budget, the fallback (FR-313-8/-12). */
class EncoderPlanTest {
    private val hdr4k = EncoderSource("/m/film.mkv", 7_200_000, "hevc", 3840, 2160, 50_000_000, hdr = true)
    private val scope2_4 = EncoderSource("/m/wide.mkv", 7_200_000, "hevc", 3840, 1600, 40_000_000, hdr = true)

    @Test fun `the top rung is capped by its own output — not the source`() {
        // The 2026-10-06 cast's 24.4 Mbps 1080p H.264 top rung is the case this removes.
        val h264 = encoderRungs(EncoderCodec.H264, 3840, 1606, 24_400_000, null)
        assertEquals(1080, h264[0].boxHeight)
        assertEquals(12_000_000L, h264[0].videoBps)
        assertEquals(1920 to 802, h264[0].width to h264[0].height)
        val hevc = encoderRungs(EncoderCodec.HEVC, 3840, 2160, 50_000_000, null)
        assertEquals(2160, hevc[0].boxHeight)
        assertEquals(20_000_000L, hevc[0].videoBps)
        assertEquals(listOf(2160, 1080, 720, 480), hevc.map { it.boxHeight })
        assertTrue(hevc.size <= ENCODER_MAX_RUNGS)
    }

    @Test fun `a cropped 4K film fills the 2160p box by width and keeps its aspect`() {
        val r = encoderRungs(EncoderCodec.HEVC, scope2_4.width, scope2_4.height, scope2_4.videoBps, null)
        assertEquals(2160, r[0].boxHeight)
        assertEquals(3840 to 1600, r[0].width to r[0].height)
        assertEquals(1920 to 800, r[1].width to r[1].height)
    }

    @Test fun `the source's own bitrate and the decode ceiling lower the top`() {
        val low = encoderRungs(EncoderCodec.H264, 1920, 1080, 6_000_000, null)
        assertEquals(6_000_000L, low[0].videoBps)
        assertTrue(low.drop(1).all { it.videoBps < 6_000_000 * 0.8 })
        val capped = encoderRungs(EncoderCodec.H264, 1920, 1080, 30_000_000, 5_000_000)
        assertEquals(4_500_000L, capped[0].videoBps)
        assertTrue(capped.all { it.videoBps <= 4_500_000 })
    }

    @Test fun `fewer rungs keep the start — then below — then above`() {
        val r = encoderRungs(EncoderCodec.HEVC, 3840, 2160, 50_000_000, null)
        assertEquals(listOf(1080, 720), trimRungs(r, start = 1, keep = 2).map { it.boxHeight })
        assertEquals(listOf(2160, 1080, 720), trimRungs(r, start = 1, keep = 3).map { it.boxHeight })
        assertEquals(listOf(2160), trimRungs(r, start = 0, keep = 1).map { it.boxHeight })
    }

    @Test fun `the start rung is the best inside the measured budget`() {
        val r = encoderRungs(EncoderCodec.H264, 1920, 1080, 30_000_000, null)
        assertEquals(0, startRung(r, null))
        assertEquals(r.indexOfFirst { it.videoBps <= 10_000_000 }, startRung(r, 10_000_000))
        assertEquals(r.lastIndex, startRung(r, 100_000))
    }

    @Test fun `the codec family follows the device and the source's HDR form`() {
        assertEquals(EncoderCodec.HEVC, encoderCodecFor(true, listOf("hevc", "h264"), supportsHdr10 = true, supportsHlg = false, source = hdr4k))
        assertEquals(EncoderCodec.H264, encoderCodecFor(true, listOf("hevc"), supportsHdr10 = false, supportsHlg = false, source = hdr4k))
        assertEquals(EncoderCodec.H264, encoderCodecFor(false, listOf("hevc"), supportsHdr10 = true, supportsHlg = true, source = hdr4k))
        assertEquals(EncoderCodec.HEVC, encoderCodecFor(true, listOf("hevc"), supportsHdr10 = false, supportsHlg = false, source = hdr4k.copy(hdr = false)))
    }

    @Test fun `codec strings are exact per rung`() {
        assertEquals("avc1.640029", videoCodecString(EncoderCodec.H264, 1080, false))
        assertEquals("avc1.64001f", videoCodecString(EncoderCodec.H264, 720, false))
        assertEquals("avc1.64001e", videoCodecString(EncoderCodec.H264, 480, false))
        assertEquals("hvc1.2.4.L150.B0", videoCodecString(EncoderCodec.HEVC, 2160, true))
        assertEquals("hvc1.2.4.L123.B0", videoCodecString(EncoderCodec.HEVC, 1080, true))
        assertEquals("hvc1.1.6.L93.B0", videoCodecString(EncoderCodec.HEVC, 720, false))
        assertEquals("ec-3", audioCodecString("eac3"))
        assertEquals("mp4a.40.2", audioCodecString("aac"))
    }

    private fun plan(codec: EncoderCodec = EncoderCodec.HEVC, mux: EncoderMux = EncoderMux.FMP4, start: Int = 1, gpu: Int? = 0, burn: Int? = null, src: EncoderSource = hdr4k) =
        EncoderPlan(src, codec, mux, encoderRungs(codec, src.width, src.height, src.videoBps, null), start,
            listOf(EncoderAudio(0, 0, "aac", 6, "eng", "English 5.1", true), EncoderAudio(1, 2, "aac", 2, "dan", "Dansk", false)), burn, gpu)

    @Test fun `the master lists one codec family — the start rung first — and every audio rendition`() {
        val m = encoderMaster(plan())
        val infs = m.lines().filter { it.startsWith("#EXT-X-STREAM-INF:") }
        assertEquals(4, infs.size)
        assertTrue(infs.all { it.contains("CODECS=\"hvc1.") && it.contains("VIDEO-RANGE=PQ") && it.contains("AUDIO=\"aud\"") && it.contains("CLOSED-CAPTIONS=NONE") })
        assertContains(infs[0], "RESOLUTION=1920x1080")       // the start rung (index 1) is listed first
        assertEquals("v/1/main.m3u8", m.lines()[m.lines().indexOf(infs[0]) + 1])
        assertContains(m, "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"a0 English 5.1\",LANGUAGE=\"eng\",DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"6\",URI=\"a/0/main.m3u8\"")
        assertContains(m, "NAME=\"a1 Dansk\",LANGUAGE=\"dan\",DEFAULT=NO,AUTOSELECT=NO,CHANNELS=\"2\",URI=\"a/1/main.m3u8\"")
        assertContains(m, "#EXT-X-VERSION:7")
        val sdr = encoderMaster(plan(EncoderCodec.H264, EncoderMux.TS, start = 0))
        assertTrue(sdr.lines().filter { it.startsWith("#EXT-X-STREAM-INF:") }.all { it.contains("avc1.") && it.contains("VIDEO-RANGE=SDR") })
    }

    @Test fun `a variant playlist covers the whole file in 2 s segments`() {
        val p = encoderPlaylist(7_001, EncoderMux.FMP4)
        assertContains(p, "#EXT-X-MAP:URI=\"init.mp4\"")
        assertEquals(listOf("0.m4s", "1.m4s", "2.m4s", "3.m4s"), p.lines().filter { it.endsWith(".m4s") })
        assertContains(p, "#EXTINF:1.001,\n3.m4s")
        assertFalse(encoderPlaylist(4_000, EncoderMux.TS).contains("EXT-X-MAP"))
    }

    @Test fun `the command decodes once — tone-maps once — forces keyframes on the file's grid and maps the file's audio`() {
        val sdr = encoderCommand(plan(EncoderCodec.H264, start = 0), startSegment = 30, dir = "/w/x", ffmpeg = "/ff")
        assertTrue(sdr.startsWith("/ff -nostdin"))
        assertContains(sdr, "-probesize 5M -analyzeduration 5M")
        assertContains(sdr, "-init_hw_device cuda=gpu:0")
        assertContains(sdr, "-ss 60.000 -i '/m/film.mkv'")
        assertEquals(1, Regex("tonemap_cuda").findAll(sdr).count())
        assertContains(sdr, "split=4[t0][t1][t2][t3]")
        assertContains(sdr, "-force_key_frames 'expr:gte(t,n_forced*2)'")
        assertContains(sdr, "-copyts")
        assertContains(sdr, "-map 0:a:0 -map 0:a:2")
        assertContains(sdr, "-c:v h264_nvenc")
        assertContains(sdr, "-start_number 0")
        assertContains(sdr, "-var_stream_map 'v:0 v:1 v:2 v:3 a:0 a:1'")
        assertContains(sdr, "-hls_segment_type fmp4")
        assertContains(sdr, "-color_trc bt709")
        val hdr = encoderCommand(plan(EncoderCodec.HEVC), startSegment = 0, dir = "/w/x")
        assertFalse(hdr.contains("tonemap_cuda"))
        assertContains(hdr, "-tag:v hvc1 -profile:v main10")
        assertContains(hdr, "-color_trc smpte2084")
        assertContains(hdr, "-level:v:0 5.0")
        val ts = encoderCommand(plan(EncoderCodec.H264, EncoderMux.TS, start = 0), startSegment = 0, dir = "/w/x")
        assertContains(ts, "-hls_segment_type mpegts")
        assertContains(ts, "s%d.ts")
    }

    @Test fun `a burned-in image subtitle is composited once — before the split`() {
        val c = encoderCommand(plan(EncoderCodec.H264, burn = 1, start = 0), startSegment = 0, dir = "/w/x")
        assertEquals(1, Regex("overlay_cuda").findAll(c).count())
        assertTrue(c.indexOf("overlay_cuda") < c.indexOf("split="))
        assertContains(c, "[0:s:1]")
    }

    @Test fun `the budget fills the P4000 — uses the consumer card within its cap — then gives fewer rungs`() {
        val p4000 = EncoderCard(0, "Quadro P4000", null, 0)
        val rtx = EncoderCard(1, "NVIDIA GeForce RTX 2060 SUPER", 8, 2)
        assertEquals(EncoderPlacement(0, 4), placeJob(listOf(p4000, rtx), emptyMap(), EncoderCodec.HEVC, 2160, 4))
        // The P4000 already carries two 4K HEVC ladders: the next goes to the 2060 SUPER (2 live + 4 ≤ 8 − 2).
        assertEquals(EncoderPlacement(1, 4), placeJob(listOf(p4000, rtx), mapOf(0 to 1.0), EncoderCodec.HEVC, 2160, 4))
        // Both full: fewer rungs before nothing.
        val busyRtx = rtx.copy(liveSessions = 5)
        assertEquals(EncoderPlacement(1, 1), placeJob(listOf(p4000, busyRtx), mapOf(0 to 1.0), EncoderCodec.HEVC, 2160, 4))
        assertNull(placeJob(listOf(p4000, rtx.copy(liveSessions = 6)), mapOf(0 to 1.0, 1 to 1.0), EncoderCodec.HEVC, 2160, 4))
        assertEquals(listOf(null, 8), parseNvidiaSmi("0, Quadro P4000, 0\n1, NVIDIA GeForce RTX 2060 SUPER, 3\n").map { it.sessionCap })
    }

    @Test fun `each fallback to Jellyfin has its reason`() {
        val card = listOf(EncoderCard(0, "Quadro P4000", null, 0))
        assertEquals("encoder off", encoderDecision(false, true, card, hdr4k, false))
        assertEquals("no GPU in the container", encoderDecision(true, true, emptyList(), hdr4k, false))
        assertEquals("no jellyfin-ffmpeg", encoderDecision(true, false, card, hdr4k, false))
        assertEquals("live TV or audio", encoderDecision(true, true, card, hdr4k, true))
        assertNotNull(encoderDecision(true, true, card, null, false))
        assertContains(encoderDecision(true, true, card, hdr4k.copy(videoCodec = "vc1"), false)!!, "vc1")
        assertContains(encoderDecision(true, true, card, hdr4k.copy(dolbyVisionProfile5 = true), false)!!, "profile 5")
        assertNull(encoderDecision(true, true, card, hdr4k, false))
    }

    private val tracks = listOf(
        Track(0, "v:0", TrackKind.VIDEO, "hevc", null, null, true, false, width = 3840, height = 2160, videoRange = "HDR", videoBitrate = 50_000_000, durationMs = 7_200_000),
        Track(1, "a:0", TrackKind.AUDIO, "truehd", "eng", null, true, false),
        Track(2, "a:1", TrackKind.AUDIO, "ac3", "dan", null, false, false),
    )
    private val audio = listOf(AudioTrack(1, "eng", "English", "truehd", 8, true), AudioTrack(2, "dan", "Dansk", "ac3", 6, false))
    private fun encoder(cards: List<EncoderCard> = listOf(EncoderCard(0, "Quadro P4000", null, 0)), enabled: Boolean = true) =
        Encoder({ EncoderConfig(enabled = enabled, workDir = "/tmp/js-encoder-test") }, cardsOverride = { cards }, ffmpegOverride = { true })

    @Test fun `a BRAVIA-like device gets HEVC HDR rungs and every audio rendition`() = runBlocking {
        val caps = ClientCapabilities(hlsHevc = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true, hlsAudioRenditions = true, maxAudioChannels = 6)
        val (plan, why) = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, null, "HDR10", burnsSubtitle = false)
        assertEquals("ours", why)
        plan!!
        assertEquals(EncoderCodec.HEVC, plan.codec)
        assertTrue(plan.keepsHdr)
        assertEquals(4, plan.rungs.size)
        assertEquals(listOf(0, 1), plan.audio.map { it.audioOrder })
        assertEquals(listOf(6, 6), plan.audio.map { it.channels })
        assertEquals(EncoderMux.TS, plan.mux, "Media3 (a BRAVIA, a phone) gets MPEG-TS")
    }

    @Test fun `only Apple's and the browsers' players get fMP4 segments`() {
        assertEquals(EncoderMux.FMP4, encoderMuxFor("tv", "mac"))
        assertEquals(EncoderMux.FMP4, encoderMuxFor("tv", "web"))
        assertEquals(EncoderMux.FMP4, encoderMuxFor("phone", "ios"))
        assertEquals(EncoderMux.TS, encoderMuxFor("phone", "phone"))
        assertEquals(EncoderMux.TS, encoderMuxFor("tv", "tv"))
        assertEquals(EncoderMux.TS, encoderMuxFor("tv", null))
        assertEquals(EncoderMux.TS, encoderMuxFor("cast", "web"))
    }

    @Test fun `an installed 1_50 app gets one quality — the receiver gets TS — and a burn-in stays Jellyfin's`() = runBlocking {
        val old = ClientCapabilities(videoCodecs = listOf("h264"), hlsAdaptive = false)
        val (one, _) = encoder().planFor(old, "phone", "/m/film.mkv", 7_200_000, tracks, audio, 1, 10_000_000, "HDR10", burnsSubtitle = false)
        assertEquals(1, one!!.rungs.size)
        assertEquals(EncoderCodec.H264, one.codec)
        assertTrue(one.tonemaps)
        assertEquals(1, one.audio.size)   // no renditions declared: the carried track only
        val cast = ClientCapabilities(videoCodecs = listOf("h264"), hlsAdaptive = true)
        assertEquals(EncoderMux.TS, encoder().planFor(cast, "cast", "/m/film.mkv", 7_200_000, tracks, audio, 1, null, "HDR10", false).first!!.mux)
        val (none, why) = encoder().planFor(cast, "cast", "/m/film.mkv", 7_200_000, tracks, audio, 1, null, "HDR10", burnsSubtitle = true)
        assertNull(none)
        assertContains(why, "burn-in")
        assertEquals("encoder off", encoder(enabled = false).planFor(cast, "cast", "/m/film.mkv", 7_200_000, tracks, audio, 1, null, "HDR10", false).second)
    }

    @Test fun `stream ids are 128 random bits`() {
        val a = secureHexId(); val b = secureHexId()
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue(a != b)
    }
}
