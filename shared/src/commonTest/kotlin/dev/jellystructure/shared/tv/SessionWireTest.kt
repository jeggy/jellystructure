package dev.jellystructure.shared.tv

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R368 + R369 — the sessions' wire: additive fields, the events-URL opt-in, the commands and their stale check. */
class SessionWireTest {
    private val lenient = Json { ignoreUnknownKeys = true }

    @Test fun aStreamTicketFromAnOlderServerDecodesWithoutASessionId() {
        val t = lenient.decodeFromString(StreamTicket.serializer(),
            """{"jellyfin_base_url":"http://x","access_token":"","item_id":"i","container":"mkv","direct_play":true,"hls_url":null,"start_position_ms":0,"subtitles":[],"audio":[],"trickplay_url":null,"expires_at":1}""")
        assertNull(t.sessionId)
    }

    @Test fun aRequestWithASessionIdDecodesWhereUnknownKeysAreIgnored() {
        val text = Json.encodeToString(PlaybackProgressRequest.serializer(), PlaybackProgressRequest("i", 5, sessionId = "ps-1"))
        assertTrue("\"session_id\":\"ps-1\"" in text)
        // An older reader without the field: ignored.
        val back = lenient.decodeFromString(PlaybackStopRequest.serializer(), """{"item_id":"i","position_ms":5,"session_id":"ps-1","future":1}""")
        assertEquals("ps-1", back.sessionId)
    }

    @Test fun sessionEventsRoundTripWithServerNowAndReconnecting() {
        val v = SessionView(id = "s", revision = 2, owner = SessionOwner("u", "Anna"), kind = "music", target = SessionTarget("cast", "d", "Office", "speaker"),
            state = "playing", reconnecting = true)
        val text = Json.encodeToString(SessionStateEnvelope.serializer(), SessionStateEnvelope(session = v, serverNowMs = 99))
        val back = lenient.decodeFromString(SessionStateEnvelope.serializer(), text)
        assertTrue(back.session.reconnecting)
        assertEquals(99, back.serverNowMs)
        assertEquals("session_state", back.type)
    }

    @Test fun theFeaturesQueryIsAbsentWithoutFeatures() {
        assertNull(eventsFeaturesQuery(emptySet()))
        assertEquals("session_control,sessions", eventsFeaturesQuery(setOf("sessions", "session_control")))
    }

    @Test fun sessionCommandsReadAsThePlayersOwnControls() {
        assertEquals(RemoteCommand.Pause, sessionRemoteCommand(SessionCommandRequest(op = "pause")))
        assertEquals(RemoteCommand.SeekTo(30_000), sessionRemoteCommand(SessionCommandRequest(op = "seek", positionMs = 30_000)))
        assertEquals(RemoteCommand.Jump(3), sessionRemoteCommand(SessionCommandRequest(op = "jump", index = 3)))
        assertEquals(RemoteCommand.SetShuffle(true), sessionRemoteCommand(SessionCommandRequest(op = "set_shuffle", on = true)))
        assertEquals(RemoteCommand.SetRepeat("one"), sessionRemoteCommand(SessionCommandRequest(op = "set_repeat", mode = "one")))
        assertEquals(RemoteCommand.SelectAudio(1), sessionRemoteCommand(SessionCommandRequest(op = "set_audio", index = 1)))
        assertEquals(RemoteCommand.QueueMove(1, 4), sessionRemoteCommand(SessionCommandRequest(op = "queue_move", index = 1, to = 4)))
        assertEquals(RemoteCommand.SetVolume(40), sessionRemoteCommand(SessionCommandRequest(op = "set_volume", level = 40)))
    }

    @Test fun anUnknownOpReadsAsNothing() = assertNull(sessionRemoteCommand(SessionCommandRequest(op = "teleport")))

    @Test fun aRoomsVolumeIsNotThePlayersOwn() = assertNull(sessionRemoteCommand(SessionCommandRequest(op = "set_volume", level = 40, castDeviceId = "room-1")))

    @Test fun aSessionCommandMapsOntoTheSendersCastCommandHandlers() {
        assertEquals("play_at", castCommandForSession(SessionCommandRequest(op = "jump", index = 2), music = true)?.type)
        assertEquals(2, castCommandForSession(SessionCommandRequest(op = "jump", index = 2), music = true)?.index)
        assertEquals("prev", castCommandForSession(SessionCommandRequest(op = "previous"), music = true)?.type)
        assertEquals("shuffle", castCommandForSession(SessionCommandRequest(op = "set_shuffle", on = true), music = true)?.type)
        assertEquals("subtitle", castCommandForSession(SessionCommandRequest(op = "set_subtitle", index = -1), music = false)?.type)
        assertNull(castCommandForSession(SessionCommandRequest(op = "pause"), music = true))
    }

    private fun env(op: String, item: String? = null, index: Int? = null, rev: Int? = null) =
        SessionCommandEnvelope(sessionId = "s", command = SessionCommandRequest(op = op), expectItem = item, expectIndex = index, queueRev = rev)

    @Test fun aMismatchedExpectedItemOrIndexIsStale() {
        assertTrue(sessionCommandIsStale(env("next", item = "song-1"), currentItemId = "song-2", currentIndex = 1, queueRev = 0))
        assertTrue(sessionCommandIsStale(env("next", index = 0), "song-1", 1, 0))
        assertFalse(sessionCommandIsStale(env("next", item = "song-1", index = 0), "song-1", 0, 0))
    }

    @Test fun aMismatchedQueueRevIsStale() {
        assertTrue(sessionCommandIsStale(env("queue_move", rev = 3), "a", 0, 4))
        assertFalse(sessionCommandIsStale(env("queue_move", rev = 4), "a", 0, 4))
    }

    @Test fun noExpectationBehavesAsToday() {
        assertFalse(sessionCommandIsStale(env("next"), "a", 0, 4))
        assertFalse(sessionCommandIsStale(env("pause", item = "zzz"), "a", 0, 4), "play and pause are never stale")
    }

    @Test fun castCommandWithoutExpectIndexDecodesAsTodayAndAnOlderReaderIgnoresIt() {
        val old = lenient.decodeFromString(CastCommand.serializer(), """{"type":"next"}""")
        assertNull(old.expectIndex)
        val text = Json.encodeToString(CastCommand.serializer(), CastCommand("next", expectIndex = 4, expectItem = "song-5"))
        assertTrue("\"expect_index\":4" in text)
        val read = lenient.decodeFromString(CastCommand.serializer(), text)
        assertEquals(4, read.expectIndex)
    }
}
