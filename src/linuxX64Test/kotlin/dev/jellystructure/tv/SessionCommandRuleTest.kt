package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.SessionCommandRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** R369 + 304b — which ops are revision-checked, where a command goes, who may control, and the command service. */
class SessionCommandRuleTest {
    private fun rec(owner: String = "u-anna", revision: Long = 5) = SessionRec(
        id = "s1", ownerUserId = owner, ownerName = "Anna", targetKind = "cast", targetId = "speaker-1", targetName = "Office",
        lane = "audio", kind = SessionKind.MUSIC, itemId = "song-1", bookId = null, queue = listOf(SessionItem("song-1")), queueIndex = 0,
        positionMs = 0, positionAt = 0, state = SessionState.PLAYING, options = SessionOptions(), revision = revision,
        startedByDeviceId = "pixel", jellyfinPlaySessionId = null, offline = false, endReason = null, endedBy = null,
        createdAt = 0, updatedAt = 0, endedAt = null,
    )

    @Test fun `next previous jump seek and every queue edit are checked`() {
        for (op in listOf("next", "previous", "jump", "seek", "queue_move", "queue_remove", "queue_add")) assertTrue(revisionChecked(op), op)
    }

    @Test fun `play pause stop volume and mute never are`() {
        for (op in listOf("play", "pause", "stop", "set_volume", "set_mute")) assertFalse(revisionChecked(op), op)
    }

    @Test fun `a stale seek and a stale next are refused`() {
        assertEquals(CommandVerdict.Stale, sessionCommandVerdict("seek", 4, rec()))
        assertEquals(CommandVerdict.Stale, sessionCommandVerdict("next", 4, rec()))
        assertEquals(CommandVerdict.Accept, sessionCommandVerdict("next", 5, rec()))
    }

    @Test fun `play pause and stop on an old revision are applied`() {
        for (op in listOf("play", "pause", "stop")) assertEquals(CommandVerdict.Accept, sessionCommandVerdict(op, 1, rec()), op)
    }

    @Test fun `a target that declared session_control gets session_command`() =
        assertEquals(CommandRoute.SessionCommand, commandRoute(SessionCommandRequest(op = "jump", index = 2), targetSocket = true, targetControl = true, senderControl = false))

    @Test fun `a target without it gets todays playstate_command or player_command`() {
        assertEquals(CommandRoute.LegacyPlaystate("Pause"), commandRoute(SessionCommandRequest(op = "pause"), true, false, false))
        assertEquals(CommandRoute.LegacyPlaystate("Seek", 30_000), commandRoute(SessionCommandRequest(op = "seek", positionMs = 30_000), true, false, false))
        assertEquals(CommandRoute.LegacyPlayerCommand("next"), commandRoute(SessionCommandRequest(op = "next"), true, false, false))
        assertEquals(CommandRoute.LegacyPlayerCommand("set_volume", "{\"volume\":40}"), commandRoute(SessionCommandRequest(op = "set_volume", level = 40), true, false, false))
    }

    @Test fun `without session_control jump shuffle repeat tracks and queue edits are not offered`() {
        for (op in listOf("jump", "set_shuffle", "set_repeat", "set_audio", "set_subtitle", "queue_move")) {
            assertEquals(CommandRoute.NotOffered, commandRoute(SessionCommandRequest(op = op, index = 1, to = 2, on = true, mode = "all"), true, false, false), op)
        }
        assertFalse("jump" in opsFor(targetControl = false))
        assertTrue("jump" in opsFor(targetControl = true))
    }

    @Test fun `a receiver in its reconnect gap goes through the attached sender phone`() =
        assertEquals(CommandRoute.ViaSender, commandRoute(SessionCommandRequest(op = "pause"), targetSocket = false, targetControl = false, senderControl = true))

    @Test fun `play on a cast session whose receiver closed is a relay load`() =
        assertEquals(CommandRoute.RelayLoad, commandRoute(SessionCommandRequest(op = "play"), false, false, false, relayAvailable = true, castTarget = true, targetOffline = true))

    @Test fun `no socket no sender and no relay app is unreachable`() =
        assertEquals(CommandRoute.Unreachable, commandRoute(SessionCommandRequest(op = "pause"), false, false, false))

    @Test fun `your own session is controllable`() = assertTrue(controllableBy("u-anna", false, rec(), householdControl = false, nearby = false, canSee = true))

    @Test fun `another members is not while the switch is off and is with it on`() {
        assertFalse(controllableBy("u-ben", false, rec(), householdControl = false, nearby = true, canSee = true))
        assertTrue(controllableBy("u-ben", false, rec(), householdControl = true, nearby = true, canSee = true))
    }

