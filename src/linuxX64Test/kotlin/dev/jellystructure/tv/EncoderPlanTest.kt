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

    @Test fun `the codec family follows either HEVC-over-HLS field and the source's HDR form`() {
        // FR-313-16 — the shape the whole app fleet really sends: hls_hevc false (R329 left it the Mac/AirPlay gate),
        // hls_hevc_capable true. Asking hls_hevc alone is what served a BRAVIA H.264 SDR (DEB-1083).
        assertEquals(EncoderCodec.HEVC, encoderCodecFor(false, true, listOf("hevc", "h264"), supportsHdr10 = true, supportsHlg = false, source = hdr4k))
        // The Cast web receiver's shape: hls_hevc only, no hls_hevc_capable at all. The one road that works today.
        assertEquals(EncoderCodec.HEVC, encoderCodecFor(true, false, listOf("hevc", "h264"), supportsHdr10 = true, supportsHlg = false, source = hdr4k))
        // Neither field: H.264 SDR, exactly as before.
        assertEquals(EncoderCodec.H264, encoderCodecFor(false, false, listOf("hevc"), supportsHdr10 = true, supportsHlg = true, source = hdr4k))
        // HEVC over HLS, but the source's own HDR form is not declared: H.264, tone-mapped.
        assertEquals(EncoderCodec.H264, encoderCodecFor(false, true, listOf("hevc"), supportsHdr10 = false, supportsHlg = false, source = hdr4k))
        assertEquals(EncoderCodec.H264, encoderCodecFor(true, true, listOf("hevc"), supportsHdr10 = true, supportsHlg = false, source = hdr4k.copy(hlg = true)))
        // An SDR source asks for no HDR form.
        assertEquals(EncoderCodec.HEVC, encoderCodecFor(false, true, listOf("hevc"), supportsHdr10 = false, supportsHlg = false, source = hdr4k.copy(hdr = false)))
        // hevc missing from video_codecs is H.264 whichever field claims HLS.
        assertEquals(EncoderCodec.H264, encoderCodecFor(true, true, listOf("h264"), supportsHdr10 = true, supportsHlg = true, source = hdr4k))
    }

    @Test fun `codec strings are exact per rung`() {
        assertEquals("avc1.640029", videoCodecString(EncoderCodec.H264, 1080, false))
        assertEquals("avc1.64001f", videoCodecString(EncoderCodec.H264, 720, false))
        assertEquals("avc1.64001e", videoCodecString(EncoderCodec.H264, 480, false))
        assertEquals("hvc1.2.4.L153.B0", videoCodecString(EncoderCodec.HEVC, 2160, true))
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
        assertContains(hdr, "-level:v:0 5.1")
        val ts = encoderCommand(plan(EncoderCodec.H264, EncoderMux.TS, start = 0), startSegment = 0, dir = "/w/x")
        assertContains(ts, "-hls_segment_type mpegts")
        assertContains(ts, "s%d.ts")
    }

    @Test fun `frames are tagged with the output's colours before the split — found live 2026-10-09 evening`() {
        // An SDR source with untagged colour: without the tags ffmpeg 8 inserted a software auto_scale on CUDA frames.
        val sdrSrc = hdr4k.copy(hdr = false, width = 1920, height = 1080, videoBps = 10_000_000)
        for (codec in listOf(EncoderCodec.HEVC, EncoderCodec.H264)) {
            val c = encoderCommand(plan(codec, src = sdrSrc, start = 0), startSegment = 0, dir = "/w/x")
            val tag = c.indexOf("setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709")
            assertTrue(tag in c.indexOf("scale_cuda") until c.indexOf("split="), "$codec: $c")
            assertContains(c, "-color_primaries bt709 -color_trc bt709 -colorspace bt709")
        }
        val keep = encoderCommand(plan(EncoderCodec.HEVC), startSegment = 0, dir = "/w/x")
        val tag = keep.indexOf("setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc")
        assertTrue(tag in keep.indexOf("scale_cuda") until keep.indexOf("split="), keep)
        val hlg = encoderCommand(plan(EncoderCodec.HEVC, src = hdr4k.copy(hlg = true)), startSegment = 0, dir = "/w/x")
        assertContains(hlg, "setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc,split=")
    }

    @Test fun `a play that starts mid-film says where — never from 0 first — found live 2026-10-09`() {
        assertContains(encoderMaster(plan(), startMs = 1_113_500), "#EXT-X-START:TIME-OFFSET=1113.500,PRECISE=YES\n")
        assertFalse(encoderMaster(plan(), startMs = 0).contains("EXT-X-START"))
    }

    @Test fun `a burned-in image subtitle is composited once — before the split`() {
        val c = encoderCommand(plan(EncoderCodec.H264, burn = 1, start = 0), startSegment = 0, dir = "/w/x")
        assertEquals(1, Regex("overlay_cuda").findAll(c).count())
        assertTrue(c.indexOf("overlay_cuda") < c.indexOf("split="))
        assertContains(c, "[0:s:1]")
        // Found live 2026-10-09: the overlay drops the colour tags; without them the output's bt709 flags made ffmpeg 8
        // insert a software conversion after the split on GPU frames, and every burn-in plan was refused.
        assertTrue(c.indexOf("setparams=color_primaries=bt709") in c.indexOf("overlay_cuda") until c.indexOf("split="))
        assertContains(c, "overlay_cuda=eof_action=pass:repeatlast=0")
        assertContains(c, "-canvas_size ")
        assertTrue(c.indexOf("-canvas_size") < c.indexOf(" -i "))
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
        // Owner, 2026-10-09 evening — casts default to our encoder; the switch turned off sends a receiver to Jellyfin.
        // The TV app (Cast Connect) plays as a `tv` either way.
        assertNull(encoderDecision(true, true, card, hdr4k, false, deviceKind = "cast"))
        assertEquals(CAST_RECEIVER_FALLBACK, encoderDecision(true, true, card, hdr4k, false, deviceKind = "cast", castReceivers = false))
        assertNull(encoderDecision(true, true, card, hdr4k, false, deviceKind = "tv"))
    }

    @Test fun `a file ffmpeg refused goes to Jellyfin for a while instead of being retried`() {
        assertTrue(refusedBeforeFirstSegment(exitCode = 1, highest = 9, startSegment = 10))   // nothing written: refused
        assertFalse(refusedBeforeFirstSegment(exitCode = 1, highest = 12, startSegment = 10)) // failed later: not a refusal
        assertFalse(refusedBeforeFirstSegment(exitCode = 0, highest = 9, startSegment = 10))
        val r = RefusedSources(ttlMs = 1_000)
        assertNull(r.reason("/m/film.mkv", 0))
        r.markRefused("/m/film.mkv", EncoderCodec.HEVC, 187, nowMs = 100)
        assertContains(r.reason("/m/film.mkv", 500)!!, "Jellyfin")
        assertNull(r.reason("/m/other.mkv", 500))
        assertNull(r.reason("/m/film.mkv", 1_200))   // the TTL passed: our encoder may try again
    }

    private val tracks = listOf(
        Track(0, "v:0", TrackKind.VIDEO, "hevc", null, null, true, false, width = 3840, height = 2160, videoRange = "HDR", videoBitrate = 50_000_000, durationMs = 7_200_000),
        Track(1, "a:0", TrackKind.AUDIO, "truehd", "eng", null, true, false),
        Track(2, "a:1", TrackKind.AUDIO, "ac3", "dan", null, false, false),
    )
    private val audio = listOf(AudioTrack(1, "eng", "English", "truehd", 8, true), AudioTrack(2, "dan", "Dansk", "ac3", 6, false))
    private fun encoder(cards: List<EncoderCard> = listOf(EncoderCard(0, "Quadro P4000", null, 0)), enabled: Boolean = true, castReceivers: Boolean = true) =
        Encoder({ EncoderConfig(enabled = enabled, workDir = "/tmp/js-encoder-test", castReceivers = castReceivers) }, cardsOverride = { cards }, ffmpegOverride = { true })

    /**
     * FR-313-16 — the capability shape the TV and the phone apps really send: `hls_hevc` **false** (R329 left it the
     * Mac/AirPlay fMP4 gate, and Android's `playsOnlyHls()` is false), `hls_hevc_capable` true, `hevc` in
     * `video_codecs`, `supports_hdr10` from the decoder probe. The `hlsHevc = true` shape this test used to build is
     * one no Android build can send, which is how DEB-1083 stayed hidden for six days — hence a rewrite, not an
     * addition.
     */
    @Test fun `a BRAVIA-like device gets HEVC HDR rungs and every audio rendition`() = runBlocking {
        val caps = ClientCapabilities(hlsHevcCapable = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true, hlsAudioRenditions = true, maxAudioChannels = 6)
        assertFalse(caps.hlsHevc, "the fleet's shape: 253's fMP4 opt-in stays off")
        val (plan, why) = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10")
        assertEquals("ours", why)
        plan!!
        assertEquals(EncoderCodec.HEVC, plan.codec)
        assertTrue(plan.keepsHdr)
        assertFalse(plan.tonemaps)
        assertEquals(4, plan.rungs.size)
        assertEquals(2160, plan.rungs[0].boxHeight)
        assertEquals(listOf(0, 1), plan.audio.map { it.audioOrder })
        assertEquals(listOf(6, 6), plan.audio.map { it.channels })
        assertEquals(EncoderMux.TS, plan.mux, "Media3 (a BRAVIA, a phone) gets MPEG-TS")
    }

    /** FR-313-16 — the Cast web receiver sends `hls_hevc` and no `hls_hevc_capable`: the 2026-10-10 road must hold. */
    @Test fun `the cast receiver's own shape still gets HEVC HDR rungs`() = runBlocking {
        val caps = ClientCapabilities(hlsHevc = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true)
        assertFalse(caps.hlsHevcCapable, "the receiver declares R379's field nowhere")
        val plan = encoder().planFor(caps, "cast", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10").first!!
        assertEquals(EncoderCodec.HEVC, plan.codec)
        assertTrue(plan.keepsHdr)
        assertEquals(2160, plan.rungs[0].boxHeight)
    }

    /** FR-313-16 — neither field, and HEVC over HLS with no declared HDR form, both keep the H.264 SDR branch. */
    @Test fun `a client that declares neither HEVC-over-HLS field keeps H_264 SDR`() = runBlocking {
        val neither = ClientCapabilities(videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true)
        val plain = encoder().planFor(neither, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10").first!!
        assertEquals(EncoderCodec.H264, plain.codec)
        assertTrue(plain.tonemaps)
        assertFalse(plain.keepsHdr)
        // HEVC-capable, HDR source, but the source's own HDR form is not declared: H.264, tone-mapped once.
        val noHdrForm = ClientCapabilities(hlsHevcCapable = true, videoCodecs = listOf("hevc", "h264"), hlsAdaptive = true)
        val sdr = encoder().planFor(noHdrForm, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10").first!!
        assertEquals(EncoderCodec.H264, sdr.codec)
        assertTrue(sdr.tonemaps)
    }

    /**
     * FR-313-16 / DEB-1083's ruled-out candidate — a 60 Mbps `decode_max_bitrate_hevc` against an 80.9 Mbps 4K HDR
     * source caps the rungs, never the family: HEVC is chosen and the 2160p top sits at `topCapBps`' 20 Mbps anyway.
     */
    @Test fun `an HEVC decode ceiling under the source's bitrate caps rungs — not the codec family`() = runBlocking {
        val fat = tracks.map { if (it.kind == TrackKind.VIDEO) it.copy(videoBitrate = 80_900_000) else it }
        val caps = ClientCapabilities(
            hlsHevcCapable = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true,
            maxHevcBitrate = 60_000_000,
        )
        val plan = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, fat, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10").first!!
        assertEquals(EncoderCodec.HEVC, plan.codec)
        assertTrue(plan.keepsHdr)
        assertEquals(2160, plan.rungs[0].boxHeight)
        assertEquals(20_000_000L, plan.rungs[0].videoBps)
        assertTrue(plan.rungs.all { it.videoBps <= 54_000_000L })
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

    @Test fun `an installed 1_50 app gets one quality — the receiver gets TS — and an unplaced burn-in stays Jellyfin's`() = runBlocking {
        val old = ClientCapabilities(videoCodecs = listOf("h264"), hlsAdaptive = false)
        val (one, _) = encoder().planFor(old, "phone", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = 10_000_000, noRecord = false, sourceVideoRange = "HDR10")
        assertEquals(1, one!!.rungs.size)
        assertEquals(EncoderCodec.H264, one.codec)
        assertTrue(one.tonemaps)
        assertEquals(1, one.audio.size)   // no renditions declared: the carried track only
        val cast = ClientCapabilities(videoCodecs = listOf("h264"), hlsAdaptive = true)
        // A receiver gets TS, and (owner, 2026-10-09) its transcode is ours by default; turned off, Jellyfin's.
        assertEquals(EncoderMux.TS, encoderMuxFor("cast", null))
        val (castPlan, castWhy) = encoder().planFor(cast, "cast", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10")
        assertEquals("ours", castWhy)
        assertEquals(EncoderMux.TS, castPlan!!.mux)
        assertEquals(CAST_RECEIVER_FALLBACK, encoder(castReceivers = false).planFor(cast, "cast", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10").second)
        val (none, why) = encoder().planFor(cast, "phone", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10", burnRequested = true)
        assertNull(none)
        assertContains(why, "313d")
        assertEquals("encoder off", encoder(enabled = false).planFor(cast, "cast", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10").second)
    }

    @Test fun `stream ids are 128 random bits`() {
        val a = secureHexId(); val b = secureHexId()
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue(a != b)
    }

    @Test fun `a 2160p HEVC rung is level 5_1 — its peak exceeds level 5_0's 25 Mbps`() {
        assertEquals("5.1", levelOf(EncoderCodec.HEVC, 2160))
        assertEquals("hvc1.2.4.L153.B0", videoCodecString(EncoderCodec.HEVC, 2160, tenBit = true))
    }

    // ── 309 (FR-309-2) — where a play starts ──────────────────────────────────────────────────────────────────────
    @Test fun `with no record a climbing player starts on the 4 Mbps rung — never the top`() = runBlocking {
        val caps = ClientCapabilities(hlsHevc = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true, hlsAudioRenditions = true, maxAudioChannels = 6)
        val plan = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10").first!!
        assertTrue(plan.startRung > 0)
        assertTrue(plan.rungs[plan.startRung].videoBps <= NO_RECORD_START_VIDEO_BPS)
        assertTrue(plan.rungs[plan.startRung - 1].videoBps > NO_RECORD_START_VIDEO_BPS)
    }

    @Test fun `a record starts on the best rung its stream fits`() {
        val rungs = encoderRungs(EncoderCodec.HEVC, 3840, 2160, 50_000_000, null)
        val audioPeak = 640_000L
        val take = rungBandwidth(rungs[1], audioPeak)
        assertEquals(1, startRungFor(rungs, audioPeak, take, noRecord = false))
        assertEquals(1, startRungFor(rungs, audioPeak, take + 1, noRecord = false))
        assertEquals(2, startRungFor(rungs, audioPeak, take - 1, noRecord = false))
        assertEquals(rungs.lastIndex, startRungFor(rungs, audioPeak, 1_000, noRecord = false))   // below everything: the lowest
        assertEquals(0, startRungFor(rungs, audioPeak, null, noRecord = false))                 // can't climb: the top
        assertEquals(0, startRungFor(rungs, audioPeak, Long.MAX_VALUE, noRecord = false))
    }

    @Test fun `a player that cannot climb gets one rung — the one its record fits`() = runBlocking {
        val old = ClientCapabilities(hlsHevc = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = false)
        val plan = encoder().planFor(old, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = 12_000_000, noRecord = false, sourceVideoRange = "HDR10").first!!
        assertEquals(1, plan.rungs.size)
        assertTrue(rungBandwidth(plan.rungs[0], 640_000) <= 12_000_000)
    }

    // ── 313d (FR-313-6) — subtitles ───────────────────────────────────────────────────────────────────────────────
    @Test fun `a picked image subtitle is burned in by our job as H_264`() = runBlocking {
        val withSubs = tracks + Track(3, "s:0", TrackKind.SUBTITLE, "srt", "eng", null, false, false) + Track(4, "s:1", TrackKind.SUBTITLE, "hdmv_pgs_subtitle", "dan", null, false, false)
        // The fleet's shape (FR-313-16), so this proves the burn-in override on a device that would otherwise get HEVC.
        val caps = ClientCapabilities(hlsHevcCapable = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true)
        val plan = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, withSubs, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10",
            burnSubtitleOrder = 1).first!!
        assertEquals(EncoderCodec.H264, plan.codec)
        assertEquals(1, plan.burnSubtitleOrder)
        assertTrue(plan.tonemaps)
        assertContains(encoderCommand(plan, 0, "/tmp/x"), "[0:s:1]")
        assertContains(encoderCommand(plan, 0, "/tmp/x"), "overlay_cuda")
        // A subtitle the scan doesn't have stays Jellyfin's.
        val (none, why) = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, withSubs, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10",
            burnSubtitleOrder = 2)
        assertNull(none)
        assertContains(why, "313d")
    }

    @Test fun `text subtitles are WebVTT renditions in the master — only for a player that reads them there`() = runBlocking {
        val subs = listOf(EncoderSubtitle("English", "eng", forced = false, default = true, sourceUrl = "http://jf/1.vtt"),
            EncoderSubtitle("Dansk \"tvungen\"", "dan", forced = true, default = false, sourceUrl = "http://jf/2.vtt"))
        val reads = ClientCapabilities(hlsHevc = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true, hlsSubtitles = true)
        val plan = encoder().planFor(reads, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10", subtitles = subs).first!!
        val master = encoderMaster(plan)
        assertContains(master, "TYPE=SUBTITLES,GROUP-ID=\"subs\"")
        assertContains(master, "URI=\"t/0/main.m3u8\"")
        assertContains(master, "URI=\"t/1/main.m3u8\"")
        assertContains(master, ",SUBTITLES=\"subs\"")
        assertTrue("\"tvungen\"" !in master)   // a quote in a name never breaks the attribute
        val noSubs = ClientCapabilities(hlsHevc = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true)
        val plain = encoder().planFor(noSubs, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10", subtitles = subs).first!!
        assertTrue("SUBTITLES" !in encoderMaster(plain))
        val pl = encoderSubtitlePlaylist(7_200_500)
        assertContains(pl, "#EXTINF:7200.500,\nsub.vtt")
        assertContains(pl, "#EXT-X-ENDLIST")
    }

    @Test fun `encoder words for the admin`() = runBlocking {
        val caps = ClientCapabilities(hlsHevc = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true)
        val plan = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10").first!!
        assertEquals("4 qualities · HEVC HDR", encoderWords(plan))
        assertEquals("1 quality · H.264 · burned-in subtitles", encoderWords(plan.copy(rungs = plan.rungs.take(1), codec = EncoderCodec.H264, burnSubtitleOrder = 0)))
    }

    // ── 309 (FR-309-6) — the early encode ─────────────────────────────────────────────────────────────────────────
    @Test fun `a prewarm is adopted by the play that matches it and stopped when the viewer leaves`() = runBlocking {
        val enc = encoder().also { it.kickJobs = false }
        val caps = ClientCapabilities(hlsHevc = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true)
        val plan = enc.planFor(caps, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "HDR10").first!!
        val far = Long.MAX_VALUE / 2
        val warm = enc.prewarm(plan, "dev1", "item1", 10, far)
        assertEquals(warm, enc.prewarm(plan, "dev1", "item1", 10, far))           // the client re-posts: same job
        assertNull(enc.adoptPrewarm("dev2", "item1", plan, 10, "ps1", far))      // another device
        val adopted = enc.adoptPrewarm("dev1", "item1", plan, 11, "ps1", far)
        assertNotNull(adopted)
        assertNotNull(enc.master(adopted))
        assertTrue(enc.serves("ps1"))
        enc.prewarm(plan, "dev1", "item2", 0, far)
        assertEquals(1, enc.cancelPrewarm("dev1", "item2"))
        assertEquals(0, enc.cancelPrewarm("dev1", "item2"))
        assertEquals(0, enc.cancelPrewarm("dev1", "item1"))   // adopted: a play's job now, the page's leave can't stop it
        enc.prewarm(plan, "dev1", "item3", 10, far)
        assertNull(enc.adoptPrewarm("dev1", "item3", plan, 40, "ps3", far))      // the play starts elsewhere: not this job…
        assertEquals(0, enc.cancelPrewarm("dev1", "item3"))                       // …and the prewarm is already stopped
    }

    // 313 (2026-10-09) — the work folder is bounded: segments far behind the player are deleted, and only once.
    @Test fun pruneRangeKeepsAMinuteBehind() {
        assertNull(pruneRange(prunedBelow = 0, furthest = 50, keepBehind = 60))
        assertEquals(0 until 40, pruneRange(prunedBelow = 0, furthest = 100, keepBehind = 60))
        assertEquals(40 until 41, pruneRange(prunedBelow = 40, furthest = 101, keepBehind = 60))
        assertNull(pruneRange(prunedBelow = 41, furthest = 101, keepBehind = 60))
        assertEquals(300 until 390, pruneRange(prunedBelow = 300, furthest = 400, keepBehind = 10))
    }

    // ── DEB-2205 (FR-5.2) — a freeze recovery never serves the rung that froze, nor one above it ─────────────────────────────
    @Test fun `a freeze recovery drops the frozen rung and every rung above it`() {
        val rungs = encoderRungs(EncoderCodec.HEVC, 3840, 2160, 50_000_000, null)   // 2160@20000k, 1080, 720, 480
        val frozen = rungBandwidth(rungs[0], 192_000L)
        val kept = rungsBelowBandwidth(rungs, 192_000L, frozen)
        assertEquals(rungs.drop(1), kept)
        // The rung below froze too: only the ones under it remain, and the top is never offered again.
        assertEquals(rungs.drop(2), rungsBelowBandwidth(rungs, 192_000L, rungBandwidth(rungs[1], 192_000L)))
    }

    @Test fun `a freeze recovery with nowhere lower to go keeps the lowest rung and nothing above`() {
        val rungs = encoderRungs(EncoderCodec.H264, 1920, 1080, 30_000_000, null)
        val lowest = rungs.last()
        assertEquals(listOf(lowest), rungsBelowBandwidth(rungs, 192_000L, rungBandwidth(lowest, 192_000L)))
        assertEquals(listOf(lowest), rungsBelowBandwidth(rungs, 192_000L, 1L))
    }

    @Test fun `a bound above every rung changes nothing`() {
        val rungs = encoderRungs(EncoderCodec.H264, 1920, 1080, 30_000_000, null)
        assertEquals(rungs, rungsBelowBandwidth(rungs, 192_000L, Long.MAX_VALUE))
    }

    // ── DEB-3101 (FR-313-5a..5c) — the ladder keeps the film's own Dolby track ───────────────────────────────────────────────

    private val dolbyTracks = listOf(
        Track(0, "v:0", TrackKind.VIDEO, "hevc", null, null, true, false, width = 3840, height = 2160, videoRange = "HDR", videoBitrate = 50_000_000, durationMs = 7_200_000),
        Track(1, "a:0", TrackKind.AUDIO, "eac3", "eng", null, true, false),
        Track(2, "a:1", TrackKind.AUDIO, "ac3", "dan", null, false, false),
        Track(3, "a:2", TrackKind.AUDIO, "truehd", "fra", null, false, false),
        Track(4, "a:3", TrackKind.AUDIO, "eac3", "deu", null, false, false),
    )
    private val dolbyAudio = listOf(
        AudioTrack(1, "eng", "English", "eac3", 6, true), AudioTrack(2, "dan", "Dansk", "ac3", 6, false),
        AudioTrack(3, "fra", "Français", "truehd", 8, false), AudioTrack(4, "deu", "Deutsch", "eac3", 8, false),
    )
    private fun dolbyCaps(decoders: List<String>?, maxChannels: Int = 6) = ClientCapabilities(
        hlsHevcCapable = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true, hlsAudioRenditions = true,
        maxAudioChannels = maxChannels, platformAudioDecoders = decoders)

    @Test fun `the family is the carried track's codec when the device decodes it — else AAC`() {
        assertEquals("eac3", audioFamilyFor("eac3", listOf("ac3", "eac3")))
        assertEquals("ac3", audioFamilyFor("ac3", listOf("AC3")))
        assertEquals("eac3", audioFamilyFor("E-AC-3", listOf("ec-3")), "Jellyfin's and the device's spellings meet")
        assertEquals("aac", audioFamilyFor("eac3", listOf("ac3")), "the device names AC-3 only")
        assertEquals("aac", audioFamilyFor("eac3", emptyList()))
        assertEquals("aac", audioFamilyFor("eac3", null), "an app that does not report the field (web, desktop, a cast receiver, older than R379)")
        assertEquals("aac", audioFamilyFor("truehd", listOf("ac3", "eac3")))
        assertEquals("aac", audioFamilyFor("aac", listOf("ac3", "eac3")))
        assertEquals("aac", audioFamilyFor(null, listOf("ac3", "eac3")))
    }

    @Test fun `a Dolby film on a device that decodes Dolby copies the carried track and encodes its siblings to the family`() = runBlocking {
        val (plan, why) = encoder().planFor(dolbyCaps(listOf("ac3", "eac3")), "tv", "/m/film.mkv", 7_200_000, dolbyTracks, dolbyAudio, 1,
            takeBps = null, noRecord = true, sourceVideoRange = "HDR10", audioBps = mapOf(1 to 640_000L, 4 to 1_024_000L))
        assertEquals("ours", why)
        plan!!
        assertTrue(plan.audio.all { it.codec == "eac3" }, "one codec per master: ${plan.audio}")
        val (carried, ac3Sibling, truehd, eac3Eight) = plan.audio
        assertTrue(carried.copy && carried.channels == 6 && carried.sourceBps == 640_000L, "the carried E-AC-3 5.1 is copied: $carried")
        assertFalse(ac3Sibling.copy, "a sibling AC-3 is encoded to E-AC-3")
        assertFalse(truehd.copy, "TrueHD is encoded to E-AC-3")
        assertEquals(6, truehd.channels, "…at the device's six channels")
        assertFalse(eac3Eight.copy, "an E-AC-3 track wider than the device takes is encoded, not copied")
        assertEquals(6, eac3Eight.channels)
        // FR-313-5c — BANDWIDTH counts the largest rendition: here a 640 kb/s encode (5.1) and the copy at 640 kb/s.
        assertEquals(640_000L, plan.audio.maxOf { audioBitrate(it) })
    }

    @Test fun `a device that does not decode Dolby or does not say gets the AAC plan it always had`() = runBlocking {
        for (decoders in listOf(null, emptyList(), listOf("ac3"))) {
            val plan = encoder().planFor(dolbyCaps(decoders), "tv", "/m/film.mkv", 7_200_000, dolbyTracks, dolbyAudio, 1,
                takeBps = null, noRecord = true, sourceVideoRange = "HDR10", audioBps = mapOf(1 to 640_000L)).first!!
            assertTrue(plan.audio.all { it.codec == "aac" && !it.copy && it.sourceBps == null }, "decoders=$decoders: ${plan.audio}")
            assertEquals(listOf(6, 6, 6, 6), plan.audio.map { it.channels })
        }
    }

    @Test fun `the family is fixed by the track the play starts on — a TrueHD start stays AAC`() = runBlocking {
        val plan = encoder().planFor(dolbyCaps(listOf("ac3", "eac3")), "tv", "/m/film.mkv", 7_200_000, dolbyTracks, dolbyAudio, 3,
            takeBps = null, noRecord = true, sourceVideoRange = "HDR10").first!!
        assertTrue(plan.audio.all { it.codec == "aac" && !it.copy })
    }

    @Test fun `a copied track counts at its real bitrate — else the library's p90 — and an encoded one at today's figure`() {
        fun copied(codec: String, bps: Long?) = EncoderAudio(0, 0, codec, 6, null, null, true, copy = true, sourceBps = bps)
        assertEquals(1_536_000L, audioBitrate(copied("eac3", 1_536_000L)))
        assertEquals(768_000L, audioBitrate(copied("eac3", null)))
        assertEquals(768_000L, audioBitrate(copied("eac3", 0L)), "a zero from Jellyfin is no figure")
        assertEquals(640_000L, audioBitrate(copied("ac3", null)))
        assertEquals(640_000L, audioBitrate(EncoderAudio(0, 0, "eac3", 6, null, null, true)), "an encoded E-AC-3 5.1")
        assertEquals(384_000L, audioBitrate(EncoderAudio(0, 0, "aac", 6, null, null, true)), "an AAC-family master is unchanged")
        // That one figure feeds BANDWIDTH, AVERAGE-BANDWIDTH and the start rung.
        val p = plan(EncoderCodec.HEVC, EncoderMux.TS, start = 0).copy(audio = listOf(copied("eac3", 1_024_000L)))
        val rung = p.rungs[0]
        val inf = encoderMaster(p).lines().first { it.startsWith("#EXT-X-STREAM-INF:") }
        assertContains(inf, "BANDWIDTH=${rungBandwidth(rung, 1_024_000L)},")
        assertContains(inf, "AVERAGE-BANDWIDTH=${rung.videoBps + 1_024_000L},")
        assertContains(inf, "CODECS=\"hvc1.2.4.L153.B0,ec-3\"")
    }

    @Test fun `a Dolby play can start one rung lower than the same film on AAC`() {
        val rungs = encoderRungs(EncoderCodec.H264, 1920, 1080, 30_000_000, null)
        val take = rungBandwidth(rungs[1], 384_000L)   // exactly what rung 1 costs beside AAC 5.1
        assertEquals(1, startRungFor(rungs, 384_000L, take, false))
        assertEquals(2, startRungFor(rungs, 768_000L, take, false), "with a 768 kb/s Dolby track the same take no longer fits rung 1")
    }

    @Test fun `a copied track is stream-copied — no bitrate and no channel count — and an encoded sibling is encoded`() {
        val p = plan(EncoderCodec.H264, EncoderMux.TS, start = 0).copy(audio = listOf(
            EncoderAudio(0, 0, "eac3", 6, "eng", "English", true, copy = true, sourceBps = 640_000L),
            EncoderAudio(1, 2, "eac3", 6, "dan", "Dansk", false)))
        for (gpu in listOf<Int?>(null, 0)) {
            val cmd = encoderCommand(p.copy(cudaDevice = gpu), startSegment = 3, dir = "/w/x", ffmpeg = "/ff")
            assertContains(cmd, " -c:a:0 copy -copypriorss:a:0 0")
            assertFalse(cmd.contains("-b:a:0") || cmd.contains("-ac:a:0"), "a copy takes neither: $cmd")
            assertContains(cmd, " -c:a:1 eac3 -b:a:1 640000 -ac:a:1 6")
            assertContains(cmd, "-map 0:a:0 -map 0:a:2")
        }
    }

    @Test fun `a plan stored before this change decodes with every new field at its old meaning and plays as planned`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val now = plan(EncoderCodec.HEVC, EncoderMux.TS)
        val stored = json.encodeToString(EncoderPlan.serializer(), now)
        val old = stored.replace(Regex(",\"copy\":false"), "").replace(Regex(",\"sourceBps\":null"), "")
        assertTrue(old.length < stored.length && !old.contains("copy") && !old.contains("sourceBps"), "the old shape has neither field: $old")
        val back = json.decodeFromString(EncoderPlan.serializer(), old)
        assertEquals(now, back)
        assertTrue(back.audio.none { it.copy || it.sourceBps != null })
        assertEquals(encoderMaster(now), encoderMaster(back))
        assertContains(encoderCommand(back, 0, "/w/x", "/ff"), " -c:a:0 aac -b:a:0 384000 -ac:a:0 6")
    }
}
