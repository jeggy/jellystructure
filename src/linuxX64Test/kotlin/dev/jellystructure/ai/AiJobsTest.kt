package dev.jellystructure.ai

import dev.jellystructure.config.AiConfig
import dev.jellystructure.config.AiJobConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.Keyword
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 270 (FR-270-9, dev review items 5 and 8) — the AI jobs against a stand-in for Anthropic. */
class AiJobsTest {
    private lateinit var dbPath: String
    private lateinit var db: JellystructureDb
    private lateinit var configStore: ConfigStore

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-ai-${getpid()}.db"
        db = createDatabase(dbPath)
        configStore = ConfigStore("/tmp/jellystructure-test-ai-${getpid()}.toml")
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
        runCatching { platform.posix.remove("/tmp/jellystructure-test-ai-${getpid()}.toml") }
    }

    /** A stand-in for Anthropic: records every call, answers from [handler]. */
    private class FakeTransport(val handler: (method: String, url: String, body: String?) -> AnthropicClient.Response) : AnthropicClient.Transport {
        val calls = ArrayList<String>()
        override suspend fun get(url: String, apiKey: String): AnthropicClient.Response { calls += "GET $url"; return handler("GET", url, null) }
        override suspend fun post(url: String, apiKey: String, body: String): AnthropicClient.Response { calls += "POST $url"; return handler("POST", url, body) }
    }

    private fun film(n: Int, keywords: Boolean = true) = MediaItem(
        id = "m$n", title = "Film $n", year = 2000 + n, kind = MediaKind.MOVIE, path = "/m/$n.mkv", tmdbId = 2000 + n,
        originalLanguage = "en", posterPath = null, overview = "A story about number $n.", tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        jellyfinId = "jf$n", genres = listOf("Drama"),
        keywords = if (keywords) listOf(Keyword(n, "kw$n")) else emptyList(),
    )

    private fun jobs(transport: FakeTransport, library: List<MediaItem> = (1..10).map { film(it) }) =
        AiJobs(db, configStore, library = { library }, client = AnthropicClient(transport), clock = { 1_790_000_000L })

    private fun enable(rerank: Boolean = true, themes: Boolean = false, limit: Double = 5.0, on: Boolean = true) = runBlocking {
        configStore.update(configStore.current.copy(ai = AiConfig(
            enabled = on, apiKey = "sk-ant-test-0000abcd",
            rerank = AiJobConfig(enabled = rerank, model = "claude-opus-5", effort = "medium", monthlyLimitUsd = limit),
            themes = AiJobConfig(enabled = themes, model = "claude-haiku-4-5", effort = "low", monthlyLimitUsd = limit),
        )))
    }

    @Test
    fun `the ai block round-trips through config_toml`() = runBlocking {
        enable(rerank = true, themes = true, limit = 7.5)
        val reread = ConfigStore("/tmp/jellystructure-test-ai-${getpid()}.toml").also { it.load() }.current.ai
        assertEquals(configStore.current.ai, reread)
        assertEquals(7.5, reread.rerank.monthlyLimitUsd)
        assertEquals("claude-haiku-4-5", reread.themes.model)
    }

    // ─── Pricing (FR-270-5/6) ─────────────────────────────────────────────────

    @Test
    fun `batch prices are half and cache writes and reads are 1_25x and 0_1x of input`() {
        // Opus 5: $5 / $25 per MTok standard, so $2.50 / $12.50 in a batch.
        assertEquals(2_500_000, AiPricing.costMicroUsd("claude-opus-5", AiPricing.Usage(input = 1_000_000, output = 0)))
        assertEquals(12_500_000, AiPricing.costMicroUsd("claude-opus-5", AiPricing.Usage(input = 0, output = 1_000_000)))
        assertEquals(3_125_000, AiPricing.costMicroUsd("claude-opus-5", AiPricing.Usage(input = 0, output = 0, cacheWrite = 1_000_000)))
        assertEquals(250_000, AiPricing.costMicroUsd("claude-opus-5", AiPricing.Usage(input = 0, output = 0, cacheRead = 1_000_000)))
        // FR-270-7's re-rank assumption on Opus 5: 12k in, 5k out ≈ $0.093.
        assertEquals(92_500, AiPricing.costMicroUsd("claude-opus-5", AiPricing.Usage(input = 12_000, output = 5_000)))
        assertEquals(18_500, AiPricing.costMicroUsd("claude-haiku-4-5", AiPricing.Usage(input = 12_000, output = 5_000)))
        assertEquals("$0.09", AiPricing.usd(92_500))
    }

    @Test
    fun `the limit refuses a batch whose worst case would pass it`() {
        assertTrue(AiPricing.wouldPassLimit(spentMicroUsd = 4_900_000, worstCaseMicroUsd = 200_000, limitUsd = 5.0))
        assertFalse(AiPricing.wouldPassLimit(spentMicroUsd = 4_700_000, worstCaseMicroUsd = 200_000, limitUsd = 5.0))
    }

    @Test
    fun `a batch that would pass the limit is not sent and the run says so`() = runBlocking {
        enable(limit = 0.01)
        val t = FakeTransport { _, _, _ -> error("nothing may be sent") }
        val sent = jobs(t).submitRerank(listOf(AiJobs.RerankInput("u1", "s1", (1..10).map { "jf$it" }, listOf("jf1"))))
        assertFalse(sent)
        assertTrue(t.calls.isEmpty())
        assertEquals(0L, db.aiQueries.countBatches().executeAsOne())
        assertTrue(db.aiQueries.runFor(AiRequests.RERANK_JOB).executeAsOne().line.startsWith("skipped: limit reached"))
    }

    // ─── Validation (FR-270-4, dev review item 5) ─────────────────────────────

    private val shortlist = (1..60).map { "jf$it" }
    private fun answer(ids: List<String>, stop: String = "end_turn", type: String = "succeeded") =
        AiRequests.Result("c", type, stop, """{"picks":[${ids.joinToString(",") { """{"id":"$it","reason":"r"}""" }}]}""", null)

    @Test
    fun `a full clean answer is kept in its order`() {
        val picks = AiRequests.validateRerank(answer((50 downTo 1).map { "t$it" }), shortlist)!!
        assertEquals(50, picks.size)
        assertEquals("jf50", picks.first().jellyfinId)
    }

    @Test
    fun `a foreign id discards the whole answer`() = assertNull(AiRequests.validateRerank(answer(listOf("t1", "t999")), shortlist))

    @Test
    fun `a repeated id discards the whole answer`() = assertNull(AiRequests.validateRerank(answer(listOf("t1", "t2", "t1")), shortlist))

    @Test
    fun `eighteen picks are topped up from the standard order`() {
        val picks = AiRequests.validateRerank(answer((30 downTo 13).map { "t$it" }), shortlist)!!
        assertEquals(50, picks.size)
        assertEquals((30 downTo 13).map { "jf$it" } + ((1..12) + (31..62)).map { "jf$it" }.filter { it in shortlist }.take(32), picks.map { it.jellyfinId })
        assertTrue(picks.drop(18).all { it.reason.isEmpty() })
    }

    @Test
    fun `a refusal or a cut-off answer keeps the standard list`() {
        assertNull(AiRequests.validateRerank(answer(listOf("t1"), stop = "refusal"), shortlist))
        assertNull(AiRequests.validateRerank(answer(listOf("t1"), stop = "max_tokens"), shortlist))
        assertNull(AiRequests.validateRerank(answer(listOf("t1"), type = "errored"), shortlist))
    }

    @Test
    fun `themes are lower-cased and de-duplicated and capped at eight`() {
        val r = AiRequests.Result("c", "succeeded", "end_turn", """{"themes":["Island Life"," island life ","A","B","C","D","E","F","G","H"]}""", null)
        assertEquals(listOf("island life", "a", "b", "c", "d", "e", "f", "g"), AiRequests.validateThemes(r))
    }

    @Test
    fun `haiku gets no effort parameter and every request asks for structured JSON`() {
        val haiku = AiRequests.themesRequest("t1", "claude-haiku-4-5", "low", film(1, keywords = false))
        val opus = AiRequests.themesRequest("t1", "claude-opus-5", "low", film(1, keywords = false))
        val hc = haiku["params"]!!.jsonObject["output_config"]!!.jsonObject
        val oc = opus["params"]!!.jsonObject["output_config"]!!.jsonObject
        assertFalse("effort" in hc)
        assertEquals("\"low\"", oc["effort"].toString())
        assertEquals("\"json_schema\"", hc["format"]!!.jsonObject["type"].toString())
    }

    // ─── Batch lifecycle (FR-270-5): resume after a restart ───────────────────

    @Test
    fun `a batch sent before a restart is read back after it and applied once and priced into the ledger`() = runBlocking {
        enable()
        var created: String? = null
        val results = """{"custom_id":"CID","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"{\"picks\":[{\"id\":\"t3\",\"reason\":\"because\"},{\"id\":\"t1\",\"reason\":\"also\"}]}"}],"stop_reason":"end_turn","usage":{"input_tokens":1000,"output_tokens":2000,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}}"""
        val t = FakeTransport { method, url, body ->
            when {
                method == "POST" && url.endsWith("/v1/messages/batches") -> {
                    created = Json.parseToJsonElement(body!!).jsonObject["requests"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["custom_id"].toString().trim('"') }
                    AnthropicClient.Response(200, """{"id":"msgbatch_1","processing_status":"in_progress"}""")
                }
                url.endsWith("/v1/messages/batches/msgbatch_1") -> AnthropicClient.Response(200, """{"id":"msgbatch_1","processing_status":"ended","results_url":"https://api.anthropic.com/v1/messages/batches/msgbatch_1/results"}""")
                url.endsWith("/results") -> AnthropicClient.Response(200, results.replace("CID", created!!))
                else -> AnthropicClient.Response(500, "")
            }
        }
        // Before the "restart": the batch is sent and recorded.
        assertTrue(jobs(t).submitRerank(listOf(AiJobs.RerankInput("u1", "s1", (1..10).map { "jf$it" }, listOf("jf9")))))
        assertEquals(1, db.aiQueries.pendingBatches().executeAsList().size)

        // After it: a NEW runner over the same database finds the batch and applies it.
        val applied = ArrayList<Pair<String, List<String>>>()
        val after = jobs(t).also { it.rerankSink = AiJobs.RerankSink { u, _, picks -> applied += u to picks.map { p -> p.jellyfinId } } }
        after.pollPending()
        assertEquals(1, applied.size)
        assertEquals(listOf("jf3", "jf1"), applied[0].second.take(2))
        assertEquals(10, applied[0].second.size)   // topped up from 269's order (the shortlist was 10)
        assertTrue(db.aiQueries.pendingBatches().executeAsList().isEmpty())
        // 1k in + 2k out on Opus 5, batch price: 1000 × 2.5 + 2000 × 12.5 micro-dollars.
        assertEquals(27_500L, after.spentThisMonth(AiRequests.RERANK_JOB))

        // Polling again reads nothing twice.
        val callsBefore = t.calls.size
        after.pollPending()
        assertEquals(1, applied.size)
        assertEquals(callsBefore, t.calls.size)
    }

    // ─── Key test (FR-270-1) ──────────────────────────────────────────────────

    @Test
    fun `the key test answers valid or invalid or unreachable`() = runBlocking {
        assertEquals(AnthropicClient.KeyCheck.VALID, AnthropicClient(FakeTransport { _, _, _ -> AnthropicClient.Response(200, "{}") }).testKey("k"))
        assertEquals(AnthropicClient.KeyCheck.INVALID, AnthropicClient(FakeTransport { _, _, _ -> AnthropicClient.Response(401, "{}") }).testKey("k"))
        assertEquals(AnthropicClient.KeyCheck.UNREACHABLE, AnthropicClient(FakeTransport { _, _, _ -> error("no route to host") }).testKey("k"))
    }

    // ─── Off means off (dev review item 8) ────────────────────────────────────

    @Test
    fun `with AI off no request leaves the server and no batch is recorded`() = runBlocking {
        enable(rerank = true, themes = true, on = false)
        val t = FakeTransport { _, _, _ -> error("AI is off: nothing may be sent") }
        val j = jobs(t, (1..10).map { film(it, keywords = false) })
        j.afterBuild(listOf(AiJobs.RerankInput("u1", "s1", (1..10).map { "jf$it" }, emptyList())))
        j.pollPending()
        assertTrue(t.calls.isEmpty())
        assertEquals(0L, db.aiQueries.countBatches().executeAsOne())
    }

    @Test
    fun `themes are sent only for titles without keywords and each once per synopsis`() = runBlocking {
        enable(rerank = false, themes = true)
        val bodies = ArrayList<JsonObject>()
        val t = FakeTransport { method, _, body ->
            if (method == "POST") { bodies += Json.parseToJsonElement(body!!).jsonObject; AnthropicClient.Response(200, """{"id":"b${bodies.size}","processing_status":"in_progress"}""") }
            else AnthropicClient.Response(200, """{"id":"b1","processing_status":"in_progress"}""")
        }
        val lib = listOf(film(1), film(2, keywords = false), film(3, keywords = false))
        assertTrue(jobs(t, lib).submitThemes())
        assertEquals(2, (bodies[0]["requests"] as kotlinx.serialization.json.JsonArray).size)
        // One batch out per job at a time: a second run while the first is pending sends nothing.
        assertFalse(jobs(t, lib).submitThemes())
        assertEquals(1, bodies.size)
    }
}