    @Test fun `the switch never widens the household`() =
        assertFalse(controllableBy("u-ben", false, rec(), householdControl = true, nearby = false, canSee = true))

    @Test fun `the admin controls every session whatever the switch says`() =
        assertTrue(controllableBy(null, true, rec(), householdControl = false, nearby = false, canSee = false))

    @Test fun `a session the viewer may not see is never controllable`() =
        assertFalse(controllableBy("u-ben", false, rec(), householdControl = true, nearby = true, canSee = false))

    private val dbPath = "/tmp/jellystructure-test-sescmd-${getpid()}.db"
    @AfterTest fun tearDown() { dev.jellystructure.db.closeLastDatabaseForTests(); for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") } }

    @Test fun `the command service authorises checks the revision and records from the admin`() = runBlocking {
        val db = createDatabase(dbPath)
        val devices = RaviloDeviceService(db)
        val sessions = PlaybackSessions(db)
        sessions.describe = { id, _ -> SessionKind.MUSIC to SessionItem(id, "Song") }
        val bus = TvEventBus(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val control = SessionControl(db, sessions, devices, bus)
        fun dev(id: String, user: String): DeviceData = devices.loginDevice(id, id, user, user, "jf", false, false, kind = "phone").first
        val anna = dev("pixel", "u-anna")
        val ben = dev("ben-phone", "u-ben")
        val id = sessions.onStart(anna, "song-1", 0)
        val rev = sessions.get(id)!!.revision
        assertIs<CommandResult.Forbidden>(control.command(id, SessionCommandRequest(op = "pause"), ben, admin = false))
        assertIs<CommandResult.Stale>(control.command(id, SessionCommandRequest(op = "next", revision = rev - 1), anna, admin = false))
        // No socket for the target and no sender: 409 unreachable — for the owner and for the admin.
        assertIs<CommandResult.Unreachable>(control.command(id, SessionCommandRequest(op = "pause", revision = rev), anna, admin = false))
        assertIs<CommandResult.Unreachable>(control.command(id, SessionCommandRequest(op = "pause"), null, admin = true))
        assertIs<CommandResult.NotFound>(control.command("nope", SessionCommandRequest(op = "pause"), anna, admin = false))
        Unit
    }

    @Test fun `a config without the key reads household control off and it round-trips`() = runBlocking {
        val path = "/tmp/jellystructure-test-household-${getpid()}.toml"
        val store = ConfigStore(path)
        assertFalse(store.current.ravilo.householdControl)
        store.update(store.current.copy(ravilo = store.current.ravilo.copy(householdControl = true)))
        val again = ConfigStore(path).also { it.load() }
        assertTrue(again.current.ravilo.householdControl)
        runCatching { platform.posix.remove(path) }
        Unit
    }

    @Test fun `a room op goes to the link holder else a relay app else nowhere`() {
        assertEquals(RoomRoute.LinkHolder, roomOpRoute(linkHolderControl = true, relayControl = true))
        assertEquals(RoomRoute.Relay, roomOpRoute(linkHolderControl = false, relayControl = true))
        assertEquals(RoomRoute.Unreachable, roomOpRoute(linkHolderControl = false, relayControl = false))
    }

    @Test fun `add_room remove_room and a rooms volume are room ops and the master is not`() {
        assertTrue(isRoomOp(SessionCommandRequest(op = "add_room", castDeviceId = "a")))
        assertTrue(isRoomOp(SessionCommandRequest(op = "remove_room", castDeviceId = "a")))
        assertTrue(isRoomOp(SessionCommandRequest(op = "set_volume", level = 30, castDeviceId = "a")))
        assertFalse(isRoomOp(SessionCommandRequest(op = "set_volume", level = 30)))
    }

    @Test fun `a members report replaces the room list and a missing room left`() {
        val a = dev.jellystructure.shared.tv.SessionRoom("a", "Office", 70)
        val b = dev.jellystructure.shared.tv.SessionRoom("b", "Kitchen", 50)
        val d = roomsDiff(listOf(a, b), listOf(a))
        assertEquals(listOf("b"), d.left.map { it.castDeviceId })
        assertTrue(d.added.isEmpty())
        assertEquals(listOf("b"), roomsDiff(listOf(a), listOf(a, b)).added.map { it.castDeviceId })
    }

    @Test fun `room ops are offered on a music cast only while an app can reach the speakers`() {
        assertTrue("add_room" in roomOps(music = true, reachable = true))
        assertTrue(roomOps(music = true, reachable = false).isEmpty())
        assertTrue(roomOps(music = false, reachable = true).isEmpty())
    }
}
