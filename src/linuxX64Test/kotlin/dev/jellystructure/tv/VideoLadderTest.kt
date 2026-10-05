package dev.jellystructure.tv

import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.auth.maxStreamingBitrate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 308 — the ladder: one table, variants above the device's decode ceiling left out, Jellyfin's own BANDWIDTH /
 * RESOLUTION / CODECS for each variant, each under its own play session, the audio renditions shared; the first guess
 * from the device's own measurements; direct play only when the file fits them.
 */
class VideoLadderTest {
    // meidam's 2026-10-05 film, as Jellyfin negotiated it for the Chromecast: a 1080p H.264 encode at the source's
    // 80.9 Mbps (TranscodeReasons as Jellyfin writes them, comma URL-encoded).
    private val template = "https://jf.example/videos/abc/master.m3u8?DeviceId=dev&MediaSourceId=abc&VideoCodec=h264" +
        "&AudioCodec=aac&AudioStreamIndex=1&VideoBitrate=80889815&AudioBitrate=256000&MaxWidth=1920&MaxHeight=1080" +
        "&PlaySessionId=psid&ApiKey=secret&SegmentContainer=ts&TranscodeReasons=VideoRangeTypeNotSupported%2CAudioCodecNotSupported"

    /** A stand-in for Jellyfin's master endpoint: it writes BANDWIDTH and RESOLUTION from the request, as Jellyfin does. */
    private fun jellyfinMaster(url: String): String {
        val vb = queryParam(url, "VideoBitrate")!!.toLong()
        val h = queryParam(url, "MaxHeight")!!.toInt()
        val w = queryParam(url, "MaxWidth")!!.toInt()
        val query = url.substringAfter('?')
        return """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=${vb + 256000},AVERAGE-BANDWIDTH=${vb + 256000},VIDEO-RANGE=SDR,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=${w}x$h,FRAME-RATE=23.976
            main.m3u8?$query
        """.trimIndent()
    }

    private val fetched = mutableListOf<String>()
    private fun service() = AudioRenditions { url -> fetched += url; jellyfinMaster(url) }

    private fun streamInfs(m: String) = m.lines().filter { it.startsWith("#EXT-X-STREAM-INF:") }
    private fun uris(m: String) = m.lines().filter { it.startsWith("http") }

    @Test
    fun `the ladder is the top plus every rung of the table under it`() = runBlocking {
        val s = service()
        val plan = AudioRenditions.LadderPlan(template, "psid", topVideoBps(80_889_815, 80_889_815, null)!!, null, null)
        val id = s.register("psid", template, emptyList(), null, Long.MAX_VALUE, "", 0, withRenditions = false, ladder = plan)!!
        val m = s.master(id)!!
        val infs = streamInfs(m)
        assertEquals(listOf(40_256_000L, 12_256_000L, 8_256_000L, 4_256_000L, 1_756_000L), infs.map { MasterVariant(it, "").bandwidth })
        assertEquals(listOf(1080, 1080, 1080, 720, 480), infs.map { MasterVariant(it, "").height })
        infs.forEach { assertContains(it, "CODECS=\"avc1.640028,mp4a.40.2\"") }
        // Each variant its own play session (Jellyfin keys a job's segments on it), all absolute, and no audio group.
        assertEquals(listOf("psidv0", "psidv1", "psidv2", "psidv3", "psidv4"), uris(m).map { queryParam(it, "PlaySessionId") })
        assertTrue(uris(m).all { it.startsWith("https://jf.example/videos/abc/main.m3u8?") })
        assertFalse(m.contains("AUDIO="), m)
        // The 720p and 480p rungs ask for their own picture box.
        assertEquals(listOf("1280", "854"), uris(m).drop(3).map { queryParam(it, "MaxWidth") })
        // Composed once: a second fetch of the master asks Jellyfin nothing.
        val n = fetched.size
        assertEquals(m, s.master(id))
        assertEquals(n, fetched.size)
    }

    @Test
    fun `variants above the device's decode ceiling are left out`() {
        // A stick decoding H.264 up to 10 Mbps: the top is 9 Mbps, and only rungs clearly below it remain.
        val top = topVideoBps(80_889_815, 80_889_815, 10_000_000)!!
        assertEquals(9_000_000, top)
        assertEquals(listOf(LadderRung(720, 4_000_000), LadderRung(480, 1_500_000)), lowerRungs(top, 1080, 10_000_000))
        // A 720p top never gets a 1080p rung, and a source at 5 Mbps has no rung near it.
        assertEquals(listOf(LadderRung(480, 1_500_000)), lowerRungs(5_000_000, 720, null))
        // A source at 1.8 Mbps is its own ladder.
        assertEquals(emptyList(), lowerRungs(1_800_000, 1080, null))
        assertEquals(LADDER_TOP_CAP_BPS, topVideoBps(null, 120_000_000, null))
        assertNull(topVideoBps(null, null, null))
    }

