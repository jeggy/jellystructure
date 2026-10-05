package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R368 — the pure rules behind the sessions: lanes, joining, what counts as a change, icons and each viewer's view. */
class PlaybackSessionRulesTest {
    private fun rec(
        owner: String = "u-anna", target: String = "speaker-1", kind: String = SessionKind.MUSIC, state: String = SessionState.PLAYING,
        position: Long = 10_000, at: Long = 0, item: String = "song-1", ended: Long? = null,
    ) = SessionRec(
        id = "s1", ownerUserId = owner, ownerName = "Anna", targetKind = "cast", targetId = target, targetName = "Living room",
        lane = sessionLane(kind), kind = kind, itemId = item, bookId = null,
        queue = listOf(SessionItem(item, "Paper Harbour", "The Lanterns", "/art", 200_000)), queueIndex = 0,
        positionMs = position, positionAt = at, state = state, options = SessionOptions(), revision = 3,
        startedByDeviceId = "pixel", jellyfinPlaySessionId = null, offline = false, endReason = null, endedBy = null,
        createdAt = 0, updatedAt = 0, endedAt = ended,
    )

    private fun device(id: String, user: String, address: String? = "198.51.100.7") = DeviceData(
        deviceId = id, deviceToken = "t-$id", jellyfinUserId = user, jellyfinUsername = user.removePrefix("u-"),
        jellyfinUserToken = "jf", isAdmin = false, lastPublicAddress = address,
    )

    @Test fun `a film and a song on one device are two lanes`() {
        assertEquals("video", sessionLane(SessionKind.FILM))
        assertEquals("video", sessionLane(SessionKind.EPISODE))
        assertEquals("audio", sessionLane(SessionKind.MUSIC))
        assertEquals("audio", sessionLane(SessionKind.AUDIOBOOK))
    }

    @Test fun `the same owner joins the live session`() =
        assertEquals(StartDecision.Join("s1"), startDecision(rec(), "u-anna"))

    @Test fun `another owner on the same receiver replaces the first`() =
        assertEquals(StartDecision.Replace("s1"), startDecision(rec(), "u-ben"))

    @Test fun `no live session creates one`() {
        assertEquals(StartDecision.Create, startDecision(null, "u-anna"))
        assertEquals(StartDecision.Create, startDecision(rec(ended = 5), "u-anna"))
    }

    @Test fun `a heartbeat is not a change`() {
        // 10 s later, playing, the position moved on by 10 s (± 3 s).
        assertFalse(isSessionChange(rec(position = 10_000, at = 0), "song-1", 20_000, paused = false, nowMs = 10_000))
        assertFalse(isSessionChange(rec(position = 10_000, at = 0), "song-1", 22_500, paused = false, nowMs = 10_000))
        // Paused: the position stands still.
        assertFalse(isSessionChange(rec(state = SessionState.PAUSED, position = 10_000, at = 0), "song-1", 10_000, paused = true, nowMs = 30_000))
    }

    @Test fun `a jump of more than three seconds is a seek`() =
        assertTrue(isSessionChange(rec(position = 10_000, at = 0), "song-1", 60_000, paused = false, nowMs = 10_000))

    @Test fun `a pause an item and an option are changes`() {
        assertTrue(isSessionChange(rec(), "song-1", 10_000, paused = true, nowMs = 0))
        assertTrue(isSessionChange(rec(), "song-2", 0, paused = false, nowMs = 0))
        assertTrue(isSessionChange(rec(state = SessionState.STARTING), "song-1", 10_000, paused = false, nowMs = 0))
    }

    @Test fun `every platform maps to its icon`() {
        assertEquals("phone", targetIcon("phone", "phone"))
        assertEquals("computer", targetIcon("mac", "tv"))
        assertEquals("computer", targetIcon("linux", "tv"))
        assertEquals("computer", targetIcon("web", "web"))
        assertEquals("tv", targetIcon("android-tv", "tv"))
        assertEquals("speaker", targetIcon("cast-audio", "cast"))
        assertEquals("tv", targetIcon("cast", "cast"))
        assertEquals("display", targetIcon("cast", "cast", smallScreen = true))
    }

    private val anna = device("pixel", "u-anna")
    private val benPhone = device("ben-phone", "u-ben")

    @Test fun `a viewers own session is listed in full and is mine`() {
        val v = assertNotNull(sessionViewFor(SessionViewer(anna, anna.lastPublicAddress), rec(), "203.0.113.9", canSee = true, castMintedBy = null))
        assertTrue(v.mine)
        assertEquals("Paper Harbour", v.title)
        assertEquals(10_000, v.positionMs)
    }

