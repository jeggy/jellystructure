package dev.jellystructure.ravilo.ui.music

import kotlin.test.Test
import kotlin.test.assertEquals

class MusicMimeTest {
    @Test
    fun `a direct-played file is named by its container and a transcode by nothing`() {
        assertEquals("audio/flac", musicMimeFor(true, "flac"))
        assertEquals("audio/mpeg", musicMimeFor(true, "mp3"))
        assertEquals("audio/mp4", musicMimeFor(true, "mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("audio/mp4", musicMimeFor(true, "m4a"))
        assertEquals("audio/aac", musicMimeFor(true, "aac"))
        assertEquals("audio/wav", musicMimeFor(true, "wav"))
        assertEquals("", musicMimeFor(true, "wma"), "unknown: AVPlayer guesses (the server converts WMA anyway)")
        assertEquals("", musicMimeFor(false, "mp3"), "a transcode is HLS")
    }
}
