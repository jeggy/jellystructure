package dev.jellystructure.autofetch

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
import kotlin.test.assertTrue

/** DEB-4341 (DEB-2474, FR-5.11, FR-5.12, FR-5.7) — *Acceptance* 25: Re-investigate starts from the saved recipe, a give-up answer's recipe is proved, and a media id set deep in a script is kept. */
class InvestigationSavedRecipeTest {
    private val h = Harness()
    private val ai = FakeAi()

    @AfterTest
    fun tearDown() = h.close()

    private fun investigator() = Investigator(
        ai, h.lanes, h.tools, h.world, RecipeProof(h.tools, h.world),
        libraries = { listOf(LibraryChoice(h.lib, "TV")) }, existing = { h.engine.existingCodes(it) }, today = { "2026-11-02" },
    )

    private fun run(fetcherId: String? = null, saved: String? = null) = runBlocking { investigator().run("inv1", h.dagbokUrl, null, true, fetcherId, false, saved) {} }

    private val series = """{"verdict":"series","reason":"A daily programme with a dated listing.","recipe":{"list":{"kind":"html","item_selector":"li.ep","fields":{"title":"a","link":"a@href","published":"time@datetime","description":"p"}},"media":{"kind":"hls","manifest":"https://media.example.fo/{value}/master.m3u8","item_page":{"pattern":"var media = '(?<value>[A-Za-z0-9._-]+)'"}},"numbering":"date","series":{"name":"h1","description":"p.about","image":"img.poster@src"},"hosts":["media.example.fo"],"audio_language":"fao"},"examples":[{"title":"Dagbók · 27. oktober","date":"2026-10-27"}]}"""
    private val giveUp = series.replace("\"verdict\":\"series\"", "\"verdict\":\"videos_not_found\"")
    private val wrongManifest = giveUp.replace("https://media.example.fo/{value}/master.m3u8", "https://media.example.fo/nowhere/{value}/stream.m3u8")

    private fun firstMessage(i: Int = 0): String = Json.parseToJsonElement(ai.sent[i]).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
    private fun lastMessage(i: Int): String = Json.parseToJsonElement(ai.sent[i]).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
    private fun system(i: Int = 0): String = Json.parseToJsonElement(ai.sent[i]).jsonObject["system"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    // ---- FR-5.7 -------------------------------------------------------------------------------------------------------------------

    private fun script(body: String) = "<html><script>$body</script></html>"
    private val filler = "// filler filler filler filler filler filler filler filler\n".repeat(30)

    @Test
    fun `an inline script keeps its first 1500 characters then a marker then the line that sets the media id`() {
        val body = filler + "var media = 'ABC123';\n" + filler
        val t = PageText.reduce(script(body), 60_000)
        assertTrue(t.contains(body.trim().take(1_500) + "\n/* … */\nvar media = 'ABC123';</script>"), t)
        assertFalse(t.length > 1_700, "the rest of the script is still cut: ${t.length}")
    }

    @Test
    fun `a script with thirty such lines keeps twenty and a 300 character string is not kept`() {
        val lines = (1..30).joinToString("\n") { "const k$it = \"v$it\";" }
        val t = PageText.reduce(script(filler + lines), 60_000)
        assertTrue("const k1 = \"v1\";" in t && "const k20 = \"v20\";" in t, t)
        assertFalse("k21" in t, t)
        assertEquals(1, Regex("""/\* … \*/""").findAll(t).count())
        val long = PageText.reduce(script(filler + "var big = '" + "x".repeat(300) + "';\nname = 'ok';"), 60_000)
        assertFalse("xxxxxxxxxx" in long, "a long string is not kept"); assertTrue("name = 'ok';" in long, long)
    }

    @Test
    fun `a short script and a long one with nothing to keep are left as they were`() {
        assertTrue("<script>var media = 'M1';</script>" in PageText.reduce(script("var media = 'M1';"), 60_000))
        val t = PageText.reduce(script(filler + "f(1);\nvar x = compute();"), 60_000)
        assertFalse("/* … */" in t, t)
    }

    // ---- FR-5.11 ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `re-investigate proves the saved recipe before turn one and hands it to the model without counting those reads`() {
        val f = runBlocking { h.saveDagbok() }
        h.site.itemPadding = 5_000
        var readsBeforeTurn1 = -1
        ai.answerFrom { readsBeforeTurn1 = h.site.nonRobots().size; series }
        val out = run(f.id, f.recipeJson)

        assertEquals("review", out.view.state, out.view.reason)
        assertTrue(readsBeforeTurn1 > 2, "the proof read the listing, an item page and the manifest before the model was asked: $readsBeforeTurn1")
        assertEquals(0, out.view.requests, "the proof's reads are not among the model's 20")
        assertEquals(1, ai.sent.size)
        val first = firstMessage()
        assertTrue("This fetcher's saved recipe:" in first && "\"manifest\":\"https://media.example.fo/{value}/master.m3u8\"" in first, first)
        assertTrue("The saved recipe still proves: 12 items, the media probe answered." in first, first)
        assertFalse("mapping" in first, first)
        assertTrue("keep every part of the saved recipe that still proves" in system())
        assertEquals("done", out.view.steps[0].state)
    }

