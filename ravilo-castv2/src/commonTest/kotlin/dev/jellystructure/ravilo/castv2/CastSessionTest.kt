package dev.jellystructure.ravilo.castv2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val APP = "ABCD1234"

/** A device on the other end of the wire: records what the sender says, answers what [answer] says to. */
private class FakeDevice(var answer: (CastMessage, JsonObject?) -> List<CastMessage> = { _, _ -> emptyList() }) : CastTransport {
    private val toSender = Channel<ByteArray>(Channel.UNLIMITED)
    private val reader = CastFrameReader()
    val heard = mutableListOf<CastMessage>()
    var closed = false
    override val incoming = toSender

    override suspend fun send(bytes: ByteArray) {
        for (m in reader.feed(bytes)) {
            heard += m
            answer(m, parsePayload(m.payloadUtf8)).forEach { say(it) }
        }
    }

    fun say(m: CastMessage) { toSender.trySend(m.frame()) }
    override fun close() { closed = true; toSender.close() }

    fun types(ns: String? = null) = heard.filter { ns == null || it.namespace == ns }.map { parsePayload(it.payloadUtf8)?.let(CastParse::type) }
}

private fun fromDevice(ns: String, body: String, source: String = PLATFORM_RECEIVER) = CastMessage(source, DEFAULT_SENDER, ns, body)
private fun reqId(o: JsonObject?) = o?.get("requestId")?.jsonPrimitive?.int ?: 0

private fun receiverStatus(id: Int, vararg apps: String) = fromDevice(CastNamespaces.RECEIVER,
    """{"type":"RECEIVER_STATUS","requestId":$id,"status":{"applications":[${apps.joinToString(",")}],"volume":{"level":0.4,"muted":false}}}""")

private fun app(appId: String, session: String = "sess-1", transport: String = "web-7", name: String = "Ravilo") =
    """{"appId":"$appId","displayName":"$name","sessionId":"$session","transportId":"$transport","statusText":"","namespaces":[{"name":"${CastNamespaces.RAVILO}"}]}"""

@OptIn(ExperimentalCoroutinesApi::class)
class CastSessionTest {
    private fun TestScope.session(device: FakeDevice): CastSession =
        CastSession(device, APP, CoroutineScope(coroutineContext)).also { it.start(); runCurrent() }

