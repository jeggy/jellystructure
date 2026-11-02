package dev.jellystructure.autofetch

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.server.routes.autofetchRoutes
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2479 (DEB-2474, FR-4.3, FR-5.4, FR-12) — *Propose* (one model call under the limit, a proposal and never a number), the investigation's
 * `mapped` answer, and the two routes. A scripted model; stand-in corpus only.
 */
class ProposeAndMappedInvestigationTest {
    private val h = Harness()
    private val ai = FakeAi()

    @AfterTest
    fun tearDown() = h.close()

    private val url = "https://varpid.example.fo/sendingar/royndin"

    private fun tmdb() {
        h.world.tmdbEps = (1..4).map { TmdbEpisodeFact(1, it, listOf("", "Fyrsti", "Annar", "Triðji", "Fjórði")[it], "2026-0$it-12", "Lýsing $it") }
    }

    /** A mapped fetcher with R1 approved and R2 and R3 waiting. */
    private fun fetcher(): Fetcher = runBlocking {
        tmdb()
        val recipe = h.royndinRecipe.copy(numbering = NumberingSpec("mapped"), pattern = emptyList())
        val rows = listOf(MapRow("/sendingar/royndin/1", "Royndin 2026 · 1. partur", 1, 1, "same"))
        (h.service.save(SaveRequest(url, 9000001, false, recipe, mapping = rows, accept = listOf("R1"))) as SaveResult.Saved).fetcher
    }

    private fun proposer() = Proposer(ai, h.store, h.world, h.service)
    private fun propose(f: Fetcher, item: String) = runBlocking { proposer().propose(f.id, item) }
    private fun match(season: Int, episode: Int, why: String = "the title names it") = ai.answer("""{"verdict":"match","season":$season,"episode":$episode,"reason":"$why"}""")

    // ---- Propose ------------------------------------------------------------------------------------------------------------

