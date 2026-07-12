package dev.jellystructure.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end check of [installGzipCompression] wired into a real (in-process, ephemeral) Ktor
 * application via `testApplication` — not the live backend; this starts and stops entirely within
 * the test. GzipCompressionTest already verifies gzipCompress() itself is correct; this verifies the
 * *interceptor* actually fires on a real response, sets Content-Encoding, and is skippable by a
 * client that doesn't ask for it — the part that can't be exercised by calling the function directly.
 */
class GzipCompressionIntegrationTest {

    private val longBody = "The quick brown fox jumps over the lazy dog. ".repeat(100)

    @Test
    fun compressesAndTagsAResponseWhenTheClientAcceptsGzip() = testApplication {
        application {
            installGzipCompression()
            routing {
                get("/big") { call.respondText(longBody) }
            }
        }
        val response = client.get("/big") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
        val wireBytes = response.bodyAsBytes()
        assertTrue(wireBytes.size < longBody.encodeToByteArray().size, "response over the wire should be smaller than the original text")
    }

    @Test
    fun leavesTheResponseAloneWhenTheClientDoesNotAcceptGzip() = testApplication {
        application {
            installGzipCompression()
            routing {
                get("/big") { call.respondText(longBody) }
            }
        }
        val response = client.get("/big") // no Accept-Encoding header
        assertNull(response.headers[HttpHeaders.ContentEncoding])
        assertEquals(longBody, response.bodyAsBytes().decodeToString())
    }

    @Test
    fun leavesASmallResponseUncompressedEvenWhenGzipIsAccepted() = testApplication {
        application {
            installGzipCompression()
            routing {
                get("/small") { call.respondText("ok") }
            }
        }
        val response = client.get("/small") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertNull(response.headers[HttpHeaders.ContentEncoding])
        assertEquals("ok", response.bodyAsBytes().decodeToString())
    }
}
