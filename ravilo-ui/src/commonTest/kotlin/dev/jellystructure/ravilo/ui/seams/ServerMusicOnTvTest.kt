package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import dev.jellystructure.shared.tv.SessionLoadEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R380 (found live on Stue TV, 2026-10-09) — music a phone hands to the TV app through the server (`session_load`):
 * *Playing from {device}* names the sender, and Home stops it as it stops a Cast LOAD's.
 */
class ServerMusicOnTvTest {
    @Test fun `session_load carries the sender's name, and an older server's carries none`() {
        val env = RaviloWireJsonWithDefaults.decodeFromString(SessionLoadEnvelope.serializer(),
            """{"type":"session_load","session_id":"s1","kind":"music","items":["a"],"sender_name":"Pixel 9 Pro"}""")
        assertEquals("Pixel 9 Pro", env.senderName)
        val old = RaviloWireJsonWithDefaults.decodeFromString(SessionLoadEnvelope.serializer(),
            """{"type":"session_load","session_id":"s1","kind":"music","items":["a"]}""")
        assertNull(old.senderName)
    }

    @Test fun `Home stops music the server started here and clears Playing from`() {
        var stopped = 0
        val before = TvCastChannel.stopServerMusic
        TvCastChannel.stopServerMusic = { stopped++ }
        try {
            TvCastChannel.startServerMusic(" Pixel 9 Pro ")
            assertEquals("Pixel 9 Pro", TvCastChannel.senderName.value)
            assertTrue(TvCastChannel.appLeftScreen(), "Home stops it")
            assertEquals(1, stopped)
            assertNull(TvCastChannel.senderName.value)
            assertFalse(TvCastChannel.appLeftScreen(), "nothing left to stop")
            assertEquals(1, stopped)
            TvCastChannel.startServerMusic("  ")
            assertNull(TvCastChannel.senderName.value, "a blank name is no name")
            TvCastChannel.appLeftScreen()
        } finally { TvCastChannel.stopServerMusic = before }
    }

    @Test fun `Home with nothing the server started stops nothing`() {
        var stopped = 0
        val before = TvCastChannel.stopServerMusic
        TvCastChannel.stopServerMusic = { stopped++ }
        try {
            assertFalse(TvCastChannel.appLeftScreen())
            assertEquals(0, stopped)
        } finally { TvCastChannel.stopServerMusic = before }
    }
}
