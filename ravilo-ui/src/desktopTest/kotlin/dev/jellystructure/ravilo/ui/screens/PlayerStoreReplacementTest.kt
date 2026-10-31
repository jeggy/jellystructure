package dev.jellystructure.ravilo.ui.screens

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DEB-3103 (DEB-3093 FR-6, FR-8) — the store's state machine against a real HTTP server: a replacement the viewer did not ask for never
 * emits `Loading`, a failed call is asked for again while the buffer holds, a failed encoder stream is asked for again before Jellyfin,
 * and the road writes its `recovery` entry.
 */
class PlayerStoreReplacementTest {
    private val ticket = StreamTicket(
        jellyfinBaseUrl = "", accessToken = "", itemId = "item-1", container = "mkv", directPlay = false,
        hlsUrl = "/api/tv/stream/item-1/master.m3u8", expiresAt = 0L, encoder = "ours", adaptive = true, startVariantBps = 8_000_000L,
    )
    private val ticketJson = RaviloWireJsonWithDefaults.encodeToString(StreamTicket.serializer(), ticket)

    private class Rig(val server: HttpServer, val client: TvApiClient) {
        val restreams = CopyOnWriteArrayList<String>()
        val qoe = CopyOnWriteArrayList<String>()
        val failNextRestreams = AtomicInteger(0)
        val tickets = AtomicInteger(0)
    }

    private fun HttpExchange.reply(code: Int, body: String, type: String = "application/json") {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", type)
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
            when {
                path == "/api/tv/playback/start" -> ex.reply(200, ticketJson)
                path == "/api/tv/playback/restream" -> {
                    rig.restreams += body
                    // Every ticket is its own (a session id in its URL), as the server's are: a StateFlow would drop an equal one.
                    if (rig.failNextRestreams.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) ex.reply(500, "{}")
                    else ex.reply(200, ticketJson.replace("master.m3u8", "master.m3u8?s=${rig.tickets.incrementAndGet()}"))
                }
                path == "/api/tv/playback/qoe" -> { rig.qoe += body; ex.reply(200, "{}") }
                path.endsWith("master.m3u8") -> ex.reply(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=8000000\nv0/index.m3u8\n", "application/vnd.apple.mpegurl")
                path.endsWith("index.m3u8") -> ex.reply(200, "#EXTM3U\n#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n", "application/vnd.apple.mpegurl")
                path.endsWith(".ts") -> ex.reply(200, "x", "video/mp2t")
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

    /** The store, started on the rig, with a player whose flags the test moves. */
    private class Player {
        val failed = AtomicBoolean(false)
        val position = AtomicLong(60_000L)
        val buffered = AtomicLong(20_000L)
        val waiting = AtomicBoolean(false)
        fun watch() = PlayerWatch(position.get(), wantsToPlay = true, waiting = waiting.get(), firstFrame = true, bufferedAheadMs = buffered.get())
    }

    private fun start(rig: Rig, player: Player, states: MutableList<PlayerSessionState>): PlayerStore {
        val store = PlayerStore(rig.client, replacementRetryMs = listOf(20L, 20L, 20L, 20L), unseenReplacements = true)
        val collector = Thread { runBlocking { store.state.collect { states += it } } }.apply { isDaemon = true; start() }
        store.startSession(
            "item-1", positionProvider = { player.position.get() }, isPausedProvider = { false },
            failedProvider = { player.failed.get() }, watchProvider = { player.watch() },
        )
        waitFor("the first Ready") { states.any { it is PlayerSessionState.Ready } }
        Thread.sleep(700)   // the failure watch has seen the flag clear (FailureLatch)
        return store
    }

    @Test
    fun `a failed encoder stream is replaced behind the picture - the state never leaves Ready and never emits Loading`() = withRig { rig ->
        val player = Player()
        val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states)
        val readyBefore = states.count { it is PlayerSessionState.Ready }
        player.failed.set(true)
        waitFor("the replacement's Ready") { states.count { it is PlayerSessionState.Ready } > readyBefore }
        val after = states.drop(states.indexOfFirst { it is PlayerSessionState.Ready } + 1)
        assertTrue(after.none { it is PlayerSessionState.Loading }, "states after the first Ready: $after")
        val last = states.last() as PlayerSessionState.Ready
        assertTrue(last.replacement, "the replacement says so, so the screen holds the last frame")
        store.close()
    }

    @Test
    fun `a failed call is asked for again while the buffer holds and the viewer sees nothing`() = withRig { rig ->
        val player = Player()
        val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states)
        rig.failNextRestreams.set(3)
        val readyBefore = states.count { it is PlayerSessionState.Ready }
        player.failed.set(true)
        waitFor("the replacement after three failed calls") { states.count { it is PlayerSessionState.Ready } > readyBefore }
        // The new engine is not failed: a flag left set would read as a second failure on a slow runner (DEB-3104, CI of 881aaa53).
        player.failed.set(false)
        assertEquals(4, rig.restreams.size, "three failures and the call that worked")
        // "Later" is after the first Ready. A Loading before it is the cold start, and where it sits in the list depends on whether the
        // collector thread saw Idle first (DEB-3200: `indexOf(it) > 0` read that race as a Loading the viewer saw).
        val afterFirstReady = states.drop(states.indexOfFirst { it is PlayerSessionState.Ready } + 1)
        assertTrue(states.none { it is PlayerSessionState.Error }, "an Error was seen: $states")
        assertTrue(afterFirstReady.none { it is PlayerSessionState.Loading }, "a Loading after the first Ready: $states")
        store.close()
    }

    @Test
    fun `with the buffer empty and every retry run it ends on the error screen`() = withRig { rig ->
        val player = Player().apply { buffered.set(0L) }
        val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states)
        rig.failNextRestreams.set(100)
        player.failed.set(true)
        waitFor("the error") { states.any { it is PlayerSessionState.Error } }
        assertEquals(5, rig.restreams.size, "the call and its four retries")
        store.close()
    }