    @Test fun `another member on the same network is listed by first name and is not controllable`() {
        val v = assertNotNull(sessionViewFor(SessionViewer(benPhone, "198.51.100.7"), rec(), "198.51.100.7", canSee = true, castMintedBy = null))
        assertFalse(v.mine)
        assertEquals("Anna", v.owner.name)
        assertFalse(v.controllable)
    }

    @Test fun `another member on another network is not listed`() =
        assertNull(sessionViewFor(SessionViewer(benPhone, "203.0.113.50"), rec(), "198.51.100.7", canSee = true, castMintedBy = null))

    @Test fun `a viewers own session on another network is still listed`() {
        assertNotNull(sessionViewFor(SessionViewer(anna, "203.0.113.50"), rec(), "198.51.100.7", canSee = true, castMintedBy = null))
    }

    @Test fun `a title the viewer may not see shows person place and state only`() {
        val v = assertNotNull(sessionViewFor(SessionViewer(benPhone, "198.51.100.7"), rec(), "198.51.100.7", canSee = false, castMintedBy = null))
        assertNull(v.title); assertNull(v.subtitle); assertNull(v.artwork); assertNull(v.positionMs); assertNull(v.durationMs)
        assertEquals("Anna", v.owner.name)
        assertEquals("Living room", v.target.name)
        assertEquals(SessionState.PLAYING, v.state)
    }

    @Test fun `here is true only on the target device`() {
        val speaker = device("speaker-1", "u-anna")
        assertTrue(assertNotNull(sessionViewFor(SessionViewer(speaker, speaker.lastPublicAddress), rec(), "x", true, null)).here)
        assertFalse(assertNotNull(sessionViewFor(SessionViewer(anna, anna.lastPublicAddress), rec(), "x", true, null)).here)
    }

    @Test fun `controllable is here or the live cast this device minted`() {
        val other = device("ipad", "u-anna")
        assertTrue(assertNotNull(sessionViewFor(SessionViewer(anna, null), rec(), null, true, castMintedBy = "pixel")).controllable)
        assertFalse(assertNotNull(sessionViewFor(SessionViewer(other, null), rec(), null, true, castMintedBy = "pixel")).controllable)
        // Once the cast ends, nobody controls it.
        assertFalse(assertNotNull(sessionViewFor(SessionViewer(anna, null), rec(ended = 9, state = SessionState.ENDED), null, true, castMintedBy = "pixel")).controllable)
    }

    @Test fun `a reconnecting session is judged by heartbeat alone`() {
        assertFalse(shouldForceStop(heartbeatStale = false, needsSocket = true, socketOpen = false, reconnecting = true))
        assertTrue(shouldForceStop(heartbeatStale = true, needsSocket = true, socketOpen = false, reconnecting = true))
        assertTrue(shouldForceStop(heartbeatStale = false, needsSocket = true, socketOpen = false, reconnecting = false))
        assertFalse(shouldForceStop(heartbeatStale = false, needsSocket = false, socketOpen = false, reconnecting = false))
    }

    @Test fun `only the sessions feature is recognised`() {
        assertEquals(setOf("sessions"), parseEventFeatures("sessions"))
        assertEquals(setOf("sessions", "session_control"), parseEventFeatures("sessions,session_control"))
        assertEquals(emptySet(), parseEventFeatures(null))
        assertEquals(emptySet(), parseEventFeatures(" "))
        assertEquals(emptySet(), parseEventFeatures("teleport"))
    }

    @Test fun `ended today starts at local midnight`() {
        val day = 24L * 60 * 60_000
        val utcMidnight = 20_000 * day
        // UTC: 00:01 → midnight; an hour ahead (UTC+1): local midnight is 23:00 UTC the day before.
        assertEquals(utcMidnight, endedTodaySince(utcMidnight + 60_000, 0))
        assertEquals(utcMidnight - 60 * 60_000, endedTodaySince(utcMidnight + 60_000, 60 * 60_000))
        assertEquals(utcMidnight, endedTodaySince(utcMidnight + day - 1, 0))
    }

    @Test fun `the place line names the rooms in the order they joined`() {
        val rooms = listOf(dev.jellystructure.shared.tv.SessionRoom("a", "Living room"), dev.jellystructure.shared.tv.SessionRoom("b", "Office"), dev.jellystructure.shared.tv.SessionRoom("c", "Kitchen"))
        assertEquals("Living room", placeName(rec()))
        assertEquals("Office", placeName(rec().copy(options = SessionOptions(rooms = rooms.drop(1).take(1)))))
        assertEquals("Living room + Office", placeName(rec().copy(options = SessionOptions(rooms = rooms.take(2)))))
        assertEquals("Living room + 2", placeName(rec().copy(options = SessionOptions(rooms = rooms))))
    }

    @Test fun `a start decision is typed`() { assertIs<StartDecision.Join>(startDecision(rec(), "u-anna")) }
}
