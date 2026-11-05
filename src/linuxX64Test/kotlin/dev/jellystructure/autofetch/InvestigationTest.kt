package dev.jellystructure.autofetch

import dev.jellystructure.ai.AiPricing
import dev.jellystructure.ai.AnthropicClient
import dev.jellystructure.log.Logger
import dev.jellystructure.media.ActivityLog
import dev.jellystructure.jobs.WsBroadcaster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A model that answers from a script, and a ledger that counts what the investigation sent and spent. */
class FakeAi : InvestigationAi {
    var model = "claude-opus-5"
    var limit = 5_000_000L
    var spent = 0L
    var on = true
    val sent = ArrayList<String>()
    val recorded = ArrayList<AiPricing.Usage>()
    val runs = ArrayList<String>()
    val script = ArrayList<(String) -> AnthropicClient.Message>()
    var gate: CompletableDeferred<Unit>? = null
    private var n = 0

    override fun settings() = if (on) InvestigationSettings(model, "medium", limit) else null
    override fun offReason() = "AI is switched off in Settings ▸ AI"
    override fun spentMicroUsd() = spent
    override suspend fun send(requestJson: String): AnthropicClient.Message {
        sent += requestJson
        gate?.await()
        return script.removeAt(0)(requestJson)
    }
    override suspend fun record(model: String, usage: AiPricing.Usage) { recorded += usage; spent += AiPricing.fullCostMicroUsd(model, usage) }
    override fun noteRun(line: String) { runs += line }

    fun tool(name: String, input: String, before: () -> Unit = {}) = tools(name, listOf(input), before)
    /** One turn that asks for several tools at once. */
    fun tools(name: String, inputs: List<String>, before: () -> Unit = {}) { script += { _ -> before(); AnthropicClient.Message("tool_use", inputs.map { input -> buildJsonObject {
        put("type", "tool_use"); put("id", "tu${++n}"); put("name", name); put("input", Json.parseToJsonElement(input)) } }, AiPricing.Usage(5_000, 300)) } }
    fun answer(text: String) { script += { _ -> AnthropicClient.Message("end_turn", listOf(buildJsonObject { put("type", "text"); put("text", text) }), AiPricing.Usage(6_000, 800)) } }
    fun answerFrom(f: (String) -> String) { script += { req -> AnthropicClient.Message("end_turn", listOf(buildJsonObject { put("type", "text"); put("text", f(req)) }), AiPricing.Usage(6_000, 800)) } }

    /** Every tool result in the request at [i], as the model saw them. */
    fun results(i: Int): List<String> = Json.parseToJsonElement(sent[i]).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }

    /** The `content` of the tool result in the request at [i], as the model saw it. */
    fun resultOf(i: Int): String = Json.parseToJsonElement(sent[i]).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
    fun isError(i: Int): Boolean = Json.parseToJsonElement(sent[i]).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray[0].jsonObject["is_error"]?.jsonPrimitive?.content == "true"
}

/** DEB-2478 (DEB-2474, FR-5, FR-4.2, FR-15.9) — the investigation, against the stand-in site and a scripted model. */
class InvestigationTest {
    private val h = Harness()
    private val ai = FakeAi()

    @AfterTest
    fun tearDown() = h.close()

    private fun investigator() = Investigator(
        ai, h.lanes, h.tools, h.world, RecipeProof(h.tools, h.world),
        libraries = { listOf(LibraryChoice(h.lib, "TV")) }, existing = { h.engine.existingCodes(it) }, today = { "2026-11-02" },
    )

    private fun run(url: String, tmdbId: Int?, pageOnly: Boolean) = runBlocking {
        val views = ArrayList<InvestigationView>()
        investigator().run("inv1", url, tmdbId, pageOnly, null) { views += it } to views
    }

    private val dagbokAnswer = """{"verdict":"series","reason":"A daily programme with a dated listing.","recipe":{"list":{"kind":"html","item_selector":"li.ep","fields":{"title":"a","link":"a@href","published":"time@datetime","description":"p"}},"media":{"kind":"hls","manifest":"https://media.example.fo/{value}/master.m3u8","item_page":{"pattern":"var media = '(?<value>[A-Za-z0-9._-]+)'"}},"numbering":"date","series":{"name":"h1","description":"p.about","image":"img.poster@src"},"hosts":["media.example.fo"],"audio_language":"fao"},"examples":[{"title":"Dagbók · 27. oktober","date":"2026-10-27"}]}"""

    private val royndinAnswer = """{"verdict":"series","reason":"Year seasons.","recipe":{"list":{"kind":"html","item_selector":"li.ep","fields":{"title":"a","link":"a@href","published":"time@datetime","id":"@data-id"}},"media":{"kind":"hls","manifest":"https://media.example.fo/{id}/master.m3u8"},"numbering":"source","pattern":[{"on":"title","regex":"(?<season>\\d{4})"},{"on":"title","regex":"(?<episode>\\d+)\\. partur"}],"hosts":["media.example.fo"],"audio_language":"fao"},"examples":[{"title":"Royndin 2026 · 2. partur","season":2026,"episode":2}]}"""

