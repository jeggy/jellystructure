package dev.jellystructure.filefix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 314b/c — which tracks are our copies (and of what), what Remove takes out, and the rename after the folder. */
class FileFixRemoveRulesTest {
    private fun a(i: Int, codec: String, lang: String, title: String? = null, default: Boolean = false) = JellyfinAudio(i, codec, lang, title, default)

    @Test fun `a copy maps to its language's main original - the default one - never a commentary or another copy`() {
        val audio = listOf(
            a(1, "aac", "dan", "Stereo"),                 // an external sidecar: Jellyfin numbers it first
            a(2, "dts", "eng", "English", default = true),
            a(3, "ac3", "eng", "Commentary"),
            a(4, "truehd", "dan", "Dansk"),
            a(5, "eac3", "eng", "Surround 5.1"),
            a(6, "aac", "eng", "Stereo"),
        )
        assertEquals(mapOf(1 to 4, 5 to 2, 6 to 2), copySourcesOf(audio))
        assertEquals(emptyMap(), copySourcesOf(listOf(a(1, "aac", "dan", "Stereo"))), "a copy with no original stays its own")
        assertEquals(emptyMap(), copySourcesOf(listOf(a(1, "ac3", "eng", default = true), a(2, "aac", "eng", "Director's stereo mix"))), "not our name")
    }

    // What `mkvmerge -J` says for a file with a copy of ours (2026-10-08, a test file of 314a's job): statistics tags on
    // every track, ours on the added one.
    private val identify = """{"tracks": [
        {"id": 0, "type": "video", "properties": {"codec_id": "V_MPEGH/ISO/HEVC", "tag_bps": "1", "tag_duration": "1"}},
        {"id": 1, "type": "audio", "properties": {"codec_id": "A_TRUEHD", "track_name": "English", "tag_bps": "1"}},
        {"id": 2, "type": "audio", "properties": {"codec_id": "A_AAC", "track_name": "Stereo", "tag_bps": "1", "tag_jellystructure_added": "2026-10-08", "tag_jellystructure_copy_of": "123"}},
        {"id": 3, "type": "audio", "properties": {"codec_id": "A_EAC3", "track_name": "Surround 5.1", "tag_bps": "1", "tag_jellystructure_added": "2026-10-08"}},
        {"id": 4, "type": "audio", "properties": {"codec_id": "A_AAC", "track_name": "Stereo", "tag_bps": "1"}},
        {"id": 5, "type": "subtitles", "properties": {"codec_id": "S_TEXT/UTF8", "track_name": "Stereo"}}]}"""

    @Test fun `Remove finds only our tagged copies of its own kind`() {
        assertEquals(listOf(2), copyTrackIds(identify, FixKind.STEREO), "track 4 is named Stereo but isn't ours")
        assertEquals(listOf(3), copyTrackIds(identify, FixKind.SURROUND))
        assertEquals(emptyList(), copyTrackIds(identify, FixKind.DOLBY_VISION))
        assertEquals(emptyList(), copyTrackIds("not json", FixKind.STEREO))
        // An older mkvmerge without tags in -J: the name and codec decide.
        val untagged = """{"tracks": [{"id": 1, "type": "audio", "properties": {"codec_id": "A_AAC", "track_name": "Stereo"}}]}"""
        assertEquals(listOf(1), copyTrackIds(untagged, FixKind.STEREO))
    }

    @Test fun `the remux keeps every track but ours`() {
        val cmd = FileFixCommands.removeTracks("/lib/A (2020)/A (2020).mkv", listOf(2, 3), "/lib/A (2020)/.jellystructure/w.mkv")
        assertTrue(cmd.contains("mkvmerge -q -o '/lib/A (2020)/.jellystructure/w.mkv' --audio-tracks '!2,3' '/lib/A (2020)/A (2020).mkv'"), cmd)
    }

    private fun s(type: String, codec: String, lang: String? = null, default: Boolean = false) = StreamFacts(type, codec, lang, default)

    @Test fun `after a Remove every original stream is there in order - and none of ours`() {
        val before = listOf(s("video", "hevc", default = true), s("audio", "truehd", "eng", true), s("audio", "aac", "eng"), s("subtitle", "subrip", "dan"))
        val after = listOf(s("video", "hevc", default = true), s("audio", "truehd", "eng", true), s("subtitle", "subrip", "dan"))
        assertNull(verifyRemoved(before, setOf(2), after))
        assertNotNull(verifyRemoved(before, setOf(2), after.dropLast(1)), "a stream lost")
        assertNotNull(verifyRemoved(before, setOf(2), listOf(after[0], after[2], after[1])), "order changed")
        assertNotNull(verifyRemoved(before, setOf(2), listOf(after[0], after[1].copy(default = false), after[2])), "a flag changed")
    }

    @Test fun `a Dolby Vision original is renamed after its own folder - keeping its extension`() {
        assertEquals("/lib/B (2019)/B (2019).mkv", renamedToFolder("/lib/B (2019)/B.2019.2160p.UHD.BluRay.REMUX.mkv"))
        assertNull(renamedToFolder("/lib/B (2019)/B (2019).mkv"), "already named after it")
        assertEquals("/lib/B (2019)/B (2019).m2ts", renamedToFolder("/lib/B (2019)/disc.m2ts"))
    }
}
