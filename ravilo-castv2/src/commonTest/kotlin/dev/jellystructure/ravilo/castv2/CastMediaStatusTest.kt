package dev.jellystructure.ravilo.castv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastMediaStatusTest {
    private fun status(json: String) = CastParse.mediaStatus(parsePayload(json)!!)

    @Test
    fun `a report without media keeps the media of the same session`() {
        val first = status("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":3,"playerState":"PLAYING","currentTime":1,""" +
            """"media":{"duration":300,"metadata":{"title":"T","subtitle":"S01E01"},"tracks":[{"trackId":5}]}}]}""")!!
        val next = status("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":3,"playerState":"PAUSED","currentTime":42}]}""")!!
            .withMediaFrom(first)
        assertEquals("PAUSED", next.playerState)
        assertEquals(42_000, next.positionMs())
        assertEquals(300.0, next.durationSec); assertEquals("T", next.title); assertEquals(listOf(5L), next.trackIds)
        val other = status("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":4,"playerState":"BUFFERING","currentTime":0}]}""")!!
            .withMediaFrom(first)
        assertNull(other.title, "a new media session starts from nothing")
    }

    @Test
    fun `an empty status list means nothing is loaded and a paused position does not move`() {
        assertNull(status("""{"type":"MEDIA_STATUS","status":[]}"""))
        val paused = status("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":1,"playerState":"PAUSED","currentTime":10.25}]}""")!!
        assertEquals(10_250, paused.positionMs())
    }

    @Test
    fun `receiver status lists the apps and the volume`() {
        val r = CastParse.receiverStatus(parsePayload("""{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"A","displayName":"Spotify",""" +
            """"sessionId":"s","transportId":"t","statusText":"Playing","namespaces":[{"name":"urn:x"}]}],"volume":{"level":0.25,"muted":true}}}""")!!)!!
        assertEquals("Spotify", r.apps.single().displayName); assertEquals(listOf("urn:x"), r.apps.single().namespaces)
        assertEquals(0.25, r.volumeLevel); assertEquals(true, r.muted)
    }
}