    @Test
    fun `a failed encoder stream is asked for again from our encoder before Jellyfin is`() = withRig { rig ->
        val player = Player()
        val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states)
        // first failure → our encoder again
        var readyBefore = states.count { it is PlayerSessionState.Ready }
        player.failed.set(true)
        waitFor("the first replacement") { states.count { it is PlayerSessionState.Ready } > readyBefore }
        assertEquals(1, rig.restreams.size)
        assertTrue("not_our_encoder" !in rig.restreams[0], "the first try asks our encoder: ${rig.restreams[0]}")
        // second failure → Jellyfin
        player.failed.set(false)
        Thread.sleep(700)
        readyBefore = states.count { it is PlayerSessionState.Ready }
        player.failed.set(true)
        waitFor("the second replacement") { states.count { it is PlayerSessionState.Ready } > readyBefore }
        assertEquals(2, rig.restreams.size)
        assertTrue("\"not_our_encoder\":true" in rig.restreams[1], "the second try asks for Jellyfin's: ${rig.restreams[1]}")
        store.close()
    }

    @Test
    fun `the encoder road writes its recovery entry with a reason once the new stream has moved`() = withRig { rig ->
        val player = Player()
        val states = CopyOnWriteArrayList<PlayerSessionState>()
        val store = start(rig, player, states)
        val readyBefore = states.count { it is PlayerSessionState.Ready }
        player.failed.set(true)
        waitFor("the replacement") { states.count { it is PlayerSessionState.Ready } > readyBefore }
        player.failed.set(false)
        // the new stream stops the picture for over a second, then moves
        player.waiting.set(true)
        Thread.sleep(1_300)
        player.waiting.set(false)
        player.position.addAndGet(2_000L)
        Thread.sleep(1_200)
        store.stopSession(player.position.get())
        waitFor("a QoE post carrying the entry") { rig.qoe.any { "\"reason\":\"encoder_failed\"" in it } }
        val body = rig.qoe.first { "\"reason\":\"encoder_failed\"" in it }
        assertTrue("\"cause\":\"recovery\"" in body, body)
        store.close()
    }
}
