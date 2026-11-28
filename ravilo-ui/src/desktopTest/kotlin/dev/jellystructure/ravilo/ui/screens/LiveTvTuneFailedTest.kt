package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-5835 (FR-3) — a live channel that will not tune keeps its cause, and zap, number entry and Retry tune from the error. */
class LiveTvTuneFailedTest {

    private class FakeEngine(val tuneStatus: HttpStatusCode, val tuned: MutableList<String>) : HttpClientEngineBase("fake") {
        override val config = object : HttpClientEngineConfig() {}
        override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined

        @io.ktor.utils.io.InternalAPI
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val path = data.url.encodedPath
            val (status, body) = when {
                path.endsWith("/tune") -> {
                    tuned += path.substringBefore("/tune").substringAfterLast('/')
                    tuneStatus to "{}"
                }
                path.endsWith("/livetv/channels") -> HttpStatusCode.OK to channelsJson
                else -> HttpStatusCode.OK to "{}"
            }
            return HttpResponseData(
                status, GMTDate(), headersOf(HttpHeaders.ContentType, "application/json"),
                io.ktor.http.HttpProtocolVersion.HTTP_1_1, ByteReadChannel(body), callContext(),
            )
        }
    }

    private fun store(status: HttpStatusCode, tuned: MutableList<String>): LiveTvPlayerStore {
        val client = TvApiClient(HttpClient(FakeEngine(status, tuned)), "http://test.invalid", { "tok" })
        return LiveTvPlayerStore(client)
    }

    private suspend fun LiveTvPlayerStore.awaitError(): LiveTvPlayerState.Error {
        repeat(200) {
            (state.value as? LiveTvPlayerState.Error)?.let { return it }
            delay(10)
        }
        error("no Error state, got ${state.value}")
    }

    private suspend fun LiveTvPlayerStore.awaitTuned(tuned: List<String>, n: Int) {
        repeat(200) { if (tuned.size >= n) return; delay(10) }
        error("expected $n tunes, got $tuned")
    }

    @Test
    fun a_failed_tune_keeps_its_kind() = runBlocking {
        for ((status, kind) in listOf(
            HttpStatusCode.Unauthorized to LoadErrorKind.REAUTH,
            HttpStatusCode.BadGateway to LoadErrorKind.UNREACHABLE,
        )) {
            val tuned = mutableListOf<String>()
            val s = store(status, tuned)
            s.tune("c1")
            assertEquals(kind, s.awaitError().kind)
        }
    }

    @Test
    fun zap_and_number_entry_from_the_error_tune_the_neighbour_and_retry_tunes_again() = runBlocking {
        val tuned = mutableListOf<String>()
        val s = store(HttpStatusCode.BadGateway, tuned)
        s.tune("c2")
        s.awaitError()
        assertEquals(listOf("c2"), tuned)

        s.zap(1)
        s.awaitTuned(tuned, 2)
        assertEquals("c3", tuned[1])
        s.awaitError()

        s.zap(-1)
        s.awaitTuned(tuned, 3)
        assertEquals("c2", tuned[2])
        s.awaitError()

        assertTrue(s.tuneByNumber(1))
        s.awaitTuned(tuned, 4)
        assertEquals("c1", tuned[3])
        s.awaitError()

        s.tune(s.tuningChannelId!!) // Retry
        s.awaitTuned(tuned, 5)
        assertEquals("c1", tuned[4])
    }

    private companion object {
        fun ch(id: String, n: Int) =
            """{"channel_id":"$id","name":"Test $n","number":$n,"order":$n,"shown":true,"logo_url":null,"category":"x","has_guide":false}"""
        val channelsJson = "[${ch("c1", 1)},${ch("c2", 2)},${ch("c3", 3)}]"
    }
}
