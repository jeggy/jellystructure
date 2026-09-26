package dev.jellystructure.tv

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
 * rendition, and every other audio track as an audio-only rendition this server makes itself — with the
 * track mapped, which Jellyfin's audio endpoint never does (2026-09-26).
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
        val m = composeMaster(jellyfinMaster, "https://jf.example/videos/abc/", listOf(rendition(0, null), rendition(1, "audio/1/main.m3u8")))
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
    fun `renditions are made in the codec the variant's audio is in`() = runBlocking {
        // Measured on the stue TV 2026-09-26: a carried AC3 track is copied (`ac-3`), and AAC renditions
        // beside it failed the first switch ("Unable to bind a sample queue to TrackGroup with MIME type audio/ac3").
        val ac3 = jellyfinMaster.replace("mp4a.40.2", "ac-3")
        assertEquals("ac3", renditionAudioCodec(ac3))
        assertEquals("eac3", renditionAudioCodec(jellyfinMaster.replace("mp4a.40.2", "ec-3")))
        assertEquals("aac", renditionAudioCodec(jellyfinMaster))
        assertEquals("mp3", renditionAudioCodec(jellyfinMaster.replace("mp4a.40.2", "mp4a.40.34")))
        assertEquals("aac", renditionAudioCodec(jellyfinMaster.replace(",mp4a.40.2", "")), "no audio codec at all")
        assertContains(renditionCommand(src(2, 6), "ac3", 10, "/d"), "-c:a ac3 -b:a 640k -ac 6 ")
        assertContains(renditionCommand(src(2, 2), "aac", 10, "/d"), "-c:a aac -b:a 192k -ac 2 ")
        assertContains(renditionCommand(src(1, 8), "aac", 10, "/d"), "-c:a aac -b:a 640k -ac 6 ", message = "7.1 is folded to 5.1")
        assertContains(renditionCommand(src(2, 6), "mp3", 10, "/d"), "-c:a libmp3lame -b:a 320k -ac 2 ")
    }

    private fun src(stream: Int, channels: Int?) = RenditionSource("/mnt/media/it's a film.mkv", stream, channels, 6_756_352)

    @Test
    fun `a rendition job maps the picked track and nothing else — on Jellyfin's timing`() {
        // 2026-09-26: Jellyfin's audio-only job had no -map at all — ffmpeg took the track with the most
        // channels (English TrueHD) whatever was asked, plus a subtitle stream that cut empty segments.
        val cmd = renditionCommand(src(2, 6), "aac", 100, "/tmp/js-renditions/x")
        assertContains(cmd, "-ss 300.000 -i '/mnt/media/it'\\''s a film.mkv' -map 0:2 -sn -dn -vn ")
        assertContains(cmd, "-copyts -avoid_negative_ts disabled")
        assertContains(cmd, "-f hls -max_delay 5000000 -hls_time 3 -hls_segment_type mpegts -hls_flags temp_file -start_number 100 ")
        assertContains(cmd, "-hls_segment_filename '/tmp/js-renditions/x/s%d.ts'")
        assertFalse(cmd.contains(" -t "), "with -copyts an output -t counts from the copied timestamps and stops at once (measured)")
    }

    @Test
    fun `a rendition playlist is the whole file in 3 s segments`() {
        val p = renditionPlaylist(7_500)
        assertEquals(listOf("#EXTINF:3.000,", "0.ts", "#EXTINF:3.000,", "1.ts", "#EXTINF:1.500,", "2.ts"), p.lines().filter { it.startsWith("#EXTINF") || it.endsWith(".ts") })
        assertContains(p, "#EXT-X-PLAYLIST-TYPE:VOD")
        assertTrue(p.trimEnd().endsWith("#EXT-X-ENDLIST"))
        assertEquals(2253, renditionPlaylist(6_756_352).lines().count { it.endsWith(".ts") }, "Now You See Me: 1:52:36.352")
    }

    @Test
    fun `a single track or an unknown carried track registers nothing`() = runBlocking {
        val r = AudioRenditions { null }
        val later = kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000
        assertNull(r.register("P", "https://jf/videos/abc/master.m3u8", audio.take(1), 1, later, "/m/f.mkv", 60_000))
        assertNull(r.register("P", "https://jf/videos/abc/master.m3u8", audio, 9, later, "/m/f.mkv", 60_000))
        assertNull(r.register("P", "https://jf/videos/abc/master.m3u8", audio, 1, later, "/m/f.mkv", 0), "no length, no playlist")
    }

    @Test
    fun `the renditions are this server's own — next to the master`() = runBlocking {
        var fetched: String? = null
        val r = AudioRenditions { url -> fetched = url; jellyfinMaster.replace("mp4a.40.2", "ac-3") }
        val later = kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000
        val streamId = r.register("P", "https://jf/videos/abc/master.m3u8?ApiKey=tok", audio, 1, later, "/m/f.mkv", 60_000)!!
        val m = r.master(streamId)!!
        assertEquals("https://jf/videos/abc/master.m3u8?ApiKey=tok", fetched)
        assertContains(m, "URI=\"audio/1/main.m3u8\""); assertContains(m, "URI=\"audio/2/main.m3u8\"")
        assertFalse(m.contains("/Audio/"), "never Jellyfin's audio endpoint")
        assertNull(r.playlist(streamId, 0), "the carried track is the video's own audio — no playlist of its own")
        assertEquals(20, r.playlist(streamId, 1)!!.lines().count { it.endsWith(".ts") })
        assertNull(r.playlist(streamId, 7))
        assertNull(r.master("not-an-id"))
        assertNull(r.segment(streamId, 1, 20), "past the end of the file")
    }
}
