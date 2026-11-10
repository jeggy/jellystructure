package dev.jellystructure.ravilo.ui.screens

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jellystructure.ravilo.ui.downloads.LocalPlay
import dev.jellystructure.ravilo.ui.downloads.SavedDownload
import dev.jellystructure.ravilo.ui.downloads.buildLocalTicket
import dev.jellystructure.ravilo.ui.seams.PlayerQoeSnapshot
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DEB-4072 (DEB-3995 FR-15, FR-16) — a play from a download never reaches the server: no start, heartbeat, stop, QoE, restream or mark.
 * The store runs against a real HTTP server that counts every request; what the play did is in the outbox calls the fake [LocalPlay] saw.
 */
class PlayerStoreLocalPlayTest {
    private class Local : LocalPlay {
        override val itemId = "item-1"
        val progress = CopyOnWriteArrayList<Long>()
        val watched = CopyOnWriteArrayList<String>()
        @Volatile var startedAt: Long? = -1L
        override suspend fun ticket(startPositionMs: Long?): StreamTicket {
            startedAt = startPositionMs
            return buildLocalTicket(itemId, SavedDownload("file", "https://srv/Videos/item-1/stream?x=1", emptyList()), startPositionMs ?: 7_000L)
        }
        override fun recordProgress(positionMs: Long) { progress += positionMs }
        override fun recordWatched() { watched += itemId }
    }

    private fun <T> withServer(block: (TvApiClient, CopyOnWriteArrayList<String>) -> T): T {
        val hits = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            hits += ex.requestURI.path
            ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.start()
        try { return block(TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:${server.address.port}", { "token" }, platform = "phone"), hits) }
        finally { server.stop(0) }
    }

    private fun wait(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!cond()) { if (System.currentTimeMillis() > until) fail("timed out waiting for $what"); Thread.sleep(20) }
    }

    private fun begin(store: PlayerStore, pos: () -> Long) {
        store.startSession("item-1", positionProvider = pos, isPausedProvider = { false }, durationProvider = { 100_000L },
            qoeSnapshotProvider = { PlayerQoeSnapshot() }, failedProvider = { false })
        wait("Ready") { store.state.value is PlayerSessionState.Ready }
    }

    @Test fun `the play is ready from the local ticket and the server is never asked`() = withServer { client, hits ->
        val local = Local()
        val store = PlayerStore(client, local = local)
        begin(store) { 5_000L }
        val ready = store.state.value as PlayerSessionState.Ready
        assertEquals("https://srv/Videos/item-1/stream?x=1", ready.ticket.hlsUrl)
        assertEquals(7_000L, ready.ticket.startPositionMs)
        Thread.sleep(300)
        store.restreamVersion("item-1", "v2", 5_000L, -1, null)   // a server-only action is absent, not broken
        store.prepareNext("item-2"); store.markWatched("item-1")
        Thread.sleep(300)
        assertTrue(hits.isEmpty(), "no request at all: $hits")
        assertEquals(listOf("item-1"), local.watched, "markWatched is a queued write")
        store.close()
    }

    @Test fun `stopping records where it reached and a finish past 90 percent, with no server stop or mark`() = withServer { client, hits ->
        val local = Local()
        val store = PlayerStore(client, local = local)
        begin(store) { 95_000L }
        store.stopSession(95_000L, 100_000L)
        assertEquals(listOf(95_000L), local.progress)
        assertEquals(listOf("item-1"), local.watched)
        assertEquals(PlayerSessionState.Idle, store.state.value)
        Thread.sleep(500)
        assertTrue(hits.isEmpty(), "no stop, no mark, no QoE: $hits")
    }

    @Test fun `a stop short of the finish records progress only`() = withServer { client, hits ->
        val local = Local()
        val store = PlayerStore(client, local = local)
        begin(store) { 20_000L }
        store.stopSession(20_000L, 100_000L)
        assertEquals(listOf(20_000L), local.progress)
        assertTrue(local.watched.isEmpty())
        Thread.sleep(300)
        assertTrue(hits.isEmpty(), "$hits")
    }

    @Test fun `a fatal player error ends on the error with Retry and does not restream`() = withServer { client, hits ->
        val local = Local(); var failed = false
        val store = PlayerStore(client, local = local)
        store.startSession("item-1", positionProvider = { 3_000L }, isPausedProvider = { false }, qoeSnapshotProvider = { PlayerQoeSnapshot() }, failedProvider = { failed })
        wait("Ready") { store.state.value is PlayerSessionState.Ready }
        Thread.sleep(700); failed = true
        wait("the error") { store.state.value is PlayerSessionState.Error }
        assertTrue(hits.isEmpty(), "$hits")
        store.close()
    }
}
