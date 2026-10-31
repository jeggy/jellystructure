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

    @Test fun `the first segments of a media playlist are listed in order and made absolute`() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts?x=1\n#EXTINF:4.0,\nseg2.ts\n#EXT-X-ENDLIST"
        assertEquals(
            listOf("http://host:9505/api/tv/stream/abc/v1/seg0.ts", "http://host:9505/api/tv/stream/abc/v1/seg1.ts?x=1"),
            HlsWarm.firstSegments(media, "http://host:9505/api/tv/stream/abc/v1/index.m3u8?t=2", 2),
        )
    }

    @Test fun `a master is not a media playlist and an empty one lists nothing`() {
        assertEquals(emptyList(), HlsWarm.firstSegments(master, masterUrl, 2))
        assertEquals(emptyList(), HlsWarm.firstSegments("#EXTM3U\n#EXT-X-ENDLIST", masterUrl, 2))
    }

    @Test fun `a master with no variants yields none`() {
        assertEquals(emptyList(), HlsWarm.variants("#EXTM3U\n#EXTINF:4,\nseg.ts", masterUrl))
    }
}
