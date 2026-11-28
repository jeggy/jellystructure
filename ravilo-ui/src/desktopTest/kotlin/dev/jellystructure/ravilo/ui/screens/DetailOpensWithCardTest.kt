package dev.jellystructure.ravilo.ui.screens

import com.sun.net.httpserver.HttpServer
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEB-5839 (FR-4) — a title page opened from a tile starts its first load holding that tile's card verbatim, and lets go of
 * it the moment the answer (here: a failure) lands. No card given, none held. The server fails every detail read, so the
 * store goes Loading -> Error without needing a detail payload.
 */
class DetailOpensWithCardTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
        it.createContext("/") { ex -> ex.sendResponseHeaders(500, -1); ex.close() }
        it.start()
    }
    private val api = TvApiClient(
        HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 2_000; socketTimeoutMillis = 2_000; connectTimeoutMillis = 2_000 } },
        "http://127.0.0.1:${server.address.port}", { "token" }, platform = "tv",
    )

    @AfterTest fun stop() = server.stop(0)

    private val card = MediaCard(id = "m1", title = "Invented Film", year = 2001, genre = null, rating = null, posterUrl = null, backdropUrl = "/img/b.jpg")

    @Test
    fun aFilmPageHoldsTheTilesCardUntilTheAnswerLands() = runBlocking {
        val store = MovieDetailStore(api)
        store.load("m1", card)
        assertEquals(card, store.openingCard.value)
        withTimeout(10_000) { store.state.first { it is MovieDetailState.Error } }
        assertNull(store.openingCard.value)
    }

    @Test
    fun aSeriesPageHoldsTheTilesCardUntilTheAnswerLands() = runBlocking {
        val store = SeriesDetailStore(api)
        store.load("m1", card)
        assertEquals(card, store.openingCard.value)
        withTimeout(10_000) { store.state.first { it is SeriesDetailState.Error } }
        assertNull(store.openingCard.value)
    }

    @Test
    fun noCardGivenNoCardHeld() {
        val store = MovieDetailStore(api)
        store.load("m1")
        assertNull(store.openingCard.value)
    }
}