    private fun dagbokTools() {
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok/27"}""")
        ai.tool("probe", """{"url":"https://media.example.fo/DOV7327/master.m3u8"}""")
    }

    @Test
    fun `a page-only page is read probed and proved and the review is the pages own`() {
        h.site.listingExtra = """<link rel="preload" href="https://media.example.fo/">"""
        dagbokTools(); ai.answer(dagbokAnswer)
        val (out, views) = run(h.dagbokUrl, null, true)

        assertEquals("review", out.view.state, out.view.reason)
        val review = assertNotNull(out.view.review)
        assertEquals("Dagbók", review.name); assertTrue(review.pageOnly); assertNull(review.tmdbId)
        assertEquals(12, review.found); assertEquals(12, review.missing); assertEquals(0, review.have)
        assertEquals("S2026E300", review.rows.first { it.date == "2026-10-27" }.code)
        assertTrue("each with its own date" in review.proof, review.proof)
        assertEquals(listOf("done", "done", "done", "done", "done"), out.view.steps.map { it.state })
        assertEquals(4, ai.sent.size, "three tool turns and the answer")
        assertEquals(4, ai.recorded.size, "every turn's usage reaches the ledger")
        assertTrue(views.any { it.state == "running" && it.steps[0].state == "run" }, "the first step is running while the page is read")
        assertNotNull(out.held)
        assertEquals("claude-opus-5", out.held.recipe.writtenBy)
        assertEquals("2026-11-02", out.held.recipe.writtenAt)
        assertEquals(0, h.world.tmdbCalls, "a page-only investigation asks TMDB nothing")
        assertTrue(ai.runs.single().startsWith("found · Dagbók"))
    }

    @Test
    fun `a page-only conversation offers no tmdb tool and says the fetcher is page-only`() {
        dagbokTools(); ai.answer(dagbokAnswer)
        run(h.dagbokUrl, null, true)
        val first = Json.parseToJsonElement(ai.sent[0]).jsonObject
        assertEquals(listOf("fetch", "probe"), first["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue("PAGE-ONLY" in first["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue("series" in first["output_config"]!!.jsonObject["format"]!!.jsonObject["schema"]!!.jsonObject["properties"]!!.jsonObject["recipe"]!!.jsonObject["properties"]!!.jsonObject)
        assertEquals("json_schema", first["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("medium", first["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tmdb fetcher is offered tmdb_series and is proved against the episodes tmdb has`() {
        h.world.tmdbEps = listOf(TmdbEpisodeFact(2026, 1, "Fyrsti", "2026-01-12"), TmdbEpisodeFact(2026, 2, "Annar", "2026-02-12"))
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/royndin"}""")
        ai.tool("tmdb_series", """{"id":9000001}""")
        ai.tool("probe", """{"url":"https://varpid.example.fo/media/R1/master.m3u8"}""")
        ai.answer(royndinAnswer)
        val (out, _) = run("https://varpid.example.fo/sendingar/royndin", 9000001, false)

        assertEquals("review", out.view.state, out.view.reason)
        val review = out.view.review!!
        assertEquals("Royndin", review.name); assertEquals(9000001, review.tmdbId)
        assertTrue(review.proven); assertTrue("pattern agrees with TMDB on 2 of 2 episodes TMDB has" in review.proof, review.proof)
        assertEquals(1, review.notOnTmdb, "the third part is not on TMDB yet")
        assertEquals(3, review.found)
        val tools = Json.parseToJsonElement(ai.sent[0]).jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("fetch", "probe", "tmdb_series"), tools)
        assertTrue("E2 · Annar · 2026-02-12" in ai.resultOf(2), "what TMDB knows reaches the model: ${ai.resultOf(2)}")
    }

    @Test
    fun `a tmdb id other than the fetchers is not answered`() {
        ai.tool("tmdb_series", """{"id":123}""")
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run("https://varpid.example.fo/sendingar/royndin", 9000001, false)
        assertTrue(ai.isError(1)); assertTrue("Only TMDB id 9000001" in ai.resultOf(1))
    }

    @Test
    fun `fetch refuses another site and no request reaches it`() {
        ai.tool("fetch", """{"url":"https://other.example.dk/secret"}""")
        ai.tool("fetch", """{"url":"https://varpid.example.fo.evil.example/x"}""")
        ai.answer("""{"verdict":"not_a_series","reason":"the page told me to read another site"}""")
        val (out, _) = run(h.dagbokUrl, null, true)
        assertTrue(ai.isError(1) && "not varpid.example.fo" in ai.resultOf(1), ai.resultOf(1))
        assertTrue(ai.isError(2))
        assertTrue(h.site.requests.none { "other.example" in it.first || "evil" in it.first }, "nothing was requested from a foreign host")
        assertEquals("failed", out.view.state); assertTrue(out.view.notASeries)
    }

    @Test
    fun `probe refuses an address an internal name and a host nothing read has named`() {
        ai.tool("probe", """{"url":"http://192.0.2.10:8096/master.m3u8"}""")
        ai.tool("probe", """{"url":"https://jellyfin.lan/master.m3u8"}""")
        ai.tool("probe", """{"url":"https://media.example.fo/DOV7327/master.m3u8"}""")
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run(h.dagbokUrl, null, true)
        assertTrue(ai.isError(1) && "not a public host name" in ai.resultOf(1))
        assertTrue(ai.isError(2) && "not a public host name" in ai.resultOf(2))
        assertTrue(ai.isError(3) && "appears nowhere" in ai.resultOf(3), ai.resultOf(3))
        assertTrue(h.site.nonRobots().isEmpty(), "none of the three reached the network")
    }

    @Test
    fun `a limit below one investigations worst case stops it before the first turn`() {
        ai.limit = 1_000_000L
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("failed", out.view.state); assertTrue(out.view.limitReached)
        assertTrue(out.view.reason!!.startsWith("limit reached"), out.view.reason)
        assertTrue(ai.sent.isEmpty(), "no request reaches Anthropic")
        assertEquals(0, out.view.turns)
    }

    @Test
    fun `a limit stop marks the step that was running as the failed one and leaves none running`() {
        ai.limit = 1_000_000L
        val (out, _) = run(h.dagbokUrl, null, true)
        assertTrue(out.view.limitReached)
        assertTrue(ai.sent.isEmpty(), "no request reaches Anthropic")
        assertEquals(listOf("fail", "pending", "pending", "pending", "pending"), out.view.steps.map { it.state })
    }

    @Test
    fun `the limit is checked again before each turn against what the ledger has spent`() {
        // Room for the first turn; between the turns the month's spend (another job's, say) takes the rest.
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.script.add(0) { _ -> ai.spent = 4_900_000L; AnthropicClient.Message("tool_use", listOf(buildJsonObject {
            put("type", "tool_use"); put("id", "t0"); put("name", "fetch"); put("input", Json.parseToJsonElement("""{"url":"https://varpid.example.fo/sendingar/dagbok"}""")) }), AiPricing.Usage(5_000, 300)) }
        val (out, _) = run(h.dagbokUrl, null, true)
        assertTrue(out.view.limitReached, out.view.reason)
        assertEquals(1, ai.sent.size, "the second turn was never sent")
        assertEquals(1, out.view.turns)
        assertTrue(ai.runs.single().startsWith("stopped at the limit"), ai.runs.single())
    }

    @Test
    fun `twelve turns without an answer fails on the step that was running and is not a series - DEB-3695`() {
        repeat(Investigator.MAX_TURNS) { ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""") }
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("failed", out.view.state); assertFalse(out.view.notASeries)
        assertEquals("the page could not be worked out in 12 steps", out.view.reason)
        assertEquals(Investigator.MAX_TURNS, ai.sent.size)
        assertEquals("fail", out.view.steps.first { it.key == "videos" }.state)
    }

    @Test
    fun `from turn 9 every tool answer ends with the turn count and before it none does - DEB-3695 FR-5_9`() {
        repeat(Investigator.MAX_TURNS) { ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""") }
        run(h.dagbokUrl, null, true)
        // ai.sent[i] is turn i + 1's request, whose last message carries turn i's answers.
        assertFalse("of 12. Answer with the recipe" in ai.resultOf(8))
        assertTrue(ai.resultOf(9).endsWith("Turn 9 of 12. Answer with the recipe by turn 12; the engine keeps two more turns for its corrections."), ai.resultOf(8))
        assertTrue(ai.resultOf(11).endsWith("Turn 11 of 12. Answer with the recipe by turn 12; the engine keeps two more turns for its corrections."), ai.resultOf(11))
    }

    @Test
    fun `an answer on turn 12 that is refused gets a thirteenth turn to be corrected - DEB-3695 FR-5_9`() {
        repeat(Investigator.MAX_TURNS - 1) { ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""") }
        ai.answer("not a recipe")
        ai.answer(dagbokAnswer)
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("review", out.view.state, out.view.reason)
        assertEquals(13, ai.sent.size); assertEquals(13, out.view.turns)
    }

    @Test
    fun `two refusals past turn 12 end the investigation as a failure with the last refusal and never as not a series - DEB-3695 FR-5_9`() {
        repeat(Investigator.MAX_TURNS - 1) { ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""") }
        repeat(3) { ai.answer("not a recipe") }
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("failed", out.view.state); assertFalse(out.view.notASeries)
        assertEquals("the model's answer was not in the recipe's form", out.view.reason)
        assertEquals(14, ai.sent.size)
    }

    @Test
    fun `a tool request past turn 12 after a refusal ends it with that refusal on its step - DEB-3695 FR-5_9`() {
        repeat(Investigator.MAX_TURNS - 1) { ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""") }
        ai.answer("not a recipe")
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("failed", out.view.state); assertFalse(out.view.notASeries)
        assertEquals("the model's answer was not in the recipe's form", out.view.reason)
        assertEquals(13, ai.sent.size)
        assertEquals("fail", out.view.steps.first { it.key == "script" }.state)
    }

    @Test
    fun `a model that says not a series fails the series step with its reason and writes nothing`() {
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.answer("""{"verdict":"not_a_series","reason":"1 video found, no recurring title"}""")
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("failed", out.view.state); assertTrue(out.view.notASeries)
        assertEquals("1 video found, no recurring title", out.view.reason)
        assertEquals(listOf("done", "done", "fail", "pending", "pending"), out.view.steps.map { it.state })
        assertTrue(h.store.fetchers().isEmpty())
        assertNull(out.held)
    }

    @Test
    fun `a recipe whose numbers differ from the models own examples is told back once and can be corrected`() {
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.answer(dagbokAnswer.replace("\"date\":\"2026-10-27\"", "\"date\":\"2026-10-26\""))
        ai.answer(dagbokAnswer)
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("review", out.view.state, out.view.reason)
        assertEquals(3, ai.sent.size)
        val told = Json.parseToJsonElement(ai.sent[2]).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("the engine reads" in told && "2026-10-27" in told, told)
    }

    private fun systemOf(i: Int) = Json.parseToJsonElement(ai.sent[i]).jsonObject["system"]!!.let { it as? JsonArray ?: JsonArray(listOf(it)) }.joinToString("\n") { e -> e.jsonObject["text"]?.jsonPrimitive?.content ?: e.toString() }

    private fun toldBack(i: Int) = Json.parseToJsonElement(ai.sent[i]).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content

    @Test
    fun `the prompt sends items with no number to mapped on a tmdb fetcher and to date on a page-only one - DEB-3459`() {
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run("https://varpid.example.fo/sendingar/royndin", 9000001, false)
        val tmdb = systemOf(0)
        assertTrue("items that carry none" in tmdb && "answer `numbering: mapped` with `mapping`" in tmdb, tmdb)
        assertTrue("never Python's `(?P<value>…)`" in tmdb && "(?<value>…)" in tmdb, tmdb)
        val pageOnlyAi = FakeAi(); val saved = ai.sent.size
        pageOnlyAi.answer("""{"verdict":"not_a_series","reason":"x"}""")
        runBlocking { Investigator(pageOnlyAi, h.lanes, h.tools, h.world, RecipeProof(h.tools, h.world), { emptyList() }, { emptySet() }, { "2026-11-03" }).run("inv2", h.dagbokUrl, null, true, null) {} }
        val prompt = Json.parseToJsonElement(pageOnlyAi.sent[0]).jsonObject["system"]!!.toString()
        assertTrue("answer `numbering: date`" in prompt && "numbering: mapped` with `mapping` (step 5)" !in prompt, prompt)
        assertEquals(1, saved)
    }

    @Test
    fun `a source recipe that numbers no item is told to answer mapped on a tmdb fetcher - DEB-3459`() {
        h.world.tmdbEps = listOf(TmdbEpisodeFact(2026, 1, "Fyrsti", "2026-01-12"), TmdbEpisodeFact(2026, 2, "Annar", "2026-02-12"))
        ai.answer(royndinAnswer.replace("""{"on":"title","regex":"(?<episode>\\d+)\\. partur"}""", """{"on":"title","regex":"(?<episode>\\d+)\\. nonexistent"}"""))
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run("https://varpid.example.fo/sendingar/royndin", 9000001, false)
        val told = toldBack(1)
        assertTrue("maps to no season and episode; if the items carry no number of their own, answer `numbering: mapped` with `mapping`" in told, told)
    }

    @Test
    fun `a source recipe that numbers no item is told to answer date on a page-only fetcher - DEB-3459`() {
        ai.answer(dagbokAnswer.replace("\"numbering\":\"date\"", "\"numbering\":\"source\",\"pattern\":[{\"on\":\"title\",\"regex\":\"(?<episode>\\\\d+)x\"}]"))
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run(h.dagbokUrl, null, true)
        val told = toldBack(1)
        assertTrue("maps to no season and episode; if the items carry no number of their own, answer `numbering: date`" in told && "mapped" !in told, told)
    }

    // ---- DEB-3695 (FR-4.2): a few shared dates are re-uploads, not a broken numbering ----

    private fun days(range: IntRange) = range.map { it to "2026-10-${it.toString().padStart(2, '0')}" }

    @Test
    fun `one pair on a shared date passes the date proof and the review marks the one a run will not fetch - DEB-3695 FR-4_2`() {
        h.site.dagbokItems = listOf(100 to "2026-10-27") + days(16..27).reversed()
        dagbokTools(); ai.answer(dagbokAnswer)
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("review", out.view.state, out.view.reason)
        val rows = out.view.review!!.rows
        val shares = rows.filter { it.code.startsWith("shares ") }
        assertEquals(1, shares.size, rows.toString())
        assertTrue(shares.single().code.startsWith("shares 2026-10-27 with '") && shares.single().code.endsWith("' · one is fetched"), shares.single().code)
        assertEquals(1, rows.count { it.code == "S2026E300" }, "the pair's number is shown once, on the item a run fetches")
    }

    @Test
    fun `three items on one date fail the date proof - DEB-3695 FR-4_2`() {
        h.site.dagbokItems = listOf(100 to "2026-10-27", 101 to "2026-10-27") + days(16..27).reversed()
        dagbokTools(); ai.answer(dagbokAnswer); ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run(h.dagbokUrl, null, true)
        assertTrue("3 items share the date 2026-10-27" in toldBack(4), toldBack(4))
    }

    @Test
    fun `more than a quarter of the items sharing a date fails the date proof - DEB-3695 FR-4_2`() {
        h.site.dagbokItems = listOf(100 to "2026-10-26", 101 to "2026-10-27") + days(26..27).reversed()
        dagbokTools(); ai.answer(dagbokAnswer); ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run(h.dagbokUrl, null, true)
        assertTrue("share the date" in toldBack(4), toldBack(4))
    }

    // ---- DEB-3567 (FR-5.1): a programme that airs more than once a day ----

    private val kvoldbokAnswer = dagbokAnswer.replace("\"published\":\"time@datetime\"", "\"published\":\"span.when\"")
        .replace("\"numbering\":\"date\"", "\"numbering\":\"datetime\",\"date_pattern\":{\"on\":\"published\",\"regex\":\"(?P<day>\\\\d{1,2})\\\\.\\\\s*(?P<month>\\\\p{L}+)\\\\.?\\\\s+(?P<year>\\\\d{4})\\\\s+(?P<hour>\\\\d{1,2})[.:](?P<minute>\\\\d{2})\",\"months\":[{\"name\":\"Nov\",\"month\":11}]}")
        .replace("""[{"title":"Dagbók · 27. oktober","date":"2026-10-27"}]""", """[{"title":"Kvøldbók · 3","season":1,"episode":1,"date":"2026-11-03 18:30"}]""")

    private fun kvoldbokTools() {
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/kvoldbok"}""")
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/kvoldbok/3"}""")
        ai.tool("probe", """{"url":"https://media.example.fo/KV3/master.m3u8"}""")
    }

    @Test
    fun `a page-only prompt offers datetime for a programme that airs more than once a day and the schema lists it - DEB-3567`() {
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run(h.kvoldbokUrl, null, true)
        val prompt = systemOf(0)
        assertTrue("numbering: datetime" in prompt && "at most once a day" in prompt, prompt)
        assertTrue("\"datetime\"" in ai.sent[0], "the schema's numbering enum offers datetime")
    }

    @Test
    fun `a date recipe refused for a shared date is told to answer datetime when the text gives a time - DEB-3567`() {
        kvoldbokTools()
        ai.answer(kvoldbokAnswer.replace("\"numbering\":\"datetime\"", "\"numbering\":\"date\""))   // the date pattern is kept, so it reads the dates
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run(h.kvoldbokUrl, null, true)
        val told = toldBack(ai.sent.size - 1)
        assertTrue("3 items share the date 2026-11-03; every item needs a number of its own; if the published text gives a time, answer `numbering: datetime`" in told, told)
    }

    @Test
    fun `a datetime answer reaches the review with the time after each date and its season and episode examples are not compared - DEB-3567`() {
        kvoldbokTools(); ai.answer(kvoldbokAnswer)
        val (out, _) = run(h.kvoldbokUrl, null, true)
        val review = assertNotNull(out.view.review)
        assertEquals("datetime", review.numbering)
        assertEquals(5, review.found)
        assertTrue(review.rows.any { it.code == "S2644E808" && it.date == "2026-11-03 18.30" }, review.rows.toString())
        assertEquals("By date and time", review.pattern)
        assertTrue("each with its own date and time" in review.proof, review.proof)
    }

    @Test
    fun `a datetime answer whose example names another date is told back - DEB-3567`() {
        kvoldbokTools()
        ai.answer(kvoldbokAnswer.replace("\"date\":\"2026-11-03 18:30\"", "\"date\":\"2026-11-03 18:35\""))
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run(h.kvoldbokUrl, null, true)
        assertTrue("on 'Kvøldbók · 3' you said" in toldBack(ai.sent.size - 1))
    }

    private fun <T> withActivityLog(body: suspend (ActivityLog) -> T): T = runBlocking {
        val dir = "/tmp/jellystructure-test-inv-log-${platform.posix.getpid()}"
        platform.posix.system("rm -rf '$dir'; mkdir -p '$dir'")
        val activity = ActivityLog("$dir/activity.json", WsBroadcaster(), CoroutineScope(Dispatchers.Default))
        Logger.activityLog = activity
        try { body(activity) } finally { Logger.activityLog = null; platform.posix.system("rm -rf '$dir'") }
    }

    @Test
    fun `each refused answer and a not_a_series verdict writes one autofetch line with the reason and the answer - DEB-3459`() = withActivityLog { activity ->
        ai.answer("this is not json")
        ai.answer("""{"verdict":"series","reason":"a series"}""")
        ai.answer("""{"verdict":"not_a_series","reason":"the model's own words"}""")
        investigator().run("inv9", h.dagbokUrl, null, true, null) {}
        val lines = activity.list(1, 100, "autofetch", null).entries.map { it.message }
        assertEquals(3, lines.size, lines.toString())
        assertTrue(lines.all { "investigation inv9" in it })
        assertTrue(lines.any { "turn 1 refused: the model's answer was not in the recipe's form · answer: this is not json" in it }, lines.toString())
        assertTrue(lines.any { "turn 2 refused: the model gave no recipe" in it && """"verdict":"series"""" in it }, lines.toString())
        assertTrue(lines.any { "turn 3 answered not_a_series: the model's own words" in it }, lines.toString())
    }

    @Test
    fun `a run that reaches the review logs only its refusals and a long answer is cut at 8 kB - DEB-3459`() = withActivityLog { activity ->
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.answer(dagbokAnswer.replace("\"date\":\"2026-10-27\"", "\"date\":\"2026-10-26\""))
        ai.answer(dagbokAnswer)
        val out = investigator().run("inv8", h.dagbokUrl, null, true, null) {}
        assertEquals("review", out.view.state, out.view.reason)
        val first = activity.list(1, 100, "autofetch", null).entries.single().message
        assertTrue("turn 2 refused: on 'Dagbók" in first && "the engine reads" in first && "2026-10-26" in first, first)

        val long = "x".repeat(20_000)
        ai.answer("""{"verdict":"not_a_series","reason":"$long"}""")
        investigator().run("inv7", h.dagbokUrl, null, true, null) {}
        val line = activity.list(1, 100, "autofetch", null).entries.first { "inv7" in it.message }.message
        assertTrue(line.length < 10_000 && "cut at 8192 characters" in line, line.length.toString())
    }

    @Test
    fun `a recipe that names an address as a host is refused by its shape`() {
        ai.answer(dagbokAnswer.replace("\"media.example.fo\"", "\"192.0.2.10\""))
        ai.answer("""{"verdict":"not_a_series","reason":"giving up"}""")
        val (out, _) = run(h.dagbokUrl, null, true)
        assertTrue("not a public host name" in Json.parseToJsonElement(ai.sent[1]).toString(), "the model is told why")
        assertEquals("failed", out.view.state)
    }

    @Test
    fun `a manifest with DRM ends the investigation with FR-3 3s sentence`() {
        h.site.drm = true; h.site.listingExtra = """<link href="https://media.example.fo/">"""
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.tool("probe", """{"url":"https://media.example.fo/DOV7327/master.m3u8"}""")
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("this site protects its videos; Auto-fetch does not get past that", out.view.reason)
        assertEquals("fail", out.view.steps[1].state)
    }

    @Test
    fun `a robots txt that forbids the path ends the investigation with the reason`() {
        h.site.robots = "User-agent: *\nDisallow: /sendingar\n"
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("this site's robots.txt does not allow /sendingar/dagbok", out.view.reason)
        assertEquals("fail", out.view.steps[0].state)
        assertEquals(1, ai.sent.size, "the model is not asked again")
    }

    @Test
    fun `a robots txt that names Anthropics agents does not bind this server and what it read goes to the model`() {
        h.site.robots = "User-agent: ClaudeBot\nDisallow: /\nUser-agent: anthropic-ai\nDisallow: /\nUser-agent: *\nDisallow: /private\n"
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run(h.dagbokUrl, null, true)
        assertFalse(ai.isError(1)); assertTrue("Dagbók · 27. oktober" in ai.resultOf(1))
    }

    @Test
    fun `requests are ten seconds apart and an investigation reads at most twenty`() {
        val page = """{"url":"https://varpid.example.fo/sendingar/dagbok"}"""
        ai.tools("fetch", List(10) { page })
        ai.tools("fetch", List(12) { page })
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run(h.dagbokUrl, null, true)
        val reads = h.site.requests.filter { !it.first.endsWith("/robots.txt") }.map { it.second }
        assertEquals(Investigator.MAX_TOOL_REQUESTS, reads.size)
        assertTrue(reads.zipWithNext().all { (a, b) -> b - a >= 10_000 }, "ten seconds between two requests")
        val last = ai.results(2)
        assertTrue(last.takeLast(2).all { "budget" in it }, last.takeLast(2).toString())
    }

    @Test
    fun `a model call refused on the first turn marks the step that was running as the failed one`() {
        ai.script += { throw AnthropicClient.ApiException(400, "Schemas contains too many optional parameters (74)") }
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("Anthropic answered 400", out.view.reason)
        assertEquals(listOf("fail", "pending", "pending", "pending", "pending"), out.view.steps.map { it.state })
    }

    @Test
    fun `a model that is refused its key says so`() {
        ai.script += { throw AnthropicClient.ApiException(401, "no") }
        val (out, _) = run(h.dagbokUrl, null, true)
        assertEquals("The key was refused — Settings ▸ AI", out.view.reason)
    }

    @Test
    fun `page text drops styles svg and external scripts and keeps the markup and inline script ids`() {
        val html = "<html><head><style>.a{color:red}</style><script src=\"/x.js\"></script></head><body><!-- c --><svg><path d=\"M0\"/></svg><li class=\"ep\" style=\"x:y\"><a href=\"/e/1\">One</a></li><script>var media = 'DOV1';</script></body></html>"
        val t = PageText.reduce(html, 60_000)
        assertFalse("color:red" in t || "x.js" in t || "<svg" in t || "<!--" in t || "style=" in t)
        assertTrue("<li class=\"ep\"><a href=\"/e/1\">One</a></li>" in t && "var media = 'DOV1';" in t, t)
        assertTrue(PageText.reduce("x".repeat(70_000), 60_000).endsWith("[cut at 60 kB]"))
    }

    // ---- DEB-3643 — the site's own player script, and Videos not found ------------------------------------------------------------

    @Test
    fun `page text keeps an external script on the pages own site as an empty tag and removes any other site's`() {
        val html = "<html><head><script async src=\"/js/player.js?v=3\" type=\"text/javascript\">x()</script><script src='https://varpid.example.fo/b.js'></script>" +
            "<script src=\"https://cdn.other.example/lib.js\"></script><script src=\"//www.varpid.example.fo/c.js\"></script></head><body><script>var media = 'M1';</script></body></html>"
        val t = PageText.reduce(html, 60_000, "https://varpid.example.fo/sendingar/dagbok/27")
        assertTrue("<script src=\"https://varpid.example.fo/js/player.js?v=3\"></script>" in t, t)
        assertTrue("<script src=\"https://varpid.example.fo/b.js\"></script>" in t && "<script src=\"https://www.varpid.example.fo/c.js\"></script>" in t, t)
        assertFalse("cdn.other" in t || "x()" in t || "async" in t || "text/javascript" in t, t)
        assertTrue("var media = 'M1';" in t)
        assertFalse("src=" in PageText.reduce(html, 60_000), "with no page address nothing is kept")
    }

    @Test
    fun `fetch of a script answers its text unreduced and cut at 60 kB`() {
        ai.limit = 50_000_000L
        h.site.itemExtra = "<script src=\"/js/player.js\"></script><script src=\"https://cdn.other.example/lib.js\"></script>"
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok/27"}""")
        ai.tool("fetch", """{"url":"https://varpid.example.fo/js/player.js"}""")
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        val (o, _) = run(h.dagbokUrl, null, true)
        assertEquals(3, ai.sent.size, "${o.view.state}: ${o.view.reason}")
        assertTrue("<script src=\"https://varpid.example.fo/js/player.js\"></script>" in ai.resultOf(1) && "cdn.other" !in ai.resultOf(1), ai.resultOf(1))
        assertTrue("return 'https://media.example.fo/' + media + '/master.m3u8'" in ai.resultOf(2) && "<p>not html</p>" in ai.resultOf(2), "no HTML reduction on a script")
        assertTrue(ai.resultOf(2).contains("[cut at 60 kB]"))
        assertTrue("media.example.fo" in ai.resultOf(2) && h.site.requests.any { it.first.endsWith("/js/player.js") })
    }

    @Test
    fun `a manifest host named only by the player script is probe-able and its edge redirect is trusted`() {
        ai.limit = 50_000_000L
        h.site.itemExtra = "<script src=\"/js/player.js\"></script>"
        h.site.masterEdge = "edge3.example.fo"
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok/27"}""")
        ai.tool("fetch", """{"url":"https://varpid.example.fo/js/player.js"}""")
        ai.tool("probe", """{"url":"https://media.example.fo/DOV7327/master.m3u8"}""")
        ai.answer(dagbokAnswer)
        val (out, _) = run(h.dagbokUrl, null, true)
        assertFalse(ai.isError(3), ai.resultOf(3))
        assertTrue("Final address: https://edge3.example.fo/" in ai.resultOf(3), ai.resultOf(3))
        assertEquals("review", out.view.state, out.view.reason)
    }

    @Test
    fun `not_a_series after a listing of three items ends as Videos not found in the servers words`() {
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        h.site.days = listOf(27, 26, 25)
        ai.answer(dagbokAnswer.replace("\"verdict\":\"series\"", "\"verdict\":\"not_a_series\"").replace("A daily programme with a dated listing.", "no manifest"))
        val (out, _) = run(h.dagbokUrl, null, true)
        val v = out.view
        assertEquals("failed", v.state)
        assertTrue(v.videosNotFound, v.reason); assertFalse(v.notASeries)
        assertEquals("It didn't work: varpid.example.fo lists 3 episodes, but Auto-fetch could not find how it plays them.", v.reason)
        assertEquals(listOf("done", "fail", "done", "pending", "pending"), v.steps.map { it.state })
        assertEquals("videos not found", v.report!!.body.lineSequence().first { it.startsWith("Ended:") }.removePrefix("Ended: ").substringBefore(" ·"))
        assertTrue(ai.runs.single().startsWith("videos not found"), ai.runs.single())
        assertFalse("no manifest" in v.report!!.body, "the model's words stay out of the report")
    }

    @Test
    fun `a videos_not_found answer needs a listing of two items and otherwise is Not a series`() {
        ai.answer("""{"verdict":"videos_not_found","reason":"one clip"}""")
        val (out, _) = run(h.dagbokUrl, null, true)
        assertTrue(out.view.notASeries); assertFalse(out.view.videosNotFound)
        val ai2 = FakeAi(); h.site.days = listOf(27, 26)
        ai2.answer(dagbokAnswer.replace("\"verdict\":\"series\"", "\"verdict\":\"videos_not_found\""))
        val o2 = runBlocking { Investigator(ai2, h.lanes, h.tools, h.world, RecipeProof(h.tools, h.world), libraries = { listOf(LibraryChoice(h.lib, "TV")) }, existing = { h.engine.existingCodes(it) }, today = { "2026-11-02" }).run("inv2", h.dagbokUrl, null, true, null) {} }
        assertTrue(o2.view.videosNotFound, o2.view.reason); assertFalse(o2.view.notASeries)
        assertTrue("lists 2 episodes" in o2.view.reason!!)
    }

    // ---- the registry: one at a time, stop, save ---------------------------------------------------------------------------------

    private fun registry(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)): Investigations {
        var ids = 0
        return Investigations(ai, investigator(), h.service, h.store, h.world, scope) { "inv${++ids}" }
    }

    @Test
    fun `a blank tmdb id without the tick a film id and an unknown id are refused before a cent is spent`() = runBlocking {
        val r = registry()
        assertEquals(400, (r.start(InvestigateRequest(h.dagbokUrl)) as StartResult.Refused).status)
        assertTrue("tick" in (r.start(InvestigateRequest(h.dagbokUrl)) as StartResult.Refused).reason)
        h.world.kind = TmdbKind.FILM
        assertEquals("this id is a film — Auto-fetch needs a series", (r.start(InvestigateRequest("https://varpid.example.fo/sendingar/royndin", 9000001)) as StartResult.Refused).reason)
        h.world.kind = TmdbKind.UNKNOWN
        assertEquals(422, (r.start(InvestigateRequest("https://varpid.example.fo/sendingar/royndin", 9000001)) as StartResult.Refused).status)
        assertEquals(400, (r.start(InvestigateRequest(h.dagbokUrl, 9000001, true)) as StartResult.Refused).status)
        assertEquals(400, (r.start(InvestigateRequest("ftp://x")) as StartResult.Refused).status)
        assertTrue(ai.sent.isEmpty())
    }

    @Test
    fun `the gate carries the reason Investigate is disabled with`() {
        val r = registry()
        assertTrue(r.gate().allowed)
        ai.on = false
        assertEquals("AI is switched off in Settings ▸ AI", r.gate().reason)
        ai.on = true; ai.spent = 5_000_000L
        assertEquals("This month's Auto-fetch limit is spent (\$5.00 of \$5.00)", r.gate().reason)
        ai.spent = 0; h.world.on = false
        assertTrue("[autofetch] enabled" in r.gate().reason!!)
    }

    private suspend fun awaitState(r: Investigations, vararg states: String): InvestigationView {
        repeat(2000) { val v = r.view(); if (v != null && v.state in states) return v; kotlinx.coroutines.delay(10) }
        error("never reached ${states.toList()}: ${r.view()}")
    }

    @Test
    fun `one investigation at a time and Stop discards what it found and writes nothing`() = runBlocking {
        val r = registry()
        ai.gate = CompletableDeferred()
        dagbokTools(); ai.answer(dagbokAnswer)
        val started = r.start(InvestigateRequest(h.dagbokUrl, null, true)) as StartResult.Started
        assertEquals("running", started.view.state)
        repeat(500) { if (ai.sent.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(1, ai.sent.size, "the first turn is out and waiting for the model")
        assertEquals(409, (r.start(InvestigateRequest(h.dagbokUrl, null, true)) as StartResult.Refused).status)
        assertEquals("An investigation is already running", r.gate().reason)
        assertTrue(r.stop(started.view.id))
        assertEquals("stopped", r.view()!!.state)
        ai.gate!!.complete(Unit)
        repeat(20) { yield(); kotlinx.coroutines.delay(10) }
        assertEquals("stopped", r.view()!!.state, "a stopped investigation stays stopped")
        assertTrue(h.store.fetchers().isEmpty())
        assertEquals(1, ai.sent.size, "it never went on to the next turn")
        assertTrue(r.discard(started.view.id)); assertNull(r.view())
    }

    @Test
    fun `saving a reviewed investigation stores the fetcher it proved and then it is gone`() = runBlocking {
        val r = registry()
        h.site.listingExtra = """<link rel="preload" href="https://media.example.fo/">"""
        dagbokTools(); ai.answer(dagbokAnswer)
        val id = (r.start(InvestigateRequest(h.dagbokUrl, null, true)) as StartResult.Started).view.id
        val v = awaitState(r, "review", "failed")
        assertEquals("review", v.state, v.reason)
        assertEquals(RetentionSpec("newest", 12, true), v.review!!.retention, "a page-only fetcher starts on the newest 12")
        assertEquals(RetentionPreview(0, 8, 12), r.preview(id, RetentionSpec("newest", 8, false)))

        val saved = r.save(id, InvestigationSave(name = "Dagbók", retention = RetentionSpec("newest", 8, true), schedule = "daily@05:00", runFirst = false)) as InvestigationSaveResult.Saved
        assertEquals("Dagbók", saved.fetcher.name); assertTrue(saved.fetcher.pageOnly)
        assertEquals(RetentionSpec("newest", 8, true), saved.fetcher.retention); assertEquals("daily@05:00", saved.fetcher.schedule)
        assertEquals("claude-opus-5", saved.fetcher.recipe!!.writtenBy)
        assertEquals(1, h.store.fetchers().size)
        assertNull(r.view())
        assertEquals(404, (r.save(id, InvestigationSave()) as InvestigationSaveResult.Refused).status)
    }

    @Test
    fun `re-investigating replaces the recipe and the old one runs until then`() = runBlocking {
        val f = h.saveDagbok()
        val before = f.recipeJson
        val r = registry()
        h.site.listingExtra = """<link rel="preload" href="https://media.example.fo/">"""
        dagbokTools(); ai.answer(dagbokAnswer)
        val id = (r.start(InvestigateRequest(fetcherId = f.id)) as StartResult.Started).view.id
        assertEquals(f.pageUrl, r.view()!!.url)
        val v = awaitState(r, "review", "failed")
        assertEquals("review", v.state, v.reason)
        assertEquals(f.id, v.review!!.replaces)
        assertEquals(before, h.store.fetcher(f.id)!!.recipeJson, "nothing changed while the admin reviews")
        h.store.setState(f.id, "needs_reinvestigation", "the page changed")
        val saved = r.save(id, InvestigationSave()) as InvestigationSaveResult.Saved
        assertEquals(f.id, saved.fetcher.id)
        assertEquals("ok", h.store.fetcher(f.id)!!.state)
        assertEquals("claude-opus-5", h.store.fetcher(f.id)!!.recipe!!.writtenBy)
        assertEquals(f.retention, h.store.fetcher(f.id)!!.retention, "the fetcher keeps its Keep")
        assertEquals(1, h.store.fetchers().size)
    }
}
