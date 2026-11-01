package dev.jellystructure.autofetch

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.server.routes.autofetchRoutes
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2476 (FR-12) — the routes' contracts, in-process, against the stand-in site. */
class AutofetchRoutesTest {
    private val h = Harness()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterTest
    fun tearDown() = h.close()

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = ConfigStore("${h.dir}/config.toml").also { it.load() }
        runBlocking { cfg.update(cfg.current.copy(autofetch = dev.jellystructure.config.AutofetchConfig(enabled = true))) }
        val runtime = AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, CoroutineScope(Dispatchers.Default))
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            routing { route("/api") { autofetchRoutes(cfg, runtime) } }
        }
        block()
    }

    private fun save() = json.encodeToString(SaveRequest.serializer(), SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe))

    @Test
    fun `save lists patches previews and deletes`() = app {
        val bad = client.post("/api/autofetch") { contentType(ContentType.Application.Json); setBody(json.encodeToString(SaveRequest.serializer(), SaveRequest(h.dagbokUrl, null, false, h.dagbokRecipe))) }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue("Auto-fetch needs a TMDB id" in bad.bodyAsText())

        val created = client.post("/api/autofetch") { contentType(ContentType.Application.Json); setBody(save()) }
        assertEquals(HttpStatusCode.Created, created.status)
        val id = h.store.fetchers().single().id

        val list = client.get("/api/autofetch").bodyAsText()
        assertTrue("\"enabled\":true" in list && "\"pageOnly\":true" in list && "\"name\":\"Dagbók\"" in list, list)

        h.engine.run(id, RunTrigger.MANUAL)
        val preview = client.post("/api/autofetch/$id/retention/preview") { contentType(ContentType.Application.Json); setBody("""{"retention":{"mode":"newest","keep":8,"leaves_page":false}}""") }
        assertEquals("""{"deletes":4,"keeps":8,"onPage":12}""", preview.bodyAsText())

        val patched = client.patch("/api/autofetch/$id") { contentType(ContentType.Application.Json); setBody("""{"retention":{"mode":"newest","keep":8,"leaves_page":false},"schedule":"every6h"}""") }
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals(RetentionSpec("newest", 8, false), h.store.fetcher(id)!!.retention); assertEquals("every6h", h.store.fetcher(id)!!.schedule)
        assertEquals(HttpStatusCode.BadRequest, client.patch("/api/autofetch/$id") { contentType(ContentType.Application.Json); setBody("""{"schedule":"every1h"}""") }.status)
        assertEquals(HttpStatusCode.NotFound, client.patch("/api/autofetch/nope") { contentType(ContentType.Application.Json); setBody("""{"enabled":false}""") }.status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/autofetch/$id").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/autofetch/$id").status)
        assertTrue(h.onDisk("${h.lib}/Dagbók/Season 2026"), "delete never removes a file")
    }

    @Test
    fun `the queue is listed with the page's words and its stills are served to the admin and can be cut again`() = app {
        h.world.tmdbEps = listOf(TmdbEpisodeFact(2026, 1, "Útfall", "2026-01-10"), TmdbEpisodeFact(2026, 2, "Onnur", "2026-02-12"))
        val saved = h.service.save(SaveRequest("https://varpid.example.fo/sendingar/royndin", 9000001, false, h.royndinRecipe)) as SaveResult.Saved
        h.engine.run(saved.fetcher.id, RunTrigger.MANUAL)
        val row = h.store.queueOf(saved.fetcher.id).single()

        val list = client.get("/api/autofetch").bodyAsText()
        assertTrue("\"queue\":[{\"id\":${row.id}" in list && "\"code\":\"S2026E03\"" in list && "\"series\":\"Royndin\"" in list, list)
        assertTrue("\"title\":\"Royndin 2026 · 3. partur\"" in list, "the page's own heading, verbatim")
        assertFalse("\"overview\":\"" in list, "the page has no description, so none is sent: $list")
        assertTrue("\"queued\":1" in list, "the fetcher's row carries its queue count")

        val still = client.get("/api/autofetch/queue/${row.id}/still/0")
        assertEquals(HttpStatusCode.OK, still.status); assertEquals("image/jpeg", still.contentType()?.toString())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/autofetch/queue/${row.id}/still/7").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/autofetch/queue/99999/still/0").status)

        val cut = client.post("/api/autofetch/queue/${row.id}/still/2") { contentType(ContentType.Application.Json); setBody("{\"at\":600}") }
        assertEquals(HttpStatusCode.OK, cut.status); assertEquals(600, h.store.queueRow(row.id)!!.stills[2])
        h.tools.blackAt = 5
        assertEquals(HttpStatusCode.UnprocessableEntity, client.post("/api/autofetch/queue/${row.id}/still/2") { contentType(ContentType.Application.Json); setBody("{\"at\":5}") }.status)

        assertEquals(HttpStatusCode.OK, client.post("/api/autofetch/queue/${row.id}/submitted").status)
        assertTrue("\"state\":\"submitted\"" in client.get("/api/autofetch").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.post("/api/autofetch/queue/99999/submitted").status)
        // Once TMDB returns the episode the row is history: it leaves the list.
        h.store.queueCleared(row.id, h.world.nowSec())
        assertFalse("\"queue\":[{" in client.get("/api/autofetch").bodyAsText())
        assertEquals(HttpStatusCode.Conflict, client.post("/api/autofetch/queue/${row.id}/submitted").status)
    }

    @Test
    fun `a stopped site is listed with how many fetchers it stops`() = app {
        h.saveDagbok()
        h.site.listingStatus = 403
        h.engine.run(h.store.fetchers().single().id, RunTrigger.MANUAL)
        val list = client.get("/api/autofetch").bodyAsText()
        assertTrue("\"sites\":[{\"site\":\"varpid.example.fo\",\"stopped\":true" in list && "\"fetchers\":1" in list, list)
    }

    @Test
    fun `run and run-all answer 409 while auto-fetch is switched off`() = testApplication {
        val cfg = ConfigStore("${h.dir}/config.toml").also { it.load() }   // default: off
        val runtime = AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, CoroutineScope(Dispatchers.Default))
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { route("/api") { autofetchRoutes(cfg, runtime) } }
        }
        assertEquals(HttpStatusCode.Conflict, client.post("/api/autofetch/x/run").status)
        assertEquals(HttpStatusCode.Conflict, client.post("/api/autofetch/run-all").status)
        assertFalse("\"enabled\":true" in client.get("/api/autofetch").bodyAsText())
    }

    @Test
    fun `resume answers 409 for a site that is not stopped`() = app {
        assertEquals(HttpStatusCode.Conflict, client.post("/api/autofetch/sites/varpid.example.fo/resume").status)
    }

    @Test
    fun `a fetcher is due when its schedule comes round and never sooner than six hours after it was saved`() = runBlocking<Unit> {
        val rt = AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, CoroutineScope(Dispatchers.Default))
        val f = h.saveDagbok()
        assertTrue(rt.due(h.clock.nowSec()).isEmpty(), "saving a fetcher does not start a download")
        assertTrue(rt.due(h.clock.nowSec() + 30 * 3600L).map { it.id }.contains(f.id))
        h.service.update(f.id, null, null, false)
        assertTrue(rt.due(h.clock.nowSec() + 30 * 3600L).isEmpty(), "a paused fetcher is not due")
        h.service.update(f.id, null, null, true)
        h.store.setState(f.id, "needs_reinvestigation", "0 videos found")
        assertTrue(rt.due(h.clock.nowSec() + 30 * 3600L).isEmpty(), "a fetcher that needs re-investigation is not due")
        h.store.setState(f.id, "ok", "")
        h.lanes.forSite("varpid.example.fo").stop("blocked")
        assertTrue(rt.due(h.clock.nowSec() + 30 * 3600L).isEmpty(), "a stopped site runs nothing")
    }
}
