package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.sessions.eventsFeaturesFor
import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.EVENTS_FEATURE_GROUP_CONTROL
import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R378 — what an Android TV relay sends, and what it declares. */
class TvRelayTest {
    private val json = RaviloWireJsonWithDefaults

    private fun songs(n: Int) = List(n) { i ->
        CastTrackItem(id = "song-$i".padEnd(32, '0'), title = "Ein sehr langer Liedtitel – Live at the Harbour (Remastered) $i".repeat(4),
            artist = "Harbour Lights", album = "Tide Tables", coverUrl = "/api/tv/music/albums/a$i/cover")
    }

    private fun relayLoad(tracks: List<CastTrackItem>, cur: Int) = CastLoadData(
        serverUrl = "https://media.example.org", code = "ABC123", itemId = tracks[cur].id, title = tracks[cur].title,
        kicker = "Harbour Lights", positionMs = 42_500L, tracks = tracks, currentIndex = cur, sessionId = "sess-9",
    )

    @Test
    fun `a relay's LOAD carries the session's hand-off once, as the media's customData`() {
        val load = relayLoad(songs(3), 1)
        val f = castRelayFrames(load, "q-1", json)
        assertEquals("ravilo://${load.itemId}", f.media["contentId"]!!.jsonPrimitive.content)
        assertEquals("audio/mpeg", f.media["contentType"]!!.jsonPrimitive.content)
        val custom = json.decodeFromJsonElement(CastLoadData.serializer(), f.media["customData"]!!)
        assertEquals("ABC123", custom.code)
        assertEquals("sess-9", custom.sessionId)
        assertEquals(1, custom.currentIndex)
        assertEquals(42.5, f.startSec, "the start in seconds")
        assertTrue(f.parts.isEmpty(), "a short queue goes whole")
    }

    @Test
    fun `a film's relay LOAD is HLS with the title card`() {
        val film = CastLoadData(serverUrl = "https://media.example.org", code = "XYZ789", itemId = "film-1", title = "Kite Weather", kicker = "2006", sessionId = "sess-2")
        val f = castRelayFrames(film, "q-2", json)
        assertEquals("application/x-mpegURL", f.media["contentType"]!!.jsonPrimitive.content)
        assertEquals("Kite Weather", f.media["metadata"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(0.0, f.startSec, "no position: from the start")
    }

    @Test
    fun `a queue too long for one message goes as a window and the rest in parts, as the Mac's sender sends it`() {
        val load = relayLoad(songs(900), 450)
        val f = castRelayFrames(load, "q-3", json)
        assertFalse(f.parts.isEmpty(), "R359: the rest follows in parts")
        val parts = f.parts.map { json.decodeFromString(CastCommand.serializer(), it) }
        assertTrue(parts.all { it.type == "queue_part" })
        val window = json.decodeFromJsonElement(CastLoadData.serializer(), f.media["customData"]!!)
        assertEquals(900, window.queueTotal)
        assertEquals("q-3", window.queueId)
    }

    @Test
    fun `a TV never declares group control, so room ops go to a phone (FR-R378-5)`() {
        val tv = eventsFeaturesFor(isTv = true, obeysSessionCommands = true, groupControl = true)
        assertFalse(EVENTS_FEATURE_GROUP_CONTROL in tv)
        assertEquals(setOf("sessions", "session_control"), tv)
    }
}