    @Test
    fun `a saved recipe whose manifest no longer answers has the media checks reason in the first message`() {
        val f = runBlocking { h.saveDagbok() }
        ai.answer(series)
        val out = run(f.id, f.recipeJson.replace("master.m3u8", "gone.m3u8"))
        val first = firstMessage()
        assertTrue("The engine ran the saved recipe against today's page and it did not hold:" in first, first)
        assertFalse("still proves" in first, first)
        assertEquals("review", out.view.state, out.view.reason)
    }

    @Test
    fun `a saved recipe that does not parse says so and the investigation runs as a new one`() {
        ai.answer(series)
        val out = run("f1", "{ not a recipe")
        val first = firstMessage()
        assertTrue("The saved recipe could not be read" in first, first)
        assertFalse("This fetcher's saved recipe:" in first || "keep every part" in system())
        assertEquals("review", out.view.state, out.view.reason)
    }

    @Test
    fun `a site whose robots txt now forbids the listing ends the investigation before the model is asked and offers the tick`() {
        val f = runBlocking { h.saveDagbok() }
        h.site.robots = "User-agent: *\nDisallow: /sendingar\n"; h.clock.advanceHours(25)
        val out = run(f.id, f.recipeJson).view
        assertEquals("failed", out.state, out.reason)
        assertTrue(out.robotsRefused, out.reason.orEmpty()); assertEquals(0, ai.sent.size, "no model call was made")
    }

    @Test
    fun `a new investigation sends no saved recipe line`() {
        ai.answer(series)
        run()
        assertFalse("saved recipe" in firstMessage().lowercase(), firstMessage())
        assertFalse("saved recipe" in system())
    }

    // ---- FR-5.12 ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a videos_not_found answer with the right recipe reaches the review`() {
        ai.answer(giveUp)
        val out = run()
        assertEquals("review", out.view.state, out.view.reason)
        assertNotNull(out.held)
        assertFalse(out.view.videosNotFound)
        assertTrue(ai.runs.single().startsWith("found · Dagbók"), ai.runs.single())
    }

    @Test
    fun `not_a_series after a listing of two items with the right recipe reaches the review`() {
        h.site.days = listOf(27, 26)
        ai.answer(series.replace("\"verdict\":\"series\"", "\"verdict\":\"not_a_series\""))
        assertEquals("review", run().view.state)
    }

    @Test
    fun `a wrong manifest comes back as a correction while one is left and the corrected recipe reaches the review`() {
        ai.answer(wrongManifest); ai.answer(series)
        val out = run()
        assertEquals("review", out.view.state, out.view.reason)
        assertEquals(2, ai.sent.size)
        assertTrue("its media did not hold" in lastMessage(1), lastMessage(1))
    }

    @Test
    fun `a wrong manifest with no correction left ends Videos not found and not Out of steps`() {
        ai.answer(wrongManifest); ai.answer(wrongManifest); ai.answer(wrongManifest)
        val out = run()
        assertEquals(3, ai.sent.size)
        assertEquals("failed", out.view.state)
        assertTrue(out.view.videosNotFound, out.view.reason); assertFalse(out.view.outOfSteps); assertFalse(out.view.notASeries)
        assertTrue("lists 12 episodes" in out.view.reason!!, out.view.reason)
    }

    @Test
    fun `an answer that fails earlier than the media keeps the models ending and spends no correction`() {
        val noName = giveUp.replace("\"name\":\"h1\"", "\"name\":\"h1.none\"")
        ai.answer(noName)
        val out = run()
        assertEquals(1, ai.sent.size)
        assertTrue(out.view.videosNotFound, out.view.reason)
    }

    @Test
    fun `a videos_not_found answer with an empty media part ends Videos not found and no item page or manifest is read`() {
        ai.answer(giveUp.replace("\"manifest\":\"https://media.example.fo/{value}/master.m3u8\"", "\"manifest\":\"\"").replace(",\"item_page\":{\"pattern\":\"var media = '(?<value>[A-Za-z0-9._-]+)'\"}", ""))
        val out = run()
        assertTrue(out.view.videosNotFound, out.view.reason)
        assertTrue(h.site.nonRobots().none { it.contains("/sendingar/dagbok/") || it.contains("m3u8") }, h.site.nonRobots().toString())
    }
}
