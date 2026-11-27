package dev.jellystructure.server.routes

import dev.jellystructure.server.parseImageWidth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-5847 (FR-3, FR-4) — the admin's image routes: the optional `w`, the content ETag and `304`, the version rule. */
class AdminImageCacheTest {
    private val dir = "/tmp/jellystructure-test-admin-image-${platform.posix.getpid()}"

    private fun file(name: String, bytes: ByteArray): String {
        SystemFileSystem.createDirectories(Path(dir))
        val p = "$dir/$name"
        SystemFileSystem.sink(Path(p)).buffered().use { it.write(bytes, 0, bytes.size) }
        return p
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(600) { (it % 251).toByte() }

    @Test fun `w is parsed and bounded and absent means the original`() {
        assertNull(parseImageWidth(null))
        assertNull(parseImageWidth(""))
        assertNull(parseImageWidth("wide"))
        assertEquals(320, parseImageWidth("320"))
        assertEquals(32, parseImageWidth("1"), "a tiny width is raised, not honoured")
        assertEquals(1600, parseImageWidth("100000"), "a huge width is bounded")
    }

    @Test fun `an image answers with an ETag and a matching If-None-Match is a 304`() = testApplication {
        val path = file("a.jpg", jpeg)
        application { routing { get("/img") { serveFile(call, path) } } }
        val first = client.get("/img?v=1")
        assertEquals(HttpStatusCode.OK, first.status)
        val etag = assertNotNull(first.headers[HttpHeaders.ETag])
        val again = client.get("/img?v=1") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, again.status)
    }

    @Test fun `a versioned URL is cached for a day and an unversioned one is revalidated every time`() = testApplication {
        val path = file("b.jpg", jpeg)
        application { routing { get("/img") { serveFile(call, path) } } }
        val versioned = assertNotNull(client.get("/img?v=7").headers[HttpHeaders.CacheControl])
        assertTrue("max-age=86400" in versioned, versioned)
        val bare = assertNotNull(client.get("/img").headers[HttpHeaders.CacheControl])
        assertTrue("max-age=0" in bare, bare)
        assertTrue("max-age=60" !in versioned + bare, "private, max-age=60 is gone")
    }

    @Test fun `a replaced picture has new bytes and so a new ETag`() = testApplication {
        val path = file("c.jpg", jpeg)
        application { routing { get("/img") { serveFile(call, path) } } }
        val before = client.get("/img?v=1").headers[HttpHeaders.ETag]
        file("c.jpg", jpeg.reversedArray())
        val after = client.get("/img?v=1") { header(HttpHeaders.IfNoneMatch, before ?: "") }
        assertEquals(HttpStatusCode.OK, after.status, "the old ETag no longer matches")
    }

    @Test fun `a missing file is still a 404`() = testApplication {
        application { routing { get("/img") { serveFile(call, "$dir/none.jpg") } } }
        assertEquals(HttpStatusCode.NotFound, client.get("/img?w=96").status)
    }

    @Test fun `w with no artwork service serves the original unchanged`() = testApplication {
        val path = file("d.jpg", jpeg)
        application { routing { get("/img") { serveFile(call, path) } } }
        val r = client.get("/img?w=96&v=1")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(jpeg.size, r.headers[HttpHeaders.ContentLength]?.toInt())
    }
}