    @Test
    fun `starting connects to the platform receiver`() = runTest {
        val device = FakeDevice()
        val s = session(device)
        assertEquals(CastSession.State.CONNECTED, s.state.value)
        assertEquals(listOf<String?>("CONNECT"), device.types())
        assertEquals(PLATFORM_RECEIVER, device.heard.first().destinationId)
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `an app that is not running is launched then joined`() = runTest {
        val device = FakeDevice { m, body ->
            when (body?.let(CastParse::type)) {
                "GET_STATUS" -> if (m.namespace == CastNamespaces.RECEIVER) listOf(receiverStatus(reqId(body), app("CC1AD845", "other", "web-1", "Spotify"))) else emptyList()
                "LAUNCH" -> listOf(receiverStatus(reqId(body), app(APP)))
                else -> emptyList()
            }
        }
        val s = session(device)
        val joined = async { s.launchOrJoin() }
        runCurrent()
        assertTrue(joined.await())
        assertEquals(CastSession.State.JOINED, s.state.value)
        assertEquals("web-7", s.app?.transportId)
        val launch = device.heard.first { parsePayload(it.payloadUtf8)?.let(CastParse::type) == "LAUNCH" }
        assertEquals(APP, parsePayload(launch.payloadUtf8)!!["appId"]!!.jsonPrimitive.content)
        // then CONNECT to the app's transport and ask its media channel where it is
        assertTrue(device.heard.any { it.destinationId == "web-7" && it.namespace == CastNamespaces.CONNECTION })
        assertTrue(device.heard.any { it.destinationId == "web-7" && it.namespace == CastNamespaces.MEDIA })
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `a TV that wakes for the launch is asked again until the app is up`() = runTest {
        // A BRAVIA asleep (2026-09-30): LAUNCH is answered with a LAUNCH_STATUS and no app; the app shows up in the
        // receiver's status a few seconds later.
        var asked = 0
        val device = FakeDevice { m, body ->
            when (body?.let(CastParse::type)) {
                "GET_STATUS" -> if (m.namespace == CastNamespaces.RECEIVER) listOf(if (asked++ < 2) receiverStatus(reqId(body)) else receiverStatus(reqId(body), app(APP))) else emptyList()
                "LAUNCH" -> listOf(fromDevice(CastNamespaces.RECEIVER, """{"type":"LAUNCH_STATUS","launchRequestId":${reqId(body)},"requestId":${reqId(body)},"status":"USER_ALLOWED"}"""))
                else -> emptyList()
            }
        }
        val s = session(device)
        val joined = async { s.launchOrJoin() }
        advanceTimeBy(4_500); runCurrent()   // two polls, 2 s apart
        assertTrue(joined.await())
        assertEquals(CastSession.State.JOINED, s.state.value)
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `a launch the device refuses is not waited for`() = runTest {
        val device = FakeDevice { m, body ->
            when (body?.let(CastParse::type)) {
                "GET_STATUS" -> if (m.namespace == CastNamespaces.RECEIVER) listOf(receiverStatus(reqId(body))) else emptyList()
                "LAUNCH" -> listOf(fromDevice(CastNamespaces.RECEIVER, """{"type":"LAUNCH_ERROR","requestId":${reqId(body)},"reason":"NOT_FOUND"}"""))
                else -> emptyList()
            }
        }
        val s = session(device)
        val joined = async { s.launchOrJoin() }
        runCurrent()
        assertFalse(joined.await())
        assertEquals(1, device.types(CastNamespaces.RECEIVER).count { it == "GET_STATUS" })
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `an app that already runs is joined without a launch`() = runTest {
        val device = FakeDevice { m, body ->
            if (m.namespace == CastNamespaces.RECEIVER && body?.let(CastParse::type) == "GET_STATUS") listOf(receiverStatus(reqId(body), app(APP))) else emptyList()
        }
        val s = session(device)
        val joined = async { s.joinIfRunning() }
        runCurrent()
        assertTrue(joined.await())
        assertFalse("LAUNCH" in device.types(CastNamespaces.RECEIVER))
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `a load says the size of the frame it sent`() = runTest {
        // R359 (FR-R359-7) — the sender logs the LOAD's size; it is the frame the device reads, request id and all.
        val device = FakeDevice { m, body ->
            if (m.namespace == CastNamespaces.RECEIVER && body?.let(CastParse::type) == "GET_STATUS") listOf(receiverStatus(reqId(body), app(APP))) else emptyList()
        }
        val s = session(device)
        val joined = async { s.joinIfRunning() }
        runCurrent()
        assertTrue(joined.await())
        val media = obj("contentId" to "ravilo://song", "metadata" to obj("title" to "A song"))
        val said = s.load(media, 30.0, autoplay = true, customData = obj("item_id" to "song", "tracks" to List(50) { "t$it" }))
        runCurrent()
        val load = device.heard.single { parsePayload(it.payloadUtf8)?.let(CastParse::type) == "LOAD" }
        // The same size give or take the request id's digits (the estimate counts the largest).
        assertTrue(said >= load.frame().size && said - load.frame().size < 12, "said $said, sent ${load.frame().size}")
        assertEquals("sess-1", parsePayload(load.payloadUtf8)!!["sessionId"]!!.jsonPrimitive.content)
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `joinIfRunning never launches`() = runTest {
        val device = FakeDevice { m, body ->
            if (m.namespace == CastNamespaces.RECEIVER && body?.let(CastParse::type) == "GET_STATUS") listOf(receiverStatus(reqId(body))) else emptyList()
        }
        val s = session(device)
        val joined = async { s.joinIfRunning() }
        runCurrent()
        assertFalse(joined.await())
        assertFalse("LAUNCH" in device.types(CastNamespaces.RECEIVER))
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `app availability is read for our app id`() = runTest {
        val device = FakeDevice { _, body ->
            if (body?.let(CastParse::type) == "GET_APP_AVAILABILITY") listOf(fromDevice(CastNamespaces.RECEIVER,
                """{"responseType":"GET_APP_AVAILABILITY","requestId":${reqId(body)},"availability":{"$APP":"APP_AVAILABLE"}}""")) else emptyList()
        }
        val s = session(device)
        val available = async { s.appAvailable() }
        runCurrent()
        assertTrue(available.await())
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `an unanswered question gives up after the timeout`() = runTest {
        val s = session(FakeDevice())
        val available = async { s.appAvailable() }
        advanceTimeBy(10_001)
        runCurrent()
        assertFalse(available.await())
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `three unanswered pings close the session and an answer resets the count`() = runTest {
        val silent = FakeDevice()
        val s = session(silent)
        advanceTimeBy(15_001); runCurrent()
        assertEquals(3, silent.types(CastNamespaces.HEARTBEAT).count { it == "PING" })
        assertEquals(CastSession.State.CONNECTED, s.state.value, "three pings out, none answered yet — still open")
        advanceTimeBy(5_000); runCurrent()
        assertEquals(CastSession.State.CLOSED, s.state.value)
        advanceUntilIdle()
        assertTrue(silent.closed)

        val alive = FakeDevice { m, body ->
            if (m.namespace == CastNamespaces.HEARTBEAT && body?.let(CastParse::type) == "PING") listOf(fromDevice(CastNamespaces.HEARTBEAT, """{"type":"PONG"}""")) else emptyList()
        }
        val t = session(alive)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(CastSession.State.CONNECTED, t.state.value)
        t.close(); advanceUntilIdle()
    }

    @Test
    fun `the device's ping is answered`() = runTest {
        val device = FakeDevice()
        val s = session(device)
        device.say(fromDevice(CastNamespaces.HEARTBEAT, """{"type":"PING"}"""))
        runCurrent()
        assertTrue("PONG" in device.types(CastNamespaces.HEARTBEAT))
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `media commands carry the last media session and our channel goes both ways`() = runTest {
        val device = FakeDevice { m, body ->
            if (m.namespace == CastNamespaces.RECEIVER && body?.let(CastParse::type) == "GET_STATUS") listOf(receiverStatus(reqId(body), app(APP))) else emptyList()
        }
        val s = session(device)
        val joined = async { s.joinIfRunning() }
        runCurrent(); joined.await()
        device.say(fromDevice(CastNamespaces.MEDIA,
            """{"type":"MEDIA_STATUS","requestId":0,"status":[{"mediaSessionId":42,"playerState":"PLAYING","currentTime":61.5,"playbackRate":1,""" +
                """"media":{"duration":200,"metadata":{"title":"A song","artist":"Someone","images":[{"url":"http://x/c.jpg"}]},"tracks":[{"trackId":1}]},"activeTrackIds":[1]}]}""",
            source = "web-7"))
        runCurrent()
        val media = assertNotNull(s.media.value)
        assertEquals(42, media.mediaSessionId); assertEquals("A song", media.title); assertEquals("Someone", media.subtitle)
        assertEquals(listOf(1L), media.trackIds); assertEquals(61_500, media.positionMs().coerceAtMost(61_500))
        s.pause(); s.seek(90_000); runCurrent()
        val sent = device.heard.filter { it.namespace == CastNamespaces.MEDIA }.mapNotNull { parsePayload(it.payloadUtf8) }
        val pause = sent.first { CastParse.type(it) == "PAUSE" }
        assertEquals(42, pause["mediaSessionId"]!!.jsonPrimitive.int)
        assertEquals("90.0", sent.first { CastParse.type(it) == "SEEK" }["currentTime"]!!.jsonPrimitive.content)

        val heard = async { s.custom.first() }
        runCurrent()
        device.say(CastMessage("web-7", "*", CastNamespaces.RAVILO, """{"type":"status"}"""))
        runCurrent()
        assertEquals("""{"type":"status"}""", heard.await())
        s.sendCustom("""{"type":"next"}"""); runCurrent()
        assertTrue(device.heard.any { it.namespace == CastNamespaces.RAVILO && it.destinationId == "web-7" && it.payloadUtf8 == """{"type":"next"}""" })
        s.close(); advanceUntilIdle()
    }

    @Test
    fun `another sender's app replacing ours ends the session`() = runTest {
        val device = FakeDevice { m, body ->
            if (m.namespace == CastNamespaces.RECEIVER && body?.let(CastParse::type) == "GET_STATUS") listOf(receiverStatus(reqId(body), app(APP))) else emptyList()
        }
        val s = session(device)
        val joined = async { s.joinIfRunning() }
        runCurrent(); joined.await()
        device.say(receiverStatus(0, app("CC1AD845", "sess-9", "web-9", "Spotify")))
        runCurrent()
        assertEquals(CastSession.State.CLOSED, s.state.value)
        assertEquals("replaced by another app", s.closedReason)
        advanceUntilIdle()
    }

    @Test
    fun `stopping the app sends STOP for its session and closes`() = runTest {
        val device = FakeDevice { m, body ->
            if (m.namespace == CastNamespaces.RECEIVER && body?.let(CastParse::type) == "GET_STATUS") listOf(receiverStatus(reqId(body), app(APP))) else emptyList()
        }
        val s = session(device)
        launch { s.joinIfRunning() }
        runCurrent()
        s.stopApp()
        advanceTimeBy(301); runCurrent()
        val stop = device.heard.mapNotNull { parsePayload(it.payloadUtf8) }.first { CastParse.type(it) == "STOP" }
        assertEquals("sess-1", stop["sessionId"]!!.jsonPrimitive.content)
        assertEquals(CastSession.State.CLOSED, s.state.value)
        advanceUntilIdle()
    }
}
