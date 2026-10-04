package dev.jellystructure.tv

import dev.jellystructure.tv.SessionLoopback.Companion.loopback
import dev.jellystructure.tv.SessionLoopback.Companion.waitFor
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R369 (FR-R369-1/-2, dev review items 2, 3, 4, 11) — commands through the server, over the loopback: the real routes,
 * bus and command service, fake devices as sockets. Specs: `SessionCommandIntegrationTest`.
 */
class SessionCommandIntegrationTest {
    @Test fun `a pause from the computer reaches the receiver as session_command and both apps see the paused report`() = loopback("cmd-pause") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val mac = device("mac-a", "u-anna", "web", "mac")
        val rx = device("rx-1", "u-anna", "cast", name = "Office")
        val ps = open(phone, "sessions,session_control")
        val ms = open(mac, "sessions")
        val rs = open(rx, "session_control")
        val id = sessions.onStart(fresh(rx), "film-1", 60_000)
        waitFor("the computer lists it") { ms.frames("session_list").any { id in it } }
        val rev = sessions.get(id)!!.revision
        val r = post("/tv/playback/sessions/$id/command", """{"op":"pause","revision":$rev}""", mac)
        assertEquals(202, r.status, r.body)
        waitFor("the receiver gets session_command pause") { rs.frames("session_command").any { "\"op\":\"pause\"" in it && id in it } }
        sessions.onProgress(fresh(rx), "film-1", 61_000, paused = true)
        waitFor("both apps see paused") {
            (ps.frames("session_state") + ps.frames("session_list")).any { "\"state\":\"paused\"" in it } &&
                (ms.frames("session_state") + ms.frames("session_list")).any { "\"state\":\"paused\"" in it }
        }
        // A socket with sessions but not session_control is never sent session_command.
        assertTrue(ms.frames("session_command").isEmpty())
    }

    @Test fun `an older receiver gets playstate_command and its detail lists only those ops`() = loopback("cmd-legacy") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val rx = device("rx-old", "u-anna", "cast", name = "Office")
        open(phone, "sessions,session_control")
        val rs = open(rx, null)
        val id = sessions.onStart(fresh(rx), "film-1", 0)
        val r = post("/tv/playback/sessions/$id/command", """{"op":"pause","revision":${sessions.get(id)!!.revision}}""", phone)
        assertEquals(202, r.status, r.body)
        waitFor("the old receiver gets playstate_command") { rs.frames("playstate_command").any { "Pause" in it } }
        assertTrue(rs.frames("session_command").isEmpty())
        val d = get("/tv/playback/sessions/$id", phone)
        assertEquals(200, d.status, d.body)
        assertTrue("\"pause\"" in d.body && "\"jump\"" !in d.body, d.body)
    }

    @Test fun `a stale seek answers 409 with the session`() = loopback("cmd-stale") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val rx = device("rx-1", "u-anna", "cast", name = "Office")
        open(phone, "sessions,session_control")
        open(rx, "session_control")
        val id = sessions.onStart(fresh(rx), "film-1", 0)
        val rev = sessions.get(id)!!.revision
        val r = post("/tv/playback/sessions/$id/command", """{"op":"seek","position_ms":5000,"revision":${rev + 7}}""", phone)
        assertEquals(409, r.status, r.body)
        assertTrue("stale" in r.body && id in r.body, r.body)
    }

    @Test fun `someone else's session is 403 with the household switch off and controllable with it on`() = loopback("cmd-household") {
        val anna = device("phone-a", "u-anna", "phone", "phone")
        val ben = device("phone-b", "u-ben", "phone", "phone")
        val rx = device("rx-1", "u-anna", "cast", name = "Office")
        open(anna, "sessions,session_control")
        open(ben, "sessions,session_control")
        val rs = open(rx, "session_control")
        val id = sessions.onStart(fresh(rx), "film-1", 0)
        assertEquals(403, post("/tv/playback/sessions/$id/command", """{"op":"pause"}""", ben).status)
        household = true
        assertEquals(202, post("/tv/playback/sessions/$id/command", """{"op":"pause"}""", ben).status)
        waitFor("Ben's pause reaches the receiver") { rs.frames("session_command").isNotEmpty() }
    }

    @Test fun `the admin's pause is recorded from the admin and the admin is never a controller`() = loopback("cmd-admin") {
        val rx = device("rx-1", "u-anna", "cast", name = "Office")
        val rs = open(rx, "session_control")
        val id = sessions.onStart(fresh(rx), "film-1", 0)
        val r = post("/tv/admin/playback/sessions/$id/command", """{"op":"pause"}""", admin = true)
        assertEquals(202, r.status, r.body)
        waitFor("the admin's pause reaches the receiver") { rs.frames("session_command").any { "\"source\":\"admin\"" in it } }
        assertTrue(control.controllerDevices(id).isEmpty())
        // Without the admin's cookie the route refuses.
        assertEquals(401, post("/tv/admin/playback/sessions/$id/command", """{"op":"pause"}""").status)
        delay(50)
    }
}
