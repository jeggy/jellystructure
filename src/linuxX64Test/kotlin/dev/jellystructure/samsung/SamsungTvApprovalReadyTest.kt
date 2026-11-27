package dev.jellystructure.samsung

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.SessionService
import dev.jellystructure.auth.installAuthPlugin
import dev.jellystructure.db.createDatabase
import dev.jellystructure.db.closeLastDatabaseForTests
import dev.jellystructure.io.FileIo
import dev.jellystructure.server.routes.samsungTvRoutes
import dev.jellystructure.shared.tv.SamsungTvRequest
import dev.jellystructure.tv.RaviloDeviceService
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


/** DEB-5409 (FR-7) — an approval of a DUID the verified release covers lands READY at once. */
class SamsungTvApprovalReadyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val wgtUrl = "https://github.com/jeggy/jellystructure/releases/download/v1.70/ravilo-tizen-1.70.wgt"

    private fun scenario(verify: Boolean, block: suspend io.ktor.server.testing.ApplicationTestBuilder.(SamsungTvRequests, String) -> Unit) = testApplication {
        val path = "/tmp/jellystructure-test-samsung-ready-${getpid()}.db"
        val db = createDatabase(path)
        val requests = SamsungTvRequests(db) { 1_800_000_000L }
        val fixture = FileIo.readBytes(Path("src/linuxX64Test/resources/samsung/ravilo-tizen-v1.70.wgt"))
        val release = SamsungTvRelease(requests, { 1_800_000_000L }) { url, _ ->
            if (url.endsWith("/releases/latest")) SamsungReleaseResponse(200, """{"tag_name":"v1.70","assets":[{"name":"ravilo-tizen-1.70.wgt","browser_download_url":"$wgtUrl"}]}""".encodeToByteArray(), "e")
            else SamsungReleaseResponse(200, fixture)
        }
        if (verify) release.refresh()
        val sessions = SessionService(db)
        val adminToken = sessions.create("root", "Root", "test")
        application {
            install(ContentNegotiation) { json(json) }
            installAuthPlugin(sessions, validateDeviceToken = { token, _, _ ->
                DeviceData(token, token, "AB-CD-1234", token, "jf", false).takeIf { token == "anna" }
            })
            routing { route("/api") { samsungTvRoutes(requests, release, RaviloDeviceService(db)) } }
        }
        try {
            block(requests, adminToken)
        } finally {
            closeLastDatabaseForTests()
            for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$path$suffix") }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.create(duid: String): SamsungTvRequest {
        val created = client.post("/api/tv/samsung-requests") {
            header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json)
            setBody("""{"duid":"$duid","model":"UE55AB1234","tizen_version":"6.0","name":"Den"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return json.decodeFromString(SamsungTvRequest.serializer(), created.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.decide(id: Long, token: String, decision: String) =
        client.post("/api/samsung-tv/requests/$id/decision") {
            header(HttpHeaders.Cookie, "js_session=$token"); contentType(ContentType.Application.Json)
            setBody("""{"decision":"$decision"}""")
        }

    @Test fun approvingACoveredDuidLandsReadyAtOnce() = scenario(verify = true) { requests, admin ->
        val row = create("EXCKEBBVWCGFC")
        assertEquals("REQUESTED", row.state)
        assertEquals(HttpStatusCode.OK, decide(row.id, admin, "APPROVE").status)
        val saved = requests.get(row.id)!!
        assertEquals("READY", saved.state)
        assertEquals("v1.70", saved.readyFromRelease)
        assertEquals(wgtUrl, saved.readyWgtUrl)
        assertEquals("Root", saved.decidedBy)
        assertEquals(1_800_000_000L, saved.decidedAt)
    }

    @Test fun approvingAnUncoveredDuidStaysApproved() = scenario(verify = true) { requests, admin ->
        val row = create("ZZZZZZZZZZZZZ")
        assertEquals(HttpStatusCode.OK, decide(row.id, admin, "APPROVE").status)
        assertEquals("APPROVED", requests.get(row.id)?.state)
    }

    @Test fun approvingWithNoVerifiedReleaseStaysApproved() = scenario(verify = false) { requests, admin ->
        val row = create("EXCKEBBVWCGFC")
        assertEquals(HttpStatusCode.OK, decide(row.id, admin, "APPROVE").status)
        assertEquals("APPROVED", requests.get(row.id)?.state)
    }

    @Test fun rejectingACoveredDuidStaysRejected() = scenario(verify = true) { requests, admin ->
        val row = create("EXCKEBBVWCGFC")
        assertEquals(HttpStatusCode.OK, decide(row.id, admin, "REJECT").status)
        assertEquals("REJECTED", requests.get(row.id)?.state)
    }
}
