package dev.jellystructure.ravilo.ui.screens

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jellystructure.ravilo.ui.seams.PlayerQoeSnapshot
import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DEB-3630 (FR-R218-7) — a start that shows no picture is bounded. The decision table, and the store against a real HTTP server with a
 * player that never renders: a direct play asks again as a transcode, an encoder start asks for Jellyfin's stream, the last route ends
 * on R237's error, a first frame cancels the bound, and a seek after the first frame never fires it. The bounds are shortened (1.5 s and
 * 2.5 s for the spec's 10 s and 20 s); the failure watch polls every 500 ms, so a bound fires within one poll of its time.
 */
class PlayerStoreStartBoundTest {
    private val moveMs = 1_500L
    private val endMs = 2_500L

    @Test
    fun `the table - 10 s moves a direct play or an encoder start once, 20 s ends the last route, a first frame ends the watch`() {
        fun a(since: Long, frame: Boolean = false, moved: Boolean = false, other: Boolean = true) = startBoundAction(since, frame, moved, other)
        assertEquals(StartBoundAction.NONE, a(9_900))
        assertEquals(StartBoundAction.MOVE, a(10_000))
        assertEquals(StartBoundAction.NONE, a(9_900, frame = true), "a first frame at 9.9 s cancels the bound")
        assertEquals(StartBoundAction.NONE, a(60_000, frame = true), "a first frame seen cancels it for good")
        assertEquals(StartBoundAction.NONE, a(10_000, moved = true), "the second leg is not moved again")
        assertEquals(StartBoundAction.NONE, a(19_900, moved = true))
        assertEquals(StartBoundAction.END, a(20_000, moved = true), "20 s after the second stream was handed over")
        assertEquals(StartBoundAction.NONE, a(10_000, other = false), "Jellyfin's stream has no other route and is not moved")
        assertEquals(StartBoundAction.NONE, a(19_900, other = false))
        assertEquals(StartBoundAction.END, a(20_000, other = false))
    }

    private class Rig(val server: HttpServer, val client: TvApiClient) {
        val restreams = CopyOnWriteArrayList<String>()
        val qoe = CopyOnWriteArrayList<String>()
        val tickets = AtomicInteger(0)
        @Volatile var startTicket: StreamTicket = ours()
        @Volatile var restreamTicket: StreamTicket = ours()
    }

    private companion object {
        fun ours() = StreamTicket(
            jellyfinBaseUrl = "", accessToken = "", itemId = "item-1", container = "mkv", directPlay = false,
            hlsUrl = "/api/tv/stream/item-1/master.m3u8", expiresAt = 0L, encoder = "ours", adaptive = true, startVariantBps = 8_000_000L,
        )
        fun jellyfin() = ours().copy(encoder = null, hlsUrl = "/Videos/item-1/master.m3u8")
        fun direct() = ours().copy(directPlay = true, encoder = null, adaptive = false, startVariantBps = null)
        fun json(t: StreamTicket) = RaviloWireJsonWithDefaults.encodeToString(StreamTicket.serializer(), t)
    }

    private fun HttpExchange.reply(code: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun <T> withRig(block: (Rig) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        lateinit var rig: Rig
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val body = ex.requestBody.readBytes().decodeToString()
            when (path) {
                "/api/tv/playback/start" -> ex.reply(200, json(rig.startTicket))
                "/api/tv/playback/restream" -> {
                    rig.restreams += body
                    // Every ticket is its own, as the server's are: a StateFlow would drop an equal one.
                    val t = rig.restreamTicket
                    ex.reply(200, json(t.copy(hlsUrl = t.hlsUrl + "?s=${rig.tickets.incrementAndGet()}")))
                }
                "/api/tv/playback/qoe" -> { rig.qoe += body; ex.reply(200, "{}") }
                else -> ex.reply(200, "{}")
            }
        }
        server.start()
        try {
            rig = Rig(server, TvApiClient(HttpClient(OkHttp), base, { "token" }, platform = "tv"))
            return block(rig)
        } finally { server.stop(0) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000L, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    /** A player that never renders until the test says so. */
    private class Player {
        val firstFrame = AtomicBoolean(false)
        val waiting = AtomicBoolean(false)
        @Volatile var position = 0L
        fun watch() = PlayerWatch(position, wantsToPlay = true, waiting = waiting.get(), firstFrame = firstFrame.get(), bufferedAheadMs = 0L)
    }

    private fun start(rig: Rig, player: Player, states: MutableList<PlayerSessionState>, bounds: Boolean = true): PlayerStore {
        val store = PlayerStore(rig.client, unseenReplacements = true, boundsStart = bounds, startMoveMs = moveMs, startEndMs = endMs)
        Thread { runBlocking { store.state.collect { states += it } } }.apply { isDaemon = true; start() }
        store.startSession(
            "item-1", positionProvider = { player.position }, isPausedProvider = { false },
            qoeSnapshotProvider = { PlayerQoeSnapshot() }, failedProvider = { false }, watchProvider = { player.watch() },
        )
        waitFor("the first Ready") { states.any { it is PlayerSessionState.Ready } }
        return store
    }

    private fun readies(states: List<PlayerSessionState>) = states.filterIsInstance<PlayerSessionState.Ready>()

    @Test
    fun `a direct play with no first frame asks again as a transcode with direct play off, and its row says -1`() = withRig { rig ->
        rig.startTicket = direct(); rig.restreamTicket = ours()
        val player = Player(); val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states)
        assertTrue(readies(states).single().ticket.directPlay)
        Thread.sleep(moveMs - 600); assertTrue(rig.restreams.isEmpty(), "nothing before the bound")
        waitFor("the restream") { rig.restreams.isNotEmpty() }
        assertEquals(1, rig.restreams.size)
        assertTrue(rig.restreams[0].contains("\"hls_only\":true"), "direct play off for the session: ${rig.restreams[0]}")
        assertFalse(rig.restreams[0].contains("\"not_our_encoder\":true"), rig.restreams[0])
        waitFor("the second leg's Ready") { readies(states).size >= 2 }
        assertFalse(readies(states).last().ticket.directPlay)
        assertTrue(rig.qoe.any { it.contains("\"start_failure_status\":-1") }, "the first leg's row: ${rig.qoe}")
        store.close()
    }

    @Test
    fun `an encoder start with no first frame asks for Jellyfin's stream`() = withRig { rig ->
        rig.startTicket = ours(); rig.restreamTicket = jellyfin()
        val player = Player(); val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states)
        waitFor("the restream") { rig.restreams.isNotEmpty() }
        assertEquals(1, rig.restreams.size)
        assertTrue(rig.restreams[0].contains("\"not_our_encoder\":true"), rig.restreams[0])
        waitFor("the second leg's Ready") { readies(states).size >= 2 }
        assertTrue(rig.qoe.any { it.contains("\"start_failure_status\":-1") }, "the first leg's row: ${rig.qoe}")
        store.close()
    }

