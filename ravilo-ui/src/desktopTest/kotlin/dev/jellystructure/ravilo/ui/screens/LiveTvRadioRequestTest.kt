package dev.jellystructure.ravilo.ui.screens

import com.sun.net.httpserver.HttpServer
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-2658 (DEB-2628 FR-2) — every client with a radio player asks for stations: `radio=1` goes out on the channels and the guide requests. */
class LiveTvRadioRequestTest {
    private fun <T> withServer(block: (client: TvApiClient, seen: List<String>) -> T): T {
        val seen = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen += ex.requestURI.toString()
            val body = "[]".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:${server.address.port}", { "token" }, platform = "tv")
            return block(client, seen)
        } finally { server.stop(0) }
    }

    @Test
    fun `the channels request carries radio=1`() = withServer { client, seen ->
        runBlocking { client.getLiveTvChannels() }
        assertEquals(1, seen.size)
        assertTrue(seen[0].startsWith("/api/tv/livetv/channels?"), seen[0])
        assertTrue("radio=1" in seen[0].substringAfter('?').split('&'), seen[0])
        assertTrue(seen[0].substringAfter('?').split('&').none { it.startsWith("kind=") }, seen[0])
    }

    @Test
    fun `the channels request can ask for stations alone`() = withServer { client, seen ->
        runBlocking { client.getLiveTvChannels(kind = "radio") }
        val query = seen.single().substringAfter('?').split('&')
        assertTrue("radio=1" in query && "kind=radio" in query, seen.single())
    }

    @Test
    fun `the guide request carries radio=1`() = withServer { client, seen ->
        runBlocking { client.getLiveTvGuide(days = 2, hoursBack = 4) }
        assertTrue(seen.single().startsWith("/api/tv/livetv/guide?"), seen.single())
        val query = seen.single().substringAfter('?').split('&')
        assertTrue("radio=1" in query && "days=2" in query && "hoursBack=4" in query, seen.single())
    }
}
