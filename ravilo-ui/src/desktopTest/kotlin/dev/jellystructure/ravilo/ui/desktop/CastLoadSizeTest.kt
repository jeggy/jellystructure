package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.castv2.CastMessage
import dev.jellystructure.ravilo.castv2.CastNamespaces
import dev.jellystructure.ravilo.castv2.DEFAULT_SENDER
import dev.jellystructure.ravilo.castv2.castFrameBytes
import dev.jellystructure.ravilo.castv2.castLoadBody
import dev.jellystructure.ravilo.ui.seams.castLoadMedia
import dev.jellystructure.shared.tv.CAST_MESSAGE_BUDGET_BYTES
import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import dev.jellystructure.shared.tv.castLoadLog
import dev.jellystructure.shared.tv.castLoadPlan
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R359 (FR-R359-1) — every frame the Mac's sender puts on the wire for a music cast, measured as it goes out. */
class CastLoadSizeTest {
    private val json = RaviloWireJsonWithDefaults

    private fun songs(n: Int, long: Boolean) = List(n) { i ->
        CastTrackItem(
            id = (i * 2654435761L).toString(16).padStart(32, '0').takeLast(32),
            title = if (long) "Ein sehr langer Liedtitel – Live at the Harbour (Remastered) · ".repeat(5) + i else "Song $i",
            artist = if (long) "The Long-Named Orchestra and Choir of the North Atlantic Islands feat. Everyone" else "Harbour Lights",
            album = "Tide Tables 1999–2012 (Deluxe Anniversary Box Set, Disc ${i % 9 + 1})",
            albumArtist = "Harbour Lights", year = 2011,
            coverUrl = "/api/tv/music/albums/${(i / 12).toString(16).padStart(32, '0')}/cover?v=1727890000",
            durationMs = 215_000L, hasLyrics = i % 2 == 0,
        )
    }

    private fun data(tracks: List<CastTrackItem>, cur: Int) = CastLoadData(
        serverUrl = "https://media.example.org", code = "ABC123", itemId = tracks[cur].id, title = tracks[cur].title, kicker = tracks[cur].artist,
        positionMs = 30_000L, deviceName = "Guest room", receiverId = "rcv-1", tracks = tracks, currentIndex = cur, shuffle = true,
    )

    private fun loadFrame(d: CastLoadData) = castFrameBytes("web-7", CastNamespaces.MEDIA, castLoadBody("sess-1", castLoadMedia(d, json), 30.0, true, null))
    private fun partFrame(c: CastCommand) = CastMessage(DEFAULT_SENDER, "web-7", CastNamespaces.RAVILO, json.encodeToString(CastCommand.serializer(), c)).frame().size

    @Test
    fun `every frame of a music cast fits, at any length`() {
        for (n in listOf(1, 30, 487, 5_000)) for (long in listOf(false, true)) for (cur in listOf(0, n / 3, n - 1)) {
            val plan = castLoadPlan(data(songs(n, long), cur), "q", json)
            val what = "$n songs${if (long) " (long titles)" else ""} at $cur"
            assertTrue(loadFrame(plan.load) <= CAST_MESSAGE_BUDGET_BYTES, "$what: LOAD frame ${loadFrame(plan.load)} B")
            plan.parts.forEach { assertTrue(partFrame(it) <= CAST_MESSAGE_BUDGET_BYTES, "$what: part frame ${partFrame(it)} B") }
        }
    }

    @Test
    fun `the 487-song LOAD, before and after`() {
        val full = data(songs(487, long = false), 200)
        // Before R359: the whole queue as the media's customData and again as the request's.
        val before = castFrameBytes("web-7", CastNamespaces.MEDIA,
            castLoadBody("sess-1", castLoadMedia(full, json), 30.0, true, json.encodeToJsonElement(CastLoadData.serializer(), full).jsonObject))
        val plan = castLoadPlan(full, "q", json)
        val after = loadFrame(plan.load)
        println("R359 (Mac): 487 songs — LOAD frame before ${before / 1024} KB; after ${castLoadLog(487, after, plan.parts.size)}, parts ${plan.parts.map { partFrame(it) / 1024 }} KB")
        assertTrue(before > 64 * 1024)
        assertTrue(after <= CAST_MESSAGE_BUDGET_BYTES)
    }

    @Test
    fun `CastLoadData rides once, as the media's`() {
        val plan = castLoadPlan(data(songs(26, long = false), 3), "q", json)
        val body = castLoadBody("sess-1", castLoadMedia(plan.load, json), 0.0, true, null)
        assertNull(body["customData"], "the request carries none (FR-R359-2)")
        val custom = json.decodeFromJsonElement(CastLoadData.serializer(), body["media"]!!.jsonObject["customData"]!!)
        // An album fits whole, as before R359: the receiver of any age plays it.
        assertEquals(26, custom.tracks.size)
        assertNull(custom.queueTotal)
        assertEquals("q", custom.queueId)
    }
}