    @Test
    fun `a Jellyfin start, and a second leg, end on the error 20 s after the handover with no further restream`() = withRig { rig ->
        // Jellyfin's stream: no other route, no restream, the error at the last bound.
        rig.startTicket = jellyfin()
        val player = Player(); val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states)
        Thread.sleep(moveMs + 700)
        assertTrue(rig.restreams.isEmpty() && states.none { it is PlayerSessionState.Error }, "the move bound does not apply to Jellyfin's stream: $states")
        waitFor("the error") { states.any { it is PlayerSessionState.Error } }
        assertTrue(rig.restreams.isEmpty())
        assertEquals("The stream did not start", (states.last { it is PlayerSessionState.Error } as PlayerSessionState.Error).message)
        waitFor("the row that says so") { rig.qoe.any { it.contains("\"start_failure_status\":-1") } }   // the stop's post is fire-and-forget
        store.close()

        // A second leg: the direct play moved once, the transcode that follows ends on the error the end bound after ITS handover.
        rig.qoe.clear(); rig.restreams.clear()
        rig.startTicket = direct(); rig.restreamTicket = ours()
        val player2 = Player(); val states2 = CopyOnWriteArrayList<PlayerSessionState>()
        val store2 = start(rig, player2, states2)
        waitFor("the move") { rig.restreams.isNotEmpty() }
        val movedAt = System.currentTimeMillis()
        waitFor("the second leg's Ready") { readies(states2).size >= 2 }
        waitFor("the error") { states2.any { it is PlayerSessionState.Error } }
        val sinceSecond = System.currentTimeMillis() - movedAt
        assertTrue(sinceSecond >= endMs - 700, "the error came ${sinceSecond} ms after the second leg was asked for, not before its own bound")
        assertEquals(1, rig.restreams.size, "the start moves once")
        store2.close()
    }

    @Test
    fun `a first frame before the bound cancels it`() = withRig { rig ->
        rig.startTicket = direct(); rig.restreamTicket = ours()
        val player = Player(); val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states)
        Thread.sleep(moveMs - 700); player.firstFrame.set(true)
        Thread.sleep(endMs + 1_000)
        assertTrue(rig.restreams.isEmpty(), "no restream: ${rig.restreams}")
        assertTrue(states.none { it is PlayerSessionState.Error }, "no error: $states")
        store.close()
    }

    @Test
    fun `a seek after the first frame never fires the bound, even when the flag drops`() = withRig { rig ->
        rig.startTicket = direct(); rig.restreamTicket = ours()
        val player = Player(); val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states)
        player.firstFrame.set(true)
        Thread.sleep(1_000)   // the watch has seen it
        player.waiting.set(true); player.position = 600_000L; player.firstFrame.set(false)   // a seek, and a player that clears the flag
        Thread.sleep(endMs + 1_000)
        assertTrue(rig.restreams.isEmpty(), "no restream: ${rig.restreams}")
        assertTrue(states.none { it is PlayerSessionState.Error }, "no error: $states")
        store.close()
    }

    @Test
    fun `a platform that does not report a first frame is not bounded`() = withRig { rig ->
        rig.startTicket = direct()
        val player = Player(); val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states, bounds = false)
        Thread.sleep(endMs + 1_200)
        assertTrue(rig.restreams.isEmpty() && states.none { it is PlayerSessionState.Error }, "$states")
        store.close()
    }
}
