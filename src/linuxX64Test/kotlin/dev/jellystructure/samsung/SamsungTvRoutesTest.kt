package dev.jellystructure.samsung

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.SessionService
import dev.jellystructure.auth.installAuthPlugin
import dev.jellystructure.db.createDatabase
import dev.jellystructure.db.closeLastDatabaseForTests
import dev.jellystructure.io.FileIo
import dev.jellystructure.server.routes.samsungTvRoutes
import dev.jellystructure.shared.tv.SamsungTvRequest
import dev.jellystructure.shared.tv.SamsungTvRequestList
import dev.jellystructure.tv.RaviloDeviceService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-4842 (FR-2/3/4) — real auth fence, routes, guarded persistence and public WGT proof. */
class SamsungTvRoutesTest {
    @Test fun certificateExpiryDatesHaveSixtyDayBoundary() {
        val author = kotlin.time.Instant.parse("2027-09-18T00:00:00Z").epochSeconds
        assertEquals(60, samsungCertificateStatus(author - 60L * 86_400).authorDaysLeft)
        assertEquals(61, samsungCertificateStatus(author - 61L * 86_400).authorDaysLeft)
        assertEquals(0, samsungCertificateStatus(author).authorDaysLeft)
    }
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun viewerOwnershipDecisionAndReadinessUseTheSignedPackage() = testApplication {
        val path = "/tmp/jellystructure-test-samsung-${getpid()}.db"
        val db = createDatabase(path)
        val requests = SamsungTvRequests(db) { 1_800_000_000L }
        val fixture = FileIo.readBytes(Path("src/linuxX64Test/resources/samsung/ravilo-tizen-v1.70.wgt"))
        val wgtUrl = "https://github.com/jeggy/jellystructure/releases/download/v1.70/ravilo-tizen-1.70.wgt"
        var now = 1_800_000_000L
        var metadataCalls = 0
        val release = SamsungTvRelease(requests, { now }) { url, etag ->
            if (url.endsWith("/releases/latest")) {
                metadataCalls++
                if (etag == "fixture-etag") SamsungReleaseResponse(304, byteArrayOf())
                else SamsungReleaseResponse(200, """{"tag_name":"v1.70","assets":[{"name":"ravilo-tizen-1.70.wgt","browser_download_url":"$wgtUrl"}]}""".encodeToByteArray(), "fixture-etag")
            } else { assertEquals(wgtUrl, url); SamsungReleaseResponse(200, fixture) }
        }
        val sessions = SessionService(db)
        val adminToken = sessions.create("root", "Root", "test")
        application {
            install(ContentNegotiation) { json(json) }
            installAuthPlugin(sessions, validateDeviceToken = { token, _, _ ->
                val user = when (token) { "anna" -> "AB-CD-1234"; "bo" -> "bbbb2222"; else -> null }
                user?.let { DeviceData(token, token, it, token, "jf", false) }
            })
            routing { route("/api") { samsungTvRoutes(requests, release, RaviloDeviceService(db)) } }
        }
        try {
            fun body(duid: String) = """{"duid":"$duid","model":"UE55AB1234","tizen_version":"6.0","name":"Living room"}"""
            val created = client.post("/api/tv/samsung-requests") { header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json); setBody(body("EXCKEBBVWCGFC")) }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val first = json.decodeFromString(SamsungTvRequest.serializer(), created.bodyAsText())
            assertEquals("abcd1234", requests.get(first.id)?.accountId)
            assertEquals(HttpStatusCode.OK, client.post("/api/tv/samsung-requests") { header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json); setBody(body("EXCKEBBVWCGFC")) }.status)
            val other = client.get("/api/tv/samsung-requests") { header(HttpHeaders.Authorization, "Bearer bo") }
            assertTrue(json.decodeFromString(SamsungTvRequestList.serializer(), other.bodyAsText()).requests.isEmpty())
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/tv/samsung-requests/${first.id}") { header(HttpHeaders.Authorization, "Bearer bo") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/samsung-tv/requests") { header(HttpHeaders.Authorization, "Bearer anna") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/samsung-tv/requests/${first.id}/decision") { header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json); setBody("""{"decision":"APPROVE"}""") }.status)
            val decision = client.post("/api/samsung-tv/requests/${first.id}/decision") { header(HttpHeaders.Cookie, "js_session=$adminToken"); contentType(ContentType.Application.Json); setBody("""{"decision":"APPROVE"}""") }
            assertEquals(HttpStatusCode.OK, decision.status, decision.bodyAsText())
            assertEquals("APPROVED", requests.get(first.id)?.state)
            assertEquals(HttpStatusCode.Conflict, client.post("/api/samsung-tv/requests/${first.id}/decision") { header(HttpHeaders.Cookie, "js_session=$adminToken"); contentType(ContentType.Application.Json); setBody("""{"decision":"REJECT"}""") }.status)
            assertEquals("v1.70", release.refresh().tag)
            assertEquals(1, metadataCalls)
            assertEquals("READY", requests.get(first.id)?.state)
            assertEquals("v1.70", requests.get(first.id)?.readyFromRelease)
            val secondCreated = client.post("/api/tv/samsung-requests") { header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json); setBody(body("XTCJYJZXZBZVK")) }
            val second = json.decodeFromString(SamsungTvRequest.serializer(), secondCreated.bodyAsText())
            assertEquals(HttpStatusCode.OK, client.post("/api/samsung-tv/requests/${second.id}/decision") { header(HttpHeaders.Cookie, "js_session=$adminToken"); contentType(ContentType.Application.Json); setBody("""{"decision":"APPROVE"}""") }.status)
            assertEquals("READY", requests.get(second.id)?.state)   // DEB-5409 FR-7: covered by the verified release, so READY at approval
            assertEquals(HttpStatusCode.Conflict, client.delete("/api/tv/samsung-requests/${first.id}") { header(HttpHeaders.Authorization, "Bearer anna") }.status)
            now += 3_600L
            assertEquals("v1.70", release.refresh().tag)
            assertEquals(2, metadataCalls)
            assertEquals("READY", requests.get(second.id)?.state)
            requests.reconcile("v1.71", "https://github.com/jeggy/jellystructure/releases/download/v1.71/ravilo-tizen-1.71.wgt", emptySet())
            assertEquals("APPROVED", requests.get(first.id)?.state)
        } finally {
            closeLastDatabaseForTests()
            for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$path$suffix") }
        }
    }

    @Test fun invalidAndWithdrawnRequestsFollowTheRouteContract() = testApplication {
        val path = "/tmp/jellystructure-test-samsung-invalid-${getpid()}.db"
        val db = createDatabase(path)
        val requests = SamsungTvRequests(db) { 1_800_000_000L }
        val release = SamsungTvRelease(requests, { 1_800_000_000L })
        val sessions = SessionService(db)
        val adminToken = sessions.create("root", "Root", "test")
        application {
            install(ContentNegotiation) { json(json) }
            installAuthPlugin(sessions, validateDeviceToken = { token, _, _ -> if (token == "anna") DeviceData("a", token, "anna", "Anna", "jf", false) else null })
            routing { route("/api") { samsungTvRoutes(requests, release, RaviloDeviceService(db)) } }
        }
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/tv/samsung-requests").status)
            val invalid = client.post("/api/tv/samsung-requests") { header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json); setBody("""{"duid":"BAD!","model":"TV","tizen_version":"6","name":"Test"}""") }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            val created = client.post("/api/tv/samsung-requests") { header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json); setBody("""{"duid":"ABCD12345678","model":"TV","tizen_version":"6","name":"Test"}""") }
            val row = json.decodeFromString(SamsungTvRequest.serializer(), created.bodyAsText())
            val deleted = client.delete("/api/tv/samsung-requests/${row.id}") { header(HttpHeaders.Authorization, "Bearer anna") }
            assertEquals(HttpStatusCode.OK, deleted.status)
            assertEquals("WITHDRAWN", json.decodeFromString(SamsungTvRequest.serializer(), deleted.bodyAsText()).state)
            assertEquals(HttpStatusCode.OK, client.delete("/api/tv/samsung-requests/${row.id}") { header(HttpHeaders.Authorization, "Bearer anna") }.status)
            val reopened = client.post("/api/tv/samsung-requests") { header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json); setBody("""{"duid":"ABCD12345678","model":"TV","tizen_version":"6","name":"Test"}""") }
            assertEquals(HttpStatusCode.Created, reopened.status)
            val rejected = client.post("/api/samsung-tv/requests/${row.id}/decision") { header(HttpHeaders.Cookie, "js_session=$adminToken"); contentType(ContentType.Application.Json); setBody("""{"decision":"REJECT","note":"Not this TV"}""") }
            assertEquals(HttpStatusCode.OK, rejected.status)
            val rejectDelete = client.delete("/api/tv/samsung-requests/${row.id}") { header(HttpHeaders.Authorization, "Bearer anna") }
            assertEquals("REJECTED", json.decodeFromString(SamsungTvRequest.serializer(), rejectDelete.bodyAsText()).state)
            for (n in 0 until 10) {
                val duid = "ABCD1234" + n.toString().padStart(4, '0')
                val r = client.post("/api/tv/samsung-requests") { header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json); setBody("""{"duid":"$duid","model":"TV","tizen_version":"6","name":"Test"}""") }
                assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
            }
            val overCap = client.post("/api/tv/samsung-requests") { header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json); setBody("""{"duid":"ABCD12349999","model":"TV","tizen_version":"6","name":"Test"}""") }
            assertEquals(HttpStatusCode.Conflict, overCap.status)
        } finally {
            closeLastDatabaseForTests()
            for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$path$suffix") }
        }
    }
}
