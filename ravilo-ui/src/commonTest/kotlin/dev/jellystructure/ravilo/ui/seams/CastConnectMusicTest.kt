package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** R380 (FR-R380-1, dev review item 7) — a music LOAD to the Android TV app. */
class CastConnectMusicTest {
    private fun t(id: String) = CastTrackItem(id = id, title = id)
    private fun load(userId: String? = "u-anna", tracks: List<CastTrackItem> = listOf(t("a"), t("b"), t("c")), current: Int = 1) =
        CastLoadData(serverUrl = "https://media.example.test", code = "ABC123", itemId = "b", title = "B", positionMs = 12_000L,
            tracks = tracks, currentIndex = current, repeat = "all", shuffle = true, userId = userId)

    @Test fun `a queue LOAD is a music cast under the casting viewer`() {
        val m = assertNotNull(castConnectMusicOf(load()))
        assertEquals(listOf("a", "b", "c"), m.tracks.map { it.id })
        assertEquals(1, m.currentIndex)
        assertEquals(12_000L, m.positionMs)
        assertEquals("all", m.repeat)
        assertEquals("u-anna", m.userId)
        assertNull(m.queueTotal, "the whole queue came in the LOAD")
    }

    @Test fun `a window of a long queue says how long the queue is`() {
        val m = assertNotNull(castConnectMusicOf(load().copy(queueId = "q1", queueTotal = 300, queueStart = 120)))
        assertEquals(300, m.queueTotal)
        assertEquals(120, m.queueStart)
        assertEquals("q1", m.queueId)
    }

    @Test fun `no viewer, no songs or a broken index`() {
        assertNull(castConnectMusicOf(load(userId = null)), "a relay of someone else's session carries no viewer")
        assertNull(castConnectMusicOf(load(tracks = emptyList())), "a film is not a music cast")
        assertEquals(2, castConnectMusicOf(load(current = 9))?.currentIndex, "an index past the end is the last song")
    }

    @Test fun `the LOAD's json picks a film or a queue`() {
        val music = RaviloWireJsonWithDefaults.encodeToString(CastLoadData.serializer(), load())
        assertIs<CastConnectMusic>(castConnectLoadFromJson(music))
        val film = RaviloWireJsonWithDefaults.encodeToString(CastLoadData.serializer(), load(tracks = emptyList()))
        assertIs<CastConnectPlay>(castConnectLoadFromJson(film))
        assertNull(castConnectLoadFromJson("not json"))
        assertNull(castConnectLoadFromJson(null))
    }
}
