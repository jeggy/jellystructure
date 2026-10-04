package dev.jellystructure.tv

import dev.jellystructure.tv.SessionLoopback.Companion.loopback
import dev.jellystructure.tv.SessionLoopback.Companion.waitFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R372 (FR-R372-2/-3, owner decisions 2 and 4) and 304b — *Move to…* over the loopback: the session keeps its id, the
 * new place gets the queue 2 s back, the old place stops once the new one plays; a speaker goes by relay; the admin
 * moves on the owner's behalf. Specs: `SessionMoveIntegrationTest`.
 */
class SessionMoveIntegrationTest {
    @Test fun `move keeps the session id and stops the old place after the new one reports playing`() = loopback("move-app") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        val ps = open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video")
        val id = sessions.onStart(fresh(phone), "film-1", 2_890_000)
        val rev = sessions.get(id)!!.revision
        val r = post("/tv/playback/sessions/$id/move", """{"target_id":"tv-a","revision":$rev}""", phone)
        assertEquals(200, r.status, r.body)
        waitFor("the TV gets the session 2 s back") { ts.frames("session_load").any { id in it && "\"start_ms\":2888000" in it } }
        waitFor("the place line reads moving") { (ps.frames("session_state") + ps.frames("session_list")).any { "\"moving_to\":\"Den TV\"" in it } }
        val joined = sessions.onStart(fresh(tv), "film-1", 2_888_000)
        assertEquals(id, joined, "the session keeps its id")
        sessions.onProgress(fresh(tv), "film-1", 2_890_000, paused = false)
        waitFor("the phone is told to stop") { ps.frames("playstate_command").any { "Stop" in it } }
        assertEquals("tv-a", sessions.get(id)!!.targetId)
    }

    @Test fun `a stale move revision answers 409`() = loopback("move-stale") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val tv = device("tv-a", "u-anna", "tv", "android_tv")
        open(phone, "sessions,session_control", plays = "video,music,book")
        open(tv, "sessions,session_control", plays = "video")
        val id = sessions.onStart(fresh(phone), "film-1", 0)
        val r = post("/tv/playback/sessions/$id/move", """{"target_id":"tv-a","revision":${sessions.get(id)!!.revision + 3}}""", phone)
        assertEquals(409, r.status, r.body)
        assertTrue("stale" in r.body, r.body)
    }

    @Test fun `a move from the web app onto a speaker relays cast_relay_load`() = loopback("move-relay") {
        val web = device("web-a", "u-anna", "web", "web")
        val phone = device("phone-a", "u-anna", "phone", "phone")
        open(web, "sessions,session_control", plays = "video,music")
        val ps = open(phone, "sessions,session_control", plays = "video,music,book")
        kinds["song-1"] = SessionKind.MUSIC
        ps.out.send("""{"type":"cast_devices_seen","devices":[{"cast_device_id":"c-office","name":"Office","kind":"speaker"}]}""")
        waitFor("the reach is reported") { starter.reach.entries().isNotEmpty() }
        val id = sessions.onStart(fresh(web), "song-1", 60_000)
        val r = post("/tv/playback/sessions/$id/move", """{"target_id":"cast:c-office"}""", web)
        assertEquals(200, r.status, r.body)
        waitFor("the phone relays the launch for the same session") { ps.frames("cast_relay_load").any { id in it && "c-office" in it } }
    }

    @Test fun `the admin lists the owner's places and moves the session on the owner's behalf`() = loopback("move-admin") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video")
        val id = sessions.onStart(fresh(phone), "film-1", 600_000)
        val list = get("/tv/admin/playback/sessions/$id/targets", admin = true)
        assertEquals(200, list.status, list.body)
        assertTrue("\"tv-a\"" in list.body, list.body)
        assertEquals(401, get("/tv/admin/playback/sessions/$id/targets").status)
        val r = post("/tv/admin/playback/sessions/$id/move", """{"target_id":"tv-a"}""", admin = true)
        assertEquals(200, r.status, r.body)
        waitFor("the TV gets the session") { ts.frames("session_load").any { id in it } }
        assertEquals(400, post("/tv/admin/playback/sessions/$id/move", """{"target_id":"here"}""", admin = true).status)
    }
}
