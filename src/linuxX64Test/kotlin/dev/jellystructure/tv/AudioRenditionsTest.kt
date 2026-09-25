package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.shared.tv.AudioTrack
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R291 (FR-R291-2) — the composed master: Jellyfin's own variant with its muxed audio as the one default
 * rendition, every other audio track as an audio-only rendition on its OWN play session, and every one
 * of those sessions handed to phase 180's teardown (one DELETE stops one job — measured).
 */
class AudioRenditionsTest {
    private val jellyfinMaster = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=20000000,AVERAGE-BANDWIDTH=20000000,VIDEO-RANGE=SDR,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=23.976
        main.m3u8?DeviceId=dev&MediaSourceId=ms&PlaySessionId=psid&ApiKey=secret
    """.trimIndent()

    private val audio = listOf(
        AudioTrack(index = 1, language = "eng", label = "English - DTS - 5.1 - Default", isDefault = true),
        AudioTrack(index = 2, language = "dan", label = "Dansk \"tale\" - AC3 - Stereo"),
        AudioTrack(index = 3, language = "eng", label = "English - Commentary"),
    )

    private fun rendition(pos: Int, uri: String?) = AudioRenditions.Rendition(pos, audio[pos].index, audio[pos].label, audio[pos].language, uri)

    @Test
    fun `the variant joins the audio group and becomes absolute`() {
        val m = composeMaster(jellyfinMaster, "https://jf.example/videos/abc/", listOf(rendition(0, null), rendition(1, "https://jf.example/Audio/abc/main.m3u8?AudioStreamIndex=2")))
        val lines = m.lines()
        assertEquals("#EXTM3U", lines.first())
        val inf = lines.first { it.startsWith("#EXT-X-STREAM-INF:") }
        assertTrue(inf.endsWith(",AUDIO=\"aud\""), inf)
        assertEquals("https://jf.example/videos/abc/main.m3u8?DeviceId=dev&MediaSourceId=ms&PlaySessionId=psid&ApiKey=secret", lines[lines.indexOf(inf) + 1])
        assertEquals(1, m.split("#EXTM3U").size - 1, "one header, not Jellyfin's copied through")
    }

    @Test
    fun `the carried track is the only default and has no URI and the others never autoselect`() {
        val m = composeMaster(jellyfinMaster, "https://jf.example/videos/abc/", listOf(rendition(0, null), rendition(1, "https://x/2"), rendition(2, "https://x/3")))
        val media = m.lines().filter { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") }
        assertEquals(3, media.size)
        assertTrue(media[0].contains("DEFAULT=YES,AUTOSELECT=YES") && !media[0].contains("URI="), media[0])
        for (l in media.drop(1)) { assertContains(l, "DEFAULT=NO,AUTOSELECT=NO,URI=\""); assertFalse(l.contains("DEFAULT=YES"), l) }
        // NAME is unique (a player merges renditions sharing one) and keyed by position; a quote cannot break it.
        assertContains(media[0], "NAME=\"a0 English - DTS - 5.1 - Default\"")
        assertContains(media[1], "NAME=\"a1 Dansk 'tale' - AC3 - Stereo\"")
        assertContains(media[1], "LANGUAGE=\"dan\"")
    }

    @Test
    fun `a single track or an unknown carried track registers nothing`() = runBlocking {
        val r = AudioRenditions { null }
        val id = JellyfinDeviceIdentity(deviceId = "dev", deviceName = "TV")
        assertNull(r.register("https://jf", "abc", "ms", "tok", id, "P", "https://jf/videos/abc/master.m3u8", audio.take(1), 1, kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000))
        assertNull(r.register("https://jf", "abc", "ms", "tok", id, "P", "https://jf/videos/abc/master.m3u8", audio, 9, kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000))
    }

    @Test
    fun `each rendition has its own play session and teardown gets every one of them once`() = runBlocking {
        var fetched: String? = null
        val r = AudioRenditions { url -> fetched = url; jellyfinMaster }
        val identity = JellyfinDeviceIdentity(deviceId = "dev", deviceName = "TV")
        val streamId = r.register("https://jf", "abc", "ms", "tok", identity, "P", "https://jf/videos/abc/master.m3u8?ApiKey=tok", audio, 1, kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000)!!
        val m = r.master(streamId)!!
        assertEquals("https://jf/videos/abc/master.m3u8?ApiKey=tok", fetched)
        assertContains(m, "PlaySessionId=Pa2"); assertContains(m, "PlaySessionId=Pa3")
        assertFalse(m.contains("PlaySessionId=Pa1"), "the carried track is the video job's own audio — no job of its own")
        assertContains(m, "https://jf/Audio/abc/main.m3u8?MediaSourceId=ms&AudioStreamIndex=2")
        assertEquals(listOf("Pa2", "Pa3"), r.sessionsFor("P"))
        assertEquals(emptyList(), r.sessionsFor("P"), "forgotten once handed over")
        assertNull(r.master("not-an-id"))
    }
}
