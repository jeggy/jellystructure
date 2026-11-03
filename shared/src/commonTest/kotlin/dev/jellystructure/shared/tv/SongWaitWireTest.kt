package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-3034 (a-song-that-waits-says-so.md FR-3, FR-7) — a song's wait and a pause's source, on the wire. */
class SongWaitWireTest {
    private fun encode(r: PlaybackProgressRequest) = RaviloWireJson.encodeToString(PlaybackProgressRequest.serializer(), r)

    @Test
    fun withoutAWaitOrASourceTheBodyIsExactlyWhatAnOlderAppSends() {
        assertEquals("""{"item_id":"s1","position_ms":42000}""", encode(progressRequest("s1", 42_000L, false, null)))
    }

    @Test
    fun aWaitRidesAsWaitingAndWaitDetail() {
        val r = progressRequest("s1", 42_000L, false, null, waiting = true, waitDetail = "phase=mid pos=42000 buffered=0 observed=96000")
        assertEquals(
            """{"item_id":"s1","position_ms":42000,"waiting":true,"wait_detail":"phase=mid pos=42000 buffered=0 observed=96000"}""",
            encode(r))
        assertEquals("""{"item_id":"s1","position_ms":42000,"waiting":false}""", encode(progressRequest("s1", 42_000L, false, null, waiting = false)))
    }

    @Test
    fun aPauseRidesWithWhatSentIt() {
        assertEquals("""{"item_id":"s1","position_ms":42000,"is_paused":true,"pause_by":"now_playing_toggle"}""",
            encode(progressRequest("s1", 42_000L, true, null, pauseBy = "now_playing_toggle")))
    }

    @Test
    fun anOlderAppsBodyStillDecodes() {
        val r = RaviloWireJson.decodeFromString(PlaybackProgressRequest.serializer(), """{"item_id":"s1","position_ms":1}""")
        assertNull(r.waiting)
        assertNull(r.waitDetail)
        assertNull(r.pauseBy)
    }

    @Test
    fun anOlderServerIgnoresTheNewKeys() {
        // The shape of the request as the previous release decodes it: the same keys it knew, and the three new ones unknown.
        val r = RaviloWireJson.decodeFromString(PlaybackProgressRequest.serializer(),
            """{"item_id":"s1","position_ms":1,"is_paused":true,"waiting":true,"wait_detail":"phase=start pos=0 buffered=0","pause_by":"keyboard","future_key":1}""")
        assertEquals(true, r.waiting)
        assertEquals("keyboard", r.pauseBy)
    }
}
