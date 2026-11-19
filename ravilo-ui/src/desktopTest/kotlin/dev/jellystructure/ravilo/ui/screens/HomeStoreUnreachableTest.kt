package dev.jellystructure.ravilo.ui.screens

import com.sun.net.httpserver.HttpServer
import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-5062 (DEB-4912, FR-12) — a Home that has never loaded does not sit on the Loading shimmer while the server does not answer.
 * Against a real HTTP server that holds every request without a reply (the blackhole QA made on the Streamer), the store ends on
 * the can't-reach state within its bounded attempts, and — the server answering again — goes on to Loaded without a Retry press.
 * The clients' 10 s request bound is 0.4 s here and the backoff starts at 50 ms, so the sequence runs in seconds.
 */
class HomeStoreUnreachableTest {
    private val answering = AtomicBoolean(false)
    private val homeCalls = AtomicInteger(0)
    private lateinit var server: HttpServer
    private lateinit var api: TvApiClient
    private lateinit var scope: CoroutineScope

    private fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            if (ex.requestURI.path == "/api/tv/home") homeCalls.incrementAndGet()
            if (answering.get() && ex.requestURI.path == "/api/tv/home") {
                val body = RaviloWireJson.encodeToString(HomeFeed.serializer(), HomeFeed(heroes = emptyList(), channels = emptyList(), rows = emptyList(), autoAdvanceSeconds = 0)).toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            } else if (answering.get()) {
                ex.sendResponseHeaders(404, -1); ex.close()
            } else {
                Thread.sleep(5_000)   // never answered within the client's bound
                runCatching { ex.close() }
            }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 400; socketTimeoutMillis = 400; connectTimeoutMillis = 400 }
        }
        api = TvApiClient(client, "http://127.0.0.1:${server.address.port}", { "token" }, platform = "tv")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private fun stop() { scope.cancel(); server.stop(0) }

    private val policy = HomeLoadPolicy(attemptsBeforeError = 2, quietRetryMs = 200, firstDelayMs = 50)

    @Test
    fun `a first load against a server that never answers ends on the cant-reach state, then loads by itself when it answers`() = runBlocking {
        start()
        try {
            val store = HomeStore(api, policy = policy, scope = scope)
            assertEquals(HomeState.Loading, store.state.value)
            // 2 attempts × 0.4 s + 50 ms: the error is there well inside 5 s, not after ten backed-off attempts.
            val err = withTimeout(5_000) { while (store.state.value !is HomeState.Error) delay(25); store.state.value as HomeState.Error }
            assertEquals(LoadErrorKind.UNREACHABLE, err.kind)
            val callsAtError = homeCalls.get()
            // It keeps asking behind the error screen …
            withTimeout(5_000) { while (homeCalls.get() <= callsAtError) delay(25) }
            assertTrue(store.state.value is HomeState.Error)
            // … and clears itself once the network is back, with no Retry press.
            answering.set(true)
            withTimeout(5_000) { while (store.state.value !is HomeState.Loaded) delay(25) }
        } finally { stop() }
    }

    @Test
    fun `a Retry press while unreachable starts the bounded sequence again and ends on the error again`() = runBlocking {
        start()
        try {
            val store = HomeStore(api, policy = policy, scope = scope)
            withTimeout(5_000) { while (store.state.value !is HomeState.Error) delay(25) }
            store.refresh(silent = false)
            assertEquals(HomeState.Loading, store.state.value)
            withTimeout(5_000) { while (store.state.value !is HomeState.Error) delay(25) }
        } finally { stop() }
    }
}