    @Test
    fun `audio renditions are shared by every variant`() = runBlocking {
        val s = service()
        val audio = listOf(AudioTrack(1, "eng", "English"), AudioTrack(2, "dan", "Dansk"))
        val plan = AudioRenditions.LadderPlan(template, "psid", 12_000_000, null, null)
        val id = s.register("psid", template, audio, 1, Long.MAX_VALUE, "/media/f.mkv", 3_600_000, ladder = plan)!!
        val m = s.master(id)!!
        assertEquals(2, m.lines().count { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") })
        val infs = streamInfs(m)
        assertEquals(4, infs.size, m)   // 12 · 8 · 4 · 1.5
        assertTrue(infs.all { it.endsWith(",AUDIO=\"aud\"") }, m)
    }

    @Test
    fun `the first guess is listed first and the rest from the top down`() {
        val bw = listOf(40_256_000L, 12_256_000L, 8_256_000L, 4_256_000L, 1_756_000L)
        // meidam's phone measured 19 Mbps ⇒ a budget of 13.3: the 12 Mbps variant first.
        assertEquals(listOf(1, 0, 2, 3, 4), startOrder(bw, throughputBudget(19_000_000)))
        // Nothing measured: the top first, as today.
        assertEquals(listOf(0, 1, 2, 3, 4), startOrder(bw, null))
        // A path below every rung: the lowest first.
        assertEquals(listOf(4, 0, 1, 2, 3), startOrder(bw, 1_000_000))
    }

    @Test
    fun `the measurement is the median of the device's latest HLS estimates and never a direct play's`() {
        val now = 100L * 86_400L
        val rows = listOf(
            ThroughputSample(5_000_000, directPlay = true, updatedAtSec = now - 10),       // progressive read pace: ignored
            ThroughputSample(19_000_000, directPlay = false, updatedAtSec = now - 20),
            ThroughputSample(25_000_000, directPlay = false, updatedAtSec = now - 30),
            ThroughputSample(15_000_000, directPlay = false, updatedAtSec = now - 40),
            ThroughputSample(200_000_000, directPlay = false, updatedAtSec = now - 50), // the 4th latest: not counted
            ThroughputSample(null, directPlay = false, updatedAtSec = now - 5),
        )
        assertEquals(19_000_000, measuredThroughput(rows, now))
        assertEquals(13_300_000, throughputBudget(measuredThroughput(rows, now)))
        // Older than 30 days, or none at all: nothing measured.
        assertNull(measuredThroughput(listOf(ThroughputSample(19_000_000, false, now - 31L * 86_400L)), now))
        assertNull(measuredThroughput(emptyList(), now))
        assertEquals(22_000_000, measuredThroughput(rows.take(3), now))
    }

    @Test
    fun `direct play only when the file fits what the device measured`() {
        val phone = ClientCapabilities(maxVideoBitrate = 160_000_000, linkKind = "wifi", linkMbps = 1080)
        // Today: the local link's 540 Mbps and the 120 Mbps ceiling — an 80.9 Mbps file direct-plays.
        assertEquals(120_000_000, maxStreamingBitrate(phone))
        // After a 19 Mbps measurement the same file is above the cap, so Jellyfin transcodes it (and the ladder follows).
        assertEquals(13_300_000, maxStreamingBitrate(phone, throughputBudget(19_000_000)))
        // A LAN TV whose HLS plays measure 220 Mbps keeps today's number.
        assertEquals(120_000_000, maxStreamingBitrate(phone, throughputBudget(220_000_000)))
    }

    @Test
    fun `only a transcode that re-encodes the picture gets a ladder`() {
        assertTrue(reencodesVideo(template))
        assertTrue(reencodesVideo("x?TranscodeReasons=ContainerBitrateExceedsLimit"))
        assertTrue(reencodesVideo("x?TranscodeReasons=AudioCodecNotSupported&SubtitleMethod=Encode&SubtitleStreamIndex=11"))
        assertFalse(reencodesVideo("x?TranscodeReasons=AudioCodecNotSupported%2CContainerNotSupported"))
        assertFalse(reencodesVideo("x?VideoCodec=h264"))
    }

    @Test
    fun `teardown names every variant's play session`() = runBlocking {
        val s = service()
        val plan = AudioRenditions.LadderPlan(template, "psid", 12_000_000, null, null)
        val id = s.register("psid", template, emptyList(), null, Long.MAX_VALUE, "", 0, withRenditions = false, ladder = plan)!!
        assertNotNull(s.master(id))
        assertEquals(listOf("psidv0", "psidv1", "psidv2", "psidv3"), s.stopFor("psid"))
        assertEquals(emptyList(), s.stopFor("psid"))
    }

    @Test
    fun `a variant url rewrites only its own parameters`() {
        val u = variantUrl(template, "psidv3", 4_000_000, 720)
        assertEquals("4000000", queryParam(u, "VideoBitrate"))
        assertEquals("720", queryParam(u, "MaxHeight"))
        assertEquals("1280", queryParam(u, "MaxWidth"))
        assertEquals("psidv3", queryParam(u, "PlaySessionId"))
        assertEquals("secret", queryParam(u, "ApiKey"))
        assertEquals(u.split('&').size, template.split('&').size)
    }
}
