package dev.jellystructure.ravilo.castv2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R383 (FR-R383-4, FR-R383-7) — a fake 100-song queue through the cast engine, with memory flat.
 *
 * Route (DEB-1784, build review item 4): the test drives `ravilo-castv2`'s [CastSession] over a [CastTransport], as
 * [CastSessionTest] does, not `MusicCast` itself — `MusicCast` holds the concrete `CastController` and the sender opens
 * real sockets, so injecting a sender there is a refactor of its own. What this covers is the engine's per-song state:
 * request bookkeeping, the media status it keeps, the outbox. What it does not cover is `MusicCast`'s own state.
 *
 * Each song carries a [PAYLOAD_CHARS]-character `LOAD` (the real ones carry a track list and an artwork URL), is
 * played for half a minute of heartbeats, and ends. A session that kept anything per song would grow by more than
 * [BUDGET_BYTES] over the last 80 songs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastQueueMemoryTest {
    /** A device that answers what a receiver would and keeps nothing it hears — only counts, so the test's own heap is flat. */
    private class QueueDevice : CastTransport {
        private val toSender = Channel<ByteArray>(Channel.UNLIMITED)
        private val reader = CastFrameReader()
        var loads = 0
        var loadBytes = 0L
        override val incoming = toSender

        override suspend fun send(bytes: ByteArray) {
            for (m in reader.feed(bytes)) {
                val body = parsePayload(m.payloadUtf8) ?: continue
                when (CastParse.type(body)) {
                    "GET_STATUS" -> if (m.namespace == CastNamespaces.RECEIVER) say(CastNamespaces.RECEIVER, PLATFORM_RECEIVER,
                        """{"type":"RECEIVER_STATUS","requestId":${reqId(body)},"status":{"applications":[$APP_JSON],"volume":{"level":0.4,"muted":false}}}""")
                    "PING" -> say(CastNamespaces.HEARTBEAT, PLATFORM_RECEIVER, """{"type":"PONG"}""")
                    "LOAD" -> {
                        loads++
                        loadBytes += m.payloadUtf8?.length ?: 0
                        status(loads, "PLAYING", 0.0, requestId = reqId(body))
                    }
                }
            }
        }

        fun status(song: Int, state: String, at: Double, requestId: Int = 0, idle: String? = null) =
            say(CastNamespaces.MEDIA, "web-7",
                """{"type":"MEDIA_STATUS","requestId":$requestId,"status":[{"mediaSessionId":$song,"playerState":"$state","currentTime":$at,"playbackRate":1,""" +
                    (idle?.let { """"idleReason":"$it",""" } ?: "") +
                    """"media":{"duration":30,"metadata":{"title":"Song $song","artist":"Someone","images":[{"url":"http://x/$song.jpg"}]},"tracks":[{"trackId":1}]},"activeTrackIds":[1]}]}""")

        private fun say(ns: String, source: String, body: String) { toSender.trySend(CastMessage(source, DEFAULT_SENDER, ns, body).frame()) }
        override fun close() { toSender.close() }
    }

    private fun TestScope.joined(device: QueueDevice): CastSession {
        val s = CastSession(device, APP, CoroutineScope(coroutineContext)).also { it.start(); runCurrent() }
        val ok = async { s.joinIfRunning() }
        runCurrent()
        check(ok.getCompleted()) { "the fake device's app was not joined" }
        return s
    }

    @Test
    fun `a 100-song queue through a cast session leaves memory flat`() = runTest {
        val device = QueueDevice()
        val s = joined(device)
        var baseline = 0L
        for (song in 1..SONGS) {
            val sent = s.load(media(song), 0.0, autoplay = true, customData = obj("item_id" to "song-$song", "tracks" to bulk(song)))
            assertTrue(sent > PAYLOAD_CHARS, "song $song: the LOAD went out ($sent bytes)")
            runCurrent()
            assertEquals(song, assertNotNull(s.media.value).mediaSessionId, "song $song: the status is the new song's")
            // A question in the middle of a song, as the progress poll asks; its answer must not stay behind.
            val receiver = async { s.requestReceiverStatus() }
            runCurrent()
            assertNotNull(receiver.await(), "song $song: the device answered")
            advanceTimeBy(15_000); runCurrent()
            device.status(song, "PLAYING", 15.0)
            advanceTimeBy(15_000); runCurrent()
            device.status(song, "IDLE", 30.0, idle = "FINISHED")
            runCurrent()
            assertEquals(CastSession.State.JOINED, s.state.value, "song $song: the heartbeat kept the session up")
            if (song == WARM_UP) baseline = usedHeap()
        }
        assertEquals(SONGS, device.loads)
        assertTrue(device.loadBytes > SONGS.toLong() * PAYLOAD_CHARS, "every song's payload really went through")
        val grown = usedHeap() - baseline
        println("CastQueueMemoryTest: ${SONGS - WARM_UP} songs grew the heap by $grown bytes")
        assertTrue(grown < BUDGET_BYTES, "the last ${SONGS - WARM_UP} songs grew the heap by $grown bytes (budget $BUDGET_BYTES)")
        s.close(); advanceUntilIdle()
    }

    private fun media(song: Int) = obj("contentId" to "ravilo://song-$song", "contentType" to "audio/flac", "metadata" to obj("title" to "Song $song"))

    /** About [PAYLOAD_CHARS] characters, different for every song so nothing is shared between two of them. */
    private fun bulk(song: Int) = List(PAYLOAD_CHARS / 64) { "song-$song-track-${it.toString().padStart(4, '0')}".padEnd(64, '.') }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        var used = Long.MAX_VALUE
        repeat(4) { System.gc(); Thread.sleep(40); used = minOf(used, rt.totalMemory() - rt.freeMemory()) }
        return used
    }

    private companion object {
        const val APP = "ABCD1234"
        const val SONGS = 100
        const val WARM_UP = 20
        /** Under the 64 KiB a Cast frame may be ([CastFrameReader]); the real ones carry a track list and artwork. */
        const val PAYLOAD_CHARS = 40 * 1024
        /** The last 80 songs would leave over 3 MB if each kept its payload once; the JIT and the GC settle well inside this. */
        const val BUDGET_BYTES = 2L * 1024 * 1024
        const val APP_JSON = """{"appId":"$APP","displayName":"Ravilo","sessionId":"sess-1","transportId":"web-7","statusText":"","namespaces":[{"name":"urn:x-cast:dev.jellystructure.ravilo"}]}"""
        fun reqId(o: JsonObject) = o["requestId"]?.jsonPrimitive?.int ?: 0
    }
}
