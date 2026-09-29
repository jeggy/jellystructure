package dev.jellystructure.ravilo.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R335 (FR-R335-11) — the pieces of the Linux engine that need no libmpv. */
class MpvPlayerTest {
    private val trackList = """
        [{"id":1,"type":"video","src-id":0,"codec":"h264","default":true,"selected":true,"image":false},
         {"id":1,"type":"audio","src-id":1,"lang":"eng","codec":"aac","default":true,"demux-channel-count":2,"selected":true},
         {"id":2,"type":"audio","src-id":2,"title":"Kommentar","lang":"dan","codec":"ac3","default":false,"demux-channel-count":6},
         {"id":1,"type":"sub","src-id":3,"lang":"eng","codec":"subrip","default":false,"forced":false},
         {"id":2,"type":"sub","codec":"subrip","lang":"dan","external":true,"external-filename":"/x.srt","forced":true}]
    """.trimIndent()

    @Test
    fun `track-list maps to typed tracks in mpv's order`() {
        val tracks = MpvPlayer.parseTrackList(trackList)
        assertEquals(listOf("video", "audio", "audio", "sub", "sub"), tracks.map { it.type })
        val audio = tracks.filter { it.type == "audio" }.map { it.track }
        assertEquals(listOf(1, 2), audio.map { it.id })
        assertEquals("eng", audio[0].language); assertTrue(audio[0].isDefault); assertEquals(2, audio[0].channels)
        assertEquals("Kommentar", audio[1].title); assertEquals(6, audio[1].channels); assertEquals("ac3", audio[1].codec)
        val subs = tracks.filter { it.type == "sub" }.map { it.track }
        assertFalse(subs[0].external); assertTrue(subs[1].external); assertTrue(subs[1].forced)
        assertNull(subs[0].title)
    }

    @Test
    fun `a broken or empty track-list is no tracks, never an exception`() {
        assertEquals(emptyList(), MpvPlayer.parseTrackList("not json"))
        assertEquals(emptyList(), MpvPlayer.parseTrackList("[]"))
        assertEquals(emptyList(), MpvPlayer.parseTrackList("""[{"type":"audio"}]"""))   // no id
    }

    @Test
    fun `render params are laid out as libmpv reads them`() {
        val a = Mpv.cString("sw")
        val m = Mpv.params(Mpv.RENDER_PARAM_API_TYPE to a, Mpv.RENDER_PARAM_SW_STRIDE to null)
        assertEquals(Mpv.RENDER_PARAM_API_TYPE, m.getInt(0))
        assertEquals(a, m.getPointer(8))
        assertEquals(Mpv.RENDER_PARAM_SW_STRIDE, m.getInt(16))
        assertEquals(Mpv.RENDER_PARAM_INVALID, m.getInt(32))   // the terminator
        assertEquals("sw", a.getString(0))
    }

    @Test
    fun `without libmpv the engine is honestly absent`() {
        if (Mpv.lib != null) return   // a machine with libmpv exercises the bench instead
        val p = MpvPlayer(audioOnly = false)
        assertFalse(p.available)
        p.load("file:///nowhere.mkv")
        assertFalse(p.loaded)
        assertNull(p.takeFrame())
        assertEquals(MacPlayerState.EMPTY, p.state)
        p.release()
    }
}
