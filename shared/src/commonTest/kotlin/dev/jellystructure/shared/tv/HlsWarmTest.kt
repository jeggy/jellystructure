package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-3103 (DEB-3093 FR-6) — what is fetched so a replacement's first segments exist before the player is reset to it. */
class HlsWarmTest {
    private val masterUrl = "http://host:9505/api/tv/stream/abc/master.m3u8?t=1"
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=40000000,RESOLUTION=3840x2160
        v0/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=1920x1080
        /api/tv/stream/abc/v1/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1280x720
        http://other/v2/index.m3u8
    """.trimIndent()

    @Test fun `variants are read with their bandwidth and made absolute`() {
        val v = HlsWarm.variants(master, masterUrl)
        assertEquals(listOf(40_000_000L, 12_000_000L, 4_000_000L), v.map { it.bandwidth })
        assertEquals("http://host:9505/api/tv/stream/abc/v0/index.m3u8", v[0].url)
        assertEquals("http://host:9505/api/tv/stream/abc/v1/index.m3u8", v[1].url)
        assertEquals("http://other/v2/index.m3u8", v[2].url)
    }

    @Test fun `the start rung is the one named or else the first listed`() {
        val v = HlsWarm.variants(master, masterUrl)
        assertEquals(12_000_000L, HlsWarm.startVariant(v, 12_000_000L)!!.bandwidth)
        assertEquals(40_000_000L, HlsWarm.startVariant(v, 999L)!!.bandwidth)
        assertEquals(40_000_000L, HlsWarm.startVariant(v, null)!!.bandwidth)
        assertNull(HlsWarm.startVariant(emptyList(), 1L))
    }

    @Test fun `a warm-up asks the start rung then the next one down then the lowest`() {
        val four = HlsWarm.variants(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=11455000\na/i.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=8000000\nb/i.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=4000000\nc/i.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1500000\nd/i.m3u8\n", masterUrl)
        assertEquals(listOf(4_000_000L, 1_500_000L), HlsWarm.fallbackOrder(four, 4_000_000L).map { it.bandwidth })
        assertEquals(listOf(11_455_000L, 8_000_000L, 1_500_000L), HlsWarm.fallbackOrder(four, 11_455_000L).map { it.bandwidth })
        assertEquals(listOf(8_000_000L, 4_000_000L, 1_500_000L), HlsWarm.fallbackOrder(four, 8_000_000L).map { it.bandwidth })
        assertEquals(listOf(1_500_000L), HlsWarm.fallbackOrder(four, 1_500_000L).map { it.bandwidth })
        assertEquals(emptyList(), HlsWarm.fallbackOrder(emptyList(), 1L))
    }

    private val mediaUrl = "http://host:9505/api/tv/stream/abc/v1/index.m3u8?t=2"
    private val base = "http://host:9505/api/tv/stream/abc/v1/"

    /** A whole-film playlist: [count] segments of [seconds] each, with an init segment. */
    private fun film(count: Int, seconds: String = "2.000", map: String? = "#EXT-X-MAP:URI=\"init.mp4\"") =
        "#EXTM3U\n#EXT-X-TARGETDURATION:2\n" + (map?.let { "$it\n" } ?: "") +
            (0 until count).joinToString("") { "#EXTINF:$seconds,\n$it.m4s\n" } + "#EXT-X-ENDLIST"

    @Test fun `a warm-up in the middle of a film takes the segment at the position and the next one and the init segment`() {
        val w = HlsWarm.segmentsAt(film(120), mediaUrl, 115_300, 2)
        assertEquals(listOf(base + "57.m4s", base + "58.m4s"), w.segments)
        assertEquals(base + "init.mp4", w.init)
        assertEquals(listOf(base + "init.mp4", base + "57.m4s", base + "58.m4s"), w.urls)
    }

    @Test fun `a position on a segment boundary is that segment and zero is the first two`() {
        assertEquals(listOf(base + "58.m4s", base + "59.m4s"), HlsWarm.segmentsAt(film(120), mediaUrl, 116_000, 2).segments)
        assertEquals(listOf(base + "0.m4s", base + "1.m4s"), HlsWarm.segmentsAt(film(120), mediaUrl, 0, 2).segments)
        assertEquals(listOf(base + "0.m4s", base + "1.m4s"), HlsWarm.segmentsAt(film(120), mediaUrl, 1_999, 2).segments)
    }

    @Test fun `uneven segment lengths are added up`() {
        val media = "#EXTM3U\n#EXTINF:4.0,\na.ts\n#EXTINF:1.5,\nb.ts\n#EXTINF:6.25,\nc.ts\n#EXTINF:2.0,\nd.ts\n#EXTINF:3.0,\ne.ts\n#EXT-X-ENDLIST"
        // 4.0 | 5.5 | 11.75 | 13.75 | 16.75
        assertEquals(listOf(base + "b.ts", base + "c.ts"), HlsWarm.segmentsAt(media, mediaUrl, 5_000, 2).segments)
        assertEquals(listOf(base + "c.ts", base + "d.ts"), HlsWarm.segmentsAt(media, mediaUrl, 5_500, 2).segments)
        assertEquals(listOf(base + "d.ts", base + "e.ts"), HlsWarm.segmentsAt(media, mediaUrl, 11_750, 2).segments)
    }

    @Test fun `a position past the end is the last segment alone`() {
        val w = HlsWarm.segmentsAt(film(10), mediaUrl, 500_000, 2)
        assertEquals(listOf(base + "9.m4s"), w.segments)
        assertEquals(listOf(base + "9.m4s"), HlsWarm.segmentsAt(film(10), mediaUrl, 18_999, 2).segments)
        assertEquals(listOf(base + "9.m4s"), HlsWarm.segmentsAt(film(10), mediaUrl, 20_000, 2).segments)
    }

    @Test fun `a playlist with no init segment has none`() {
        val w = HlsWarm.segmentsAt(film(10, "4.0", map = null).replace(".m4s", ".ts"), mediaUrl, 9_000, 2)
        assertNull(w.init)
        assertEquals(listOf(base + "2.ts", base + "3.ts"), w.segments)
        assertEquals(w.segments, w.urls)
    }

    @Test fun `the init segment in effect at the position is the one fetched and resolved against the playlist`() {
        val media = "#EXTM3U\n#EXT-X-MAP:URI=\"a/init.mp4\"\n#EXTINF:2,\n0.m4s\n#EXT-X-DISCONTINUITY\n#EXT-X-MAP:URI=\"/b/init.mp4\",BYTERANGE=\"720@0\"\n#EXTINF:2,\n1.m4s\n#EXTINF:2,\n2.m4s"
        assertEquals(base + "a/init.mp4", HlsWarm.segmentsAt(media, mediaUrl, 1_000, 2).init)
        assertEquals("http://host:9505/b/init.mp4", HlsWarm.segmentsAt(media, mediaUrl, 3_000, 2).init)
    }

    @Test fun `a master is not a media playlist and an empty one lists nothing`() {
        assertEquals(emptyList(), HlsWarm.segmentsAt(master, masterUrl, 0, 2).segments)
        assertEquals(emptyList(), HlsWarm.segmentsAt("#EXTM3U\n#EXT-X-ENDLIST", masterUrl, 0, 2).segments)
        assertNull(HlsWarm.segmentsAt("#EXTM3U\n#EXT-X-ENDLIST", masterUrl, 0, 2).init)
    }

    @Test fun `a master with no variants yields none`() {
        assertEquals(emptyList(), HlsWarm.variants("#EXTM3U\n#EXTINF:4,\nseg.ts", masterUrl))
    }
}
