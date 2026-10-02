package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.CAST_MESSAGE_BUDGET_BYTES
import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import dev.jellystructure.shared.tv.castLoadPlan
import dev.jellystructure.shared.tv.castWireBytes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R359 (FR-R359-1/2) — the phone's LOAD, as the Cast SDK writes it (`MediaLoadRequestData.toJson`), at any queue length. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastLoadRequestTest {
    private val json = RaviloWireJsonWithDefaults

    private fun songs(n: Int, long: Boolean) = List(n) { i ->
        CastTrackItem(
            id = (i * 2654435761L).toString(16).padStart(32, '0').takeLast(32),
            title = if (long) "Ein sehr langer Liedtitel – Live at the Harbour (Remastered) · ".repeat(5) + i else "Song $i",
            artist = "Harbour Lights", album = "Tide Tables 1999–2012 (Deluxe Anniversary Box Set)", albumArtist = "Harbour Lights", year = 2011,
            coverUrl = "/api/tv/music/albums/${(i / 12).toString(16).padStart(32, '0')}/cover?v=1727890000", durationMs = 215_000L,
        )
    }

    private fun data(tracks: List<CastTrackItem>, cur: Int) = CastLoadData(
        serverUrl = "https://media.example.org", code = "ABC123", itemId = tracks[cur].id, title = tracks[cur].title,
        tracks = tracks, currentIndex = cur, positionMs = 30_000L,
    )

    @Test
    fun everyLoadAndPartFits() {
        for (n in listOf(1, 30, 487, 5_000)) for (long in listOf(false, true)) {
            val plan = castLoadPlan(data(songs(n, long), n / 2), "q", json)
            val load = castWireBytes(castLoadRequest(plan.load).toJson().toString())
            assertTrue(load <= CAST_MESSAGE_BUDGET_BYTES, "$n songs: LOAD $load B")
            if (n == 487 && !long) {
                // Before R359: the whole queue in the media's customData, and the same again as the request's.
                val whole = data(songs(n, long), n / 2)
                val before = castWireBytes(castLoadRequest(whole).toJson().toString()) + castWireBytes(json.encodeToString(CastLoadData.serializer(), whole))
                println("R359 (Android): 487 songs — LOAD before ${before / 1024} KB; after ${load / 1024} KB + ${plan.parts.size} parts")
            }
            plan.parts.forEach { p ->
                val bytes = castWireBytes(json.encodeToString(CastCommand.serializer(), p))
                assertTrue(bytes <= CAST_MESSAGE_BUDGET_BYTES, "$n songs: part $bytes B")
            }
        }
    }

    @Test
    fun theLoadCarriesCastLoadDataOnceAsTheMedias() {
        val req = castLoadRequest(castLoadPlan(data(songs(26, long = false), 3), "q", json).load).toJson()
        assertFalse(req.has("customData") && !req.isNull("customData"), "the request carries none (FR-R359-2)")
        val custom = req.getJSONObject("media").getJSONObject("customData")
        assertEquals(26, custom.getJSONArray("tracks").length())
        assertEquals("q", custom.getString("queue_id"))
    }
}
