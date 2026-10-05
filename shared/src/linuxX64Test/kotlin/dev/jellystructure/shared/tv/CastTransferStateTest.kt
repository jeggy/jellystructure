package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R371 (found on the Pixel 9 Pro, 10:38:33) — a speaker group that shrinks moves the session to another endpoint; the
 * receiver hands its queue and place on, and the resumed LOAD carries them. Before, it carried no songs and the receiver
 * closed on both speakers.
 */
class CastTransferStateTest {
    private val songs = (1..11).map { CastTrackItem(id = "s$it", title = "Song $it") }
    private val playing = CastLoadData(serverUrl = "https://media.example.test", code = "", itemId = "s4", title = "Song 4",
        tracks = songs, currentIndex = 3, positionMs = 1_000, queueId = "q1", startOver = true, sessionId = "ps-1")

    @Test fun `the state handed on is the queue the song and the place with no code`() {
        val s = castTransferState(playing.copy(code = "ABC123"), positionMs = 125_400)
        assertEquals(11, s.tracks.size)
        assertEquals(3, s.currentIndex)
        assertEquals(125_400, s.positionMs)
        assertEquals("", s.code)
        assertEquals(false, s.startOver)
        assertEquals("ps-1", s.sessionId)
    }

    @Test fun `the resumed load starts where Cast says the media had reached`() {
        val handed = castTransferState(playing, 125_400)
        assertEquals(126_900, castResumedLoad(handed, 126.9)?.positionMs)
        assertEquals(125_400, castResumedLoad(handed, null)?.positionMs, "no time from Cast: the handed place")
        assertEquals(125_400, castResumedLoad(handed, 0.0)?.positionMs)
        assertNull(castResumedLoad(null, 126.9), "a state from an older receiver: the LOAD stands as Cast built it")
    }

    @Test fun `the state survives the trip as JSON`() {
        val json = RaviloWireJson.encodeToString(CastLoadData.serializer(), castTransferState(playing, 125_400))
        assertEquals(11, RaviloWireJson.decodeFromString(CastLoadData.serializer(), json).tracks.size)
    }
}
