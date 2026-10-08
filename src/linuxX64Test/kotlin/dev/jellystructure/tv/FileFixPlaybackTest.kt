package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinMediaSourceInfo
import dev.jellystructure.auth.JellyfinMediaStream
import dev.jellystructure.shared.tv.AudioTrack
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 314b/c — what a play needs from the files 314 adds: the audio list in the player's order with each copy folded
 * onto its source, a sidecar served by a capability id with byte ranges, and the picture version a device plays.
 */
class FileFixPlaybackTest {
    private fun audio(index: Int, codec: String, lang: String, title: String? = null, default: Boolean = false, external: Boolean = false, path: String? = null) =
        JellyfinMediaStream(type = "Audio", index = index, codec = codec, language = lang, title = title, displayTitle = title, isDefault = default, isExternal = external, path = path)

    // Jellyfin numbers the external sidecar first (index 1), then the container's own audio (2, 3).
    private val streams = listOf(
        JellyfinMediaStream(type = "Video", index = 0, codec = "hevc"),
        audio(1, "aac", "dan", "Stereo", external = true, path = "/media/films/A (2020)/A (2020).da.Stereo.mka"),
        audio(2, "truehd", "dan", "Dansk", default = true),
        audio(3, "ac3", "eng", "English"),
    )

    @Test fun `the container's tracks come first in their order - an external one after them - folded onto its source`() {
        val t = ticketAudioTracks(streams, includeExternal = true, externalUrls = mapOf(1 to "/api/tv/stream/x/sidecar.mka"))
        assertEquals(listOf(2, 3, 1), t.map { it.index })
        val copy = t.last()
        assertTrue(copy.external)
        assertEquals("/api/tv/stream/x/sidecar.mka", copy.externalUrl)
        assertEquals(2, copy.copyOf, "the Stereo copy is the Danish original's")
        assertNull(t[0].copyOf); assertNull(t[1].copyOf)
        assertTrue(t.take(2).none { it.external || it.externalUrl != null })
    }

    @Test fun `a client that can't merge a sidecar never sees the external track`() {
        val t = ticketAudioTracks(streams, includeExternal = false, externalUrls = emptyMap())
        assertEquals(listOf(2, 3), t.map { it.index })
    }

    @Test fun `a copy in the file folds too - a copy whose source is gone stays its own row`() {
        val inFile = listOf(audio(1, "eac3", "eng", "English", default = true), audio(2, "aac", "eng", "Stereo"))
        assertEquals(1, ticketAudioTracks(inFile, true, emptyMap()).single { it.index == 2 }.copyOf)
        val orphan = listOf(audio(1, "ac3", "dan", "Dansk", default = true), audio(2, "aac", "eng", "Stereo"))
        assertNull(ticketAudioTracks(orphan, true, emptyMap()).single { it.index == 2 }.copyOf, "no English original to fold onto")
    }

    @Test fun `a sidecar is found by its name beside the video's own path - only a mka - never outside the folder`() {
        assertEquals("/lib/A (2020)/A (2020).da.Stereo.mka", localSidecarPath("/lib/A (2020)/A (2020).mkv", "/media/films/A (2020)/A (2020).da.Stereo.mka"))
        assertNull(localSidecarPath("/lib/A (2020)/A (2020).mkv", "/media/films/A (2020)/A (2020).da.srt"))
        assertNull(localSidecarPath("/lib/A (2020)/A (2020).mkv", "/media/x/..mka"))
        assertNull(localSidecarPath("/lib/A (2020)/A (2020).mkv", null))
    }

    private val sources = listOf(
        JellyfinMediaSourceInfo(id = "orig", path = "/media/B (2019)/B (2019) - Remux-2160p.mkv", name = "Remux-2160p"),
        JellyfinMediaSourceInfo(id = "dv81", path = "/media/B (2019)/B (2019) - Dolby Vision.mkv", name = "Dolby Vision"),
    )

