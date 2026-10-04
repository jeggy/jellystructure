package dev.jellystructure.tv

import dev.jellystructure.tv.SessionLoopback.Companion.loopback
import dev.jellystructure.tv.SessionLoopback.Companion.waitFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R370 (FR-R370-1/-3/-4/-5, owner decision 1, review items 3, 6, 10) — one *Play on…* list and starting anywhere, over
 * the loopback. Specs: `PlaybackStartIntegrationTest`.
 */
class PlaybackStartIntegrationTest {
    @Test fun `GET playback targets lists the apps and the speakers this viewer can reach`() = loopback("start-targets") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        val ps = open(phone, "sessions,session_control", plays = "video,music,book")
        open(tv, "sessions,session_control", plays = "video")
        ps.out.send("""{"type":"cast_devices_seen","devices":[{"cast_device_id":"c-office","name":"Office","kind":"speaker"}]}""")
        waitFor("the reach is reported") { starter.reach.entries().isNotEmpty() }
        val r = get("/tv/playback/targets", phone)
        assertEquals(200, r.status, r.body)
        assertTrue("\"tv-a\"" in r.body && "Den TV" in r.body, r.body)
        assertTrue("\"cast:c-office\"" in r.body, r.body)
    }

    @Test fun `a start on an app target sends session_load and the session is starting`() = loopback("start-app") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video")
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"film","items":["film-1"],"start_ms":1000}""", phone)
        assertEquals(200, r.status, r.body)
        waitFor("the TV gets session_load") { ts.frames("session_load").any { "film-1" in it } }
        val s = sessions.all().single { it.live }
        assertEquals("tv-a", s.targetId)
        assertTrue(ts.frames("session_load").single().contains(s.id))
        // The TV's first report joins the starting row (the same id).
        val joined = sessions.onStart(fresh(tv), "film-1", 1000)
        assertEquals(s.id, joined)
    }

    @Test fun `a start on a speaker from the web app relays cast_relay_load to the Android app that sees it`() = loopback("start-relay") {
        val web = device("web-a", "u-anna", "web", "web")
        val phone = device("phone-a", "u-anna", "phone", "phone")
        open(web, "sessions,session_control", plays = "video")
        val ps = open(phone, "sessions,session_control", plays = "video,music,book")
        kinds["song-1"] = SessionKind.MUSIC
        ps.out.send("""{"type":"cast_devices_seen","devices":[{"cast_device_id":"c-office","name":"Office","kind":"speaker"}]}""")
        waitFor("the reach is reported") { starter.reach.entries().isNotEmpty() }
        val r = post("/tv/playback/sessions", """{"target_id":"cast:c-office","kind":"music","items":["song-1"]}""", web)
        assertEquals(200, r.status, r.body)
        waitFor("the phone is asked to launch it") { ps.frames("cast_relay_load").any { "c-office" in it && "song-1" in it } }
        assertTrue("\"load_here\":true" !in r.body, r.body)
    }

    @Test fun `with no relay app online the speaker start is refused as not reachable`() = loopback("start-norelay") {
        val web = device("web-a", "u-anna", "web", "web")
        open(web, "sessions,session_control", plays = "video")
        kinds["song-1"] = SessionKind.MUSIC
        val r = post("/tv/playback/sessions", """{"target_id":"cast:c-office","kind":"music","items":["song-1"]}""", web)
        assertEquals(409, r.status, r.body)
        assertTrue("unreachable" in r.body, r.body)
        assertTrue(sessions.all().none { it.live })
    }

    @Test fun `replace ends the busy session with end_reason replaced and keeps its resume point`() = loopback("start-replace") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video")
        val busy = sessions.onStart(fresh(tv), "film-1", 1_200_000)
        val refused = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"film","items":["film-2"]}""", phone)
        assertEquals(409, refused.status, refused.body)
        assertTrue("busy" in refused.body, refused.body)
        val rev = sessions.get(busy)!!.revision
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"film","items":["film-2"],"replace":{"session_id":"$busy","revision":$rev}}""", phone)
        assertEquals(200, r.status, r.body)
        val old = assertNotNull(sessions.get(busy))
        assertEquals("replaced", old.endReason)
        assertEquals(1_200_000, old.positionMs)
        waitFor("the old film is stopped and the new one loaded") {
            ts.frames("playstate_command").any { "Stop" in it } && ts.frames("session_load").any { "film-2" in it }
        }
    }

    @Test fun `replace across users is 403 with the switch off and allowed with it on`() = loopback("start-cross") {
        val ben = device("phone-b", "u-ben", "phone", "phone")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(ben, "sessions,session_control", plays = "video,music,book")
        open(tv, "sessions,session_control", plays = "video")
        val busy = sessions.onStart(fresh(tv), "film-1", 0)
        val body = """{"target_id":"tv-a","kind":"film","items":["film-2"],"replace":{"session_id":"$busy"}}"""
        assertEquals(403, post("/tv/playback/sessions", body, ben).status)
        household = true
        assertEquals(200, post("/tv/playback/sessions", body, ben).status)
    }
}
