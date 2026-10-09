package dev.jellystructure.tv

import dev.jellystructure.tv.SessionLoopback.Companion.loopback
import dev.jellystructure.tv.SessionLoopback.Companion.waitFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R380 (found live on Stue TV, 2026-10-09) — music a phone hands to a TV app through the server (*Play on… ▸ Stue TV*,
 * `session_load`): the TV hears which device sent it (*Playing from {device}*), and the phone's own session ends and its
 * player stops, so *Playing everywhere* lists the song once.
 */
class SessionHandOffIntegrationTest {
    @Test fun `music handed to a TV app names the sender and leaves one session`() = loopback("handoff-music") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel 9 Pro")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        val ps = open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music")
        kinds["song-1"] = SessionKind.MUSIC
        kinds["song-2"] = SessionKind.MUSIC
        val mine = sessions.onStart(fresh(phone), "song-1", 60_000)
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"music","items":["song-1","song-2"],"start_ms":60000}""", phone)
        assertEquals(200, r.status, r.body)
        waitFor("the TV gets the queue with the sender's name") { ts.frames("session_load").any { "\"sender_name\":\"Pixel 9 Pro\"" in it } }
        waitFor("the phone is told to stop") { ps.frames("playstate_command").any { "Stop" in it } }
        assertFalse(sessions.get(mine)!!.live, "the phone's own session ended")
        // The phone's last report, on the wire before its stop, does not bring its row back.
        sessions.onProgress(fresh(phone), "song-1", 61_000, paused = false)
        val tvRow = sessions.onStart(fresh(tv), "song-1", 60_000)
        assertEquals(listOf(tvRow), sessions.all().filter { it.live }.map { it.id }, "one row in Playing everywhere")
        assertEquals("tv-a", sessions.get(tvRow)!!.targetId)
    }

    @Test fun `a film started on a TV app leaves the phone's music alone`() = loopback("handoff-film") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel 9 Pro")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        val ps = open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music")
        kinds["song-1"] = SessionKind.MUSIC
        val mine = sessions.onStart(fresh(phone), "song-1", 60_000)
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"film","items":["film-1"],"start_ms":0}""", phone)
        assertEquals(200, r.status, r.body)
        waitFor("the TV gets the film, named by its sender") { ts.frames("session_load").any { "film-1" in it && "\"sender_name\":\"Pixel 9 Pro\"" in it } }
        assertTrue(sessions.get(mine)!!.live, "the phone's music plays on")
        assertTrue(ps.frames("playstate_command").none { "Stop" in it })
    }

    @Test fun `only the caller's own music on itself is handed over`() = loopback("handoff-rule") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val other = device("phone-b", "u-anna", "phone", "phone")
        kinds["song-1"] = SessionKind.MUSIC
        val song = sessions.onStart(fresh(phone), "song-1", 0)
        val elsewhere = sessions.onStart(fresh(other), "song-1", 0)
        val all = sessions.all()
        assertEquals(listOf(song), handedOver(all, "phone-a", SessionKind.MUSIC, "new").map { it.id })
        assertEquals(emptyList(), handedOver(all, "phone-a", SessionKind.FILM, "new"), "a film start hands nothing over")
        assertEquals(emptyList(), handedOver(all, "phone-a", SessionKind.MUSIC, song), "never the new session itself")
        assertFalse(elsewhere in handedOver(all, "phone-a", SessionKind.MUSIC, "new").map { it.id })
    }
}
