package dev.jellystructure.tv

import dev.jellystructure.tv.SessionLoopback.Companion.loopback
import dev.jellystructure.tv.SessionLoopback.Companion.waitFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R371 (FR-R371-2/-4/-5, review items 7 and 8) — a group's rooms through the server, over the loopback: the master to
 * the receiver, a room to the app holding the Cast link when it can group (`group_control`), else to a relay app on
 * that network, which is told which Cast device to join. Specs: `SessionRoomsIntegrationTest`.
 */
class SessionRoomsIntegrationTest {
    private suspend fun SessionLoopback.musicOnOffice(minter: String): String {
        kinds["song-1"] = SessionKind.MUSIC
        val rx = device("rx-1", "u-anna", "cast", name = "Office")
        sessions.onReceiverRedeemed(fresh(rx), minter, "c-office", null)
        return sessions.onStart(fresh(rx), "song-1", 0)
    }

    @Test fun `a master set_volume from the web app reaches the receiver`() = loopback("rooms-master") {
        val web = device("web-a", "u-anna", "web", "web")
        open(web, "sessions,session_control")
        val rs = open(device("rx-1", "u-anna", "cast", name = "Office"), "session_control")
        val id = musicOnOffice("phone-a")
        assertEquals(202, post("/tv/playback/sessions/$id/command", """{"op":"set_volume","level":30}""", web).status)
        waitFor("the receiver sets its own level") { rs.frames("session_command").any { "set_volume" in it && "\"level\":30" in it } }
    }

    @Test fun `add_room from the web app reaches the link holder and its members report updates every controller`() = loopback("rooms-holder") {
        val web = device("web-a", "u-anna", "web", "web")
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val ws = open(web, "sessions,session_control")
        val ps = open(phone, "sessions,session_control,group_control", plays = "video,music,book")
        open(device("rx-1", "u-anna", "cast", name = "Office"), "session_control")
        val id = musicOnOffice("phone-a")
        val r = post("/tv/playback/sessions/$id/command", """{"op":"add_room","cast_device_id":"c-guest"}""", web)
        assertEquals(202, r.status, r.body)
        waitFor("the link holder gets add_room") { ps.frames("session_command").any { "add_room" in it && "c-guest" in it } }
        val members = """{"item_id":"song-1","members":[{"cast_device_id":"c-office","name":"Office","volume":70},{"cast_device_id":"c-guest","name":"Guest room","volume":50}]}"""
        assertEquals(200, post("/tv/playback/sessions/members", members, phone).status)
        waitFor("the web app sees both rooms") { (ws.frames("session_state") + ws.frames("session_list")).any { "Guest room" in it } }
    }

    @Test fun `a link holder that cannot group hands the room op to a relay app which is told the Cast device to join`() = loopback("rooms-relay") {
        val mac = device("mac-a", "u-anna", "web", "mac")
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val ms = open(mac, "sessions,session_control", plays = "video,music,book")   // the Mac sent the cast; no group_control
        val ps = open(phone, "sessions,session_control,group_control", plays = "video,music,book")
        open(device("rx-1", "u-anna", "cast", name = "Office"), "session_control")
        ps.out.send("""{"type":"cast_devices_seen","devices":[{"cast_device_id":"c-office","name":"Office","kind":"speaker"}]}""")
        waitFor("the phone's reach is reported") { starter.reach.entries().isNotEmpty() }
        val id = musicOnOffice("mac-a")
        assertEquals(202, post("/tv/playback/sessions/$id/command", """{"op":"set_volume","level":40,"cast_device_id":"c-office"}""", mac).status)
        waitFor("the relay phone gets the room op naming the session's Cast device") {
            ps.frames("session_command").any { "\"place_cast_device_id\":\"c-office\"" in it && "set_volume" in it }
        }
        assertTrue(ms.frames("session_command").isEmpty())
    }

    @Test fun `with no app able to reach the speakers the room op is 409 and the master still works`() = loopback("rooms-none") {
        val web = device("web-a", "u-anna", "web", "web")
        open(web, "sessions,session_control")
        val rs = open(device("rx-1", "u-anna", "cast", name = "Office"), "session_control")
        val id = musicOnOffice("phone-gone")
        val r = post("/tv/playback/sessions/$id/command", """{"op":"add_room","cast_device_id":"c-guest"}""", web)
        assertEquals(409, r.status, r.body)
        assertTrue("unreachable" in r.body, r.body)
        val d = get("/tv/playback/sessions/$id", web)
        assertTrue("\"add_room\"" !in d.body, d.body)
        assertEquals(202, post("/tv/playback/sessions/$id/command", """{"op":"set_volume","level":20}""", web).status)
        waitFor("the master still reaches the receiver") { rs.frames("session_command").any { "\"level\":20" in it } }
    }
}