    @Test
    fun `propose keeps the models answer as a proposal and downloads nothing`() {
        val f = fetcher(); match(1, 2)
        val r = propose(f, "R2")
        assertTrue(r is ProposeResult.Proposed, r.toString())
        val item = h.store.item(f.id, "R2")!!
        assertEquals(SeasonEpisode(1, 2), item.proposed); assertEquals("the title names it", item.proposedNote)
        assertNull(item.approved, "a proposal is not a number"); assertNull(item.season)
        assertEquals("waiting", item.state)
        assertEquals(0, h.tools.downloads.size)
        runBlocking { h.engine.run(f.id, RunTrigger.MANUAL) }
        assertEquals(1, h.tools.downloads.size, "only R1, which has an approved number")
        val view = AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, CoroutineScope(Dispatchers.Default)).view(f).waiting.first { it.itemId == "R2" }
        assertEquals("S01E02", view.proposal!!.code)
    }

    @Test
    fun `propose sends the items own words and tmdbs episodes at the full price and nothing from the library`() {
        val f = fetcher(); match(1, 2)
        propose(f, "R2")
        assertEquals(1, ai.sent.size); assertEquals(1, ai.recorded.size, "the turn's usage reaches the ledger")
        val req = Json.parseToJsonElement(ai.sent[0]).jsonObject
        val text = req["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("Royndin 2026 · 2. partur" in text && "Annar" in text && "Lýsing 2" in text, text)
        assertFalse(h.lib in ai.sent[0] || "Season 01" in ai.sent[0] || "R1" in text, "nothing from the library, and no numbers taken")
        assertEquals("json_schema", req["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(ai.runs.single().startsWith("proposal for Royndin · S01E02"), ai.runs.toString())
    }

    @Test
    fun `a no fits answer keeps nothing`() {
        val f = fetcher()
        ai.answer("""{"verdict":"none","reason":"two episodes fit equally well"}""")
        val r = propose(f, "R2")
        assertEquals("two episodes fit equally well", (r as ProposeResult.NoMatch).reason)
        assertNull(h.store.item(f.id, "R2")!!.proposed)
    }

    @Test
    fun `a proposal for an episode tmdb lacks or a number another item has is refused and kept as nothing`() {
        val f = fetcher()
        match(1, 9)
        val lacks = propose(f, "R2") as ProposeResult.Refused
        assertEquals(422, lacks.status); assertTrue("S01E09" in lacks.reason && "TMDB does not have" in lacks.reason, lacks.reason)
        match(1, 1)
        val taken = propose(f, "R2") as ProposeResult.Refused
        assertEquals(422, taken.status); assertTrue("already the number of" in taken.reason, taken.reason)
        ai.answer("not json")
        assertEquals(422, (propose(f, "R2") as ProposeResult.Refused).status)
        assertNull(h.store.item(f.id, "R2")!!.proposed)
    }

    @Test
    fun `propose is refused before a request when the limit is spent or AI is off or the fetcher has no tmdb series`() {
        val f = fetcher()
        ai.limit = 1L
        val limit = propose(f, "R2") as ProposeResult.Refused
        assertEquals(409, limit.status); assertTrue("limit reached" in limit.reason, limit.reason)
        ai.limit = 5_000_000L; ai.on = false
        val off = propose(f, "R2") as ProposeResult.Refused
        assertEquals(409, off.status); assertEquals("AI is switched off in Settings ▸ AI", off.reason)
        assertTrue(ai.sent.isEmpty(), "no request reached Anthropic")
        ai.on = true
        assertEquals(404, (propose(f, "nope") as ProposeResult.Refused).status)
        runBlocking { h.engine.run(f.id, RunTrigger.MANUAL) }
        assertEquals(409, (propose(f, "R1") as ProposeResult.Refused).status, "an item already in the library")
        val pageOnly = runBlocking { h.saveDagbok() }
        assertEquals(409, (runBlocking { proposer().propose(pageOnly.id, "x") } as ProposeResult.Refused).status)
        assertTrue(ai.sent.isEmpty())
    }

    // ---- the investigation's mapped answer ----------------------------------------------------------------------------------

    private fun investigator() = Investigator(
        ai, h.lanes, h.tools, h.world, RecipeProof(h.tools, h.world),
        libraries = { listOf(LibraryChoice(h.lib, "TV")) }, existing = { h.engine.existingCodes(it) }, today = { "2026-11-02" },
    )

    private fun mappedAnswer(vararg rows: String) =
        """{"verdict":"series","reason":"A dub that lists in order.","recipe":{"list":{"kind":"html","item_selector":"li.ep","fields":{"title":"a","link":"a@href","published":"time@datetime","id":"@data-id"}},"media":{"kind":"hls","manifest":"https://media.example.fo/{id}/master.m3u8"},"numbering":"mapped","hosts":["media.example.fo"],"audio_language":"fao"},"examples":[],"mapping":[${rows.joinToString(",")}]}"""

    private fun mrow(n: Int, season: Int = 1, episode: Int = n) =
        """{"link":"/sendingar/royndin/$n","title":"Royndin 2026 · $n. partur","season":$season,"episode":$episode,"why":"part $n is TMDB's $episode"}"""

    private fun investigate() = runBlocking { investigator().run("inv1", url, 9000001, false, null) { } }

    @Test
    fun `a mapped answer is a review whose every item is a row the ones placed carry a proposal and the rest wait`() {
        tmdb()
        ai.tool("fetch", """{"url":"$url"}""")
        ai.tool("tmdb_series", """{"id":9000001}""")
        ai.tool("probe", """{"url":"https://varpid.example.fo/media/R1/master.m3u8"}""")
        ai.answer(mappedAnswer(mrow(1), mrow(2)))
        val out = investigate()

        assertEquals("review", out.view.state, out.view.reason)
        val review = out.view.review!!
        assertEquals("mapped", review.numbering); assertFalse(review.proven); assertEquals("Mapped", review.pattern)
        assertTrue("mapped by the model's proposal" in review.proof && "2 of 3" in review.proof, review.proof)
        assertEquals(listOf("R1", "R2", "R3"), review.rows.map { it.itemId })
        assertEquals(listOf("S01E01", "S01E02", ""), review.rows.map { it.code })
        assertEquals(listOf("Fyrsti", "Annar", null), review.rows.map { it.tmdbName })
        assertEquals("part 1 is TMDB's 1", review.rows[0].why)
        assertEquals(2, assertNotNull(out.held).mapping.size)
        assertTrue("Lýsing 2" in ai.resultOf(2), "the model sees TMDB's overviews: ${ai.resultOf(2)}")
        // Nothing is written until Save, and Save numbers only what is ticked.
        assertTrue(h.store.fetchers().isEmpty())
        val saved = runBlocking { h.service.save(SaveRequest(url, 9000001, false, out.held!!.recipe, mapping = out.held!!.mapping, accept = listOf("R2"))) } as SaveResult.Saved
        assertEquals(mapOf("R1" to null, "R2" to SeasonEpisode(1, 2), "R3" to null), h.store.items(saved.fetcher.id).associate { it.itemId to it.approved })
    }

    @Test
    fun `a proposal naming an episode tmdb lacks is told back to the model and corrected`() {
        tmdb()
        ai.tool("fetch", """{"url":"$url"}""")
        ai.answer(mappedAnswer(mrow(1), mrow(2, 1, 9)))
        ai.answer(mappedAnswer(mrow(1), mrow(2)))
        val out = investigate()
        assertEquals("review", out.view.state, out.view.reason)
        val told = Json.parseToJsonElement(ai.sent[2]).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("S01E09, which TMDB does not have" in told, told)
        assertEquals("S01E02", out.view.review!!.rows[1].code)
    }

    @Test
    fun `a mapped answer that places no item fails after the model is told twice and writes nothing`() {
        ai.answer(mappedAnswer(MapRowJson.nowhere)); ai.answer(mappedAnswer(MapRowJson.nowhere)); ai.answer(mappedAnswer(MapRowJson.nowhere))
        tmdb()
        val out = investigate()
        assertEquals("failed", out.view.state)
        assertTrue("proposal maps no item" in out.view.reason.orEmpty(), out.view.reason)
        assertNull(out.held); assertTrue(h.store.fetchers().isEmpty())
    }

    private object MapRowJson { const val nowhere = """{"link":"/nowhere","title":"Nothing","season":1,"episode":1,"why":"x"}""" }

    // ---- the routes -----------------------------------------------------------------------------------------------------------

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(AutofetchRuntime) -> Unit) = testApplication {
        val cfg = ConfigStore("${h.dir}/config.toml").also { it.load() }
        runBlocking { cfg.update(cfg.current.copy(autofetch = dev.jellystructure.config.AutofetchConfig(enabled = true))) }
        val runtime = AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, CoroutineScope(Dispatchers.Default))
        runtime.proposer = proposer()
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            routing { route("/api") { autofetchRoutes(cfg, runtime) } }
        }
        block(runtime)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.post(path: String, body: String = "{}") =
        client.post(path) { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun `the number route approves refuses and finds`() = app { _ ->
        val f = fetcher()
        assertEquals(HttpStatusCode.OK, post("/api/autofetch/${f.id}/items/R2/number", """{"season":1,"episode":2}""").status)
        assertEquals(SeasonEpisode(1, 2), h.store.item(f.id, "R2")!!.approved)
        assertEquals(HttpStatusCode.Conflict, post("/api/autofetch/${f.id}/items/R3/number", """{"season":1,"episode":2}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, post("/api/autofetch/${f.id}/items/R3/number", """{"season":1,"episode":99999}""").status)
        assertEquals(HttpStatusCode.NotFound, post("/api/autofetch/${f.id}/items/nope/number", """{"season":1,"episode":3}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/api/autofetch/${f.id}/items/R3/number", """{"season":1}""").status)
    }

    @Test
    fun `an item id that is a link with slashes goes percent-encoded`() = app { _ ->
        val f = fetcher()
        val link = "https://varpid.example.fo/sendingar/royndin/9"
        h.store.upsertListed(f.id, link, ListedItem(title = "Royndin 2026 · 9. partur", link = link), null, null, 1)
        h.store.setItemState(f.id, link, "waiting", "no number approved yet", 0)
        val enc = "https%3A%2F%2Fvarpid.example.fo%2Fsendingar%2Froyndin%2F9"
        assertEquals(HttpStatusCode.OK, post("/api/autofetch/${f.id}/items/$enc/number", """{"season":1,"episode":4}""").status)
        assertEquals(SeasonEpisode(1, 4), h.store.item(f.id, link)!!.approved)
    }

    @Test
    fun `the propose route answers the proposal or the models no and the reason it was refused`() = app { rt ->
        val f = fetcher()
        match(1, 2, "same name")
        val yes = post("/api/autofetch/${f.id}/items/R2/propose")
        assertEquals(HttpStatusCode.OK, yes.status)
        assertTrue(""""proposed":true""" in yes.bodyAsText() || h.store.item(f.id, "R2")!!.proposed != null)
        assertEquals("same name", rt.view(f).waiting.first { it.itemId == "R2" }.proposal!!.note)
        ai.answer("""{"verdict":"none","reason":"nothing fits"}""")
        val no = post("/api/autofetch/${f.id}/items/R3/propose")
        assertEquals(HttpStatusCode.OK, no.status); assertTrue(""""proposed":false""" in no.bodyAsText() && "nothing fits" in no.bodyAsText(), no.bodyAsText())
        ai.limit = 1L
        assertEquals(HttpStatusCode.Conflict, post("/api/autofetch/${f.id}/items/R3/propose").status)
        assertEquals(HttpStatusCode.NotFound, post("/api/autofetch/nope/items/R3/propose").status)
    }
}