    @Test fun `a device without dual-layer Dolby Vision gets the 8 1 version - one with it the original - a request wins`() {
        assertEquals("dv81", chooseMediaSource(sources, null, deviceDecodesEl = false))
        assertEquals("orig", chooseMediaSource(sources, null, deviceDecodesEl = true))
        assertEquals("orig", chooseMediaSource(sources, "orig", deviceDecodesEl = false))
        assertEquals("dv81", chooseMediaSource(sources, "nope", deviceDecodesEl = false), "an unknown request is ignored")
        assertNull(chooseMediaSource(sources.take(1), null, false), "one source: nothing to choose")
        val other = listOf(JellyfinMediaSourceInfo(id = "a", path = "/m/C - 1080p.mkv"), JellyfinMediaSourceInfo(id = "b", path = "/m/C - 2160p.mkv"))
        assertEquals("a", chooseMediaSource(other, null, false), "no version of ours: Jellyfin's first")
    }

    @Test fun `the versions in plain words - the current one marked`() {
        val v = ticketVersions(sources, "dv81")
        assertEquals(listOf("Dolby Vision, full detail", "Dolby Vision"), v.map { it.label })
        assertEquals(listOf(false, true), v.map { it.current })
        assertEquals(emptyList(), ticketVersions(sources.take(1), "orig"))
        val other = listOf(JellyfinMediaSourceInfo(id = "a", name = "1080p"), JellyfinMediaSourceInfo(id = "b", name = "2160p"))
        assertEquals(listOf("1080p", "2160p"), ticketVersions(other, null).map { it.label })
    }

    @Test fun `a sidecar id is a capability that lives as long as its ticket`() = runBlocking {
        val s = SidecarStreams()
        val id = s.register("/lib/A.da.Stereo.mka", expiresAt = 2_000, now = 1_000)
        assertTrue(id.length >= 32)
        assertEquals("/lib/A.da.Stereo.mka", s.path(id, now = 1_500))
        assertEquals(id, s.register("/lib/A.da.Stereo.mka", expiresAt = 1_800, now = 1_100), "the same file, still valid: the same id")
        assertNotEquals(id, s.register("/lib/B.da.Stereo.mka", expiresAt = 2_000, now = 1_100))
        assertNull(s.path(id, now = 2_001), "expired")
        assertNull(s.path("unknown", now = 1_000))
    }

    @Test fun `byte ranges - open - suffix - clamped - and the ones answered with the whole file or 416`() {
        assertEquals(0L..99L, parseByteRange("bytes=0-99", 1_000))
        assertEquals(500L..999L, parseByteRange("bytes=500-", 1_000))
        assertEquals(900L..999L, parseByteRange("bytes=-100", 1_000))
        assertEquals(990L..999L, parseByteRange("bytes=990-5000", 1_000))
        assertNull(parseByteRange(null, 1_000))
        assertNull(parseByteRange("bytes=0-1,5-9", 1_000), "multi-range")
        assertNull(parseByteRange("bytes=1000-", 1_000), "past the end")
        assertNull(parseByteRange("bytes=9-3", 1_000))
        assertNull(parseByteRange("items=0-1", 1_000))
    }

    @Test fun `an external track's rendition is made from its own file - found by 314's naming`() {
        val t = AudioTrack(index = 1, language = "dan", label = "Stereo", codec = "aac", channels = 2, external = true)
        assertEquals("/lib/A (2020)/A (2020).dan.Stereo.mka", sidecarFileFor("/lib/A (2020)/A (2020).mkv", t) { true })
        val surround = t.copy(codec = "eac3", channels = 6, label = "Surround 5.1")
        assertEquals("/lib/A (2020)/A (2020).dan.Surround.mka", sidecarFileFor("/lib/A (2020)/A (2020).mkv", surround) { true })
        assertNull(sidecarFileFor("/lib/A (2020)/A (2020).mkv", t) { false }, "missing ⇒ no rendition guessed")
    }
}
