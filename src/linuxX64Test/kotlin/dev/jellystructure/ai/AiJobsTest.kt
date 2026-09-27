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

    /** Phase 272 — queue then send, as `afterBuild` does. */
    private suspend fun submit(j: AiJobs, vararg inputs: AiJobs.RerankInput) {
        j.enqueueRerank(inputs.toList(), "weekly build", AiJobs.BY_SYSTEM)
        j.sendQueued()
    }

    private fun viewer(u: String, n: Int = 10) = AiJobs.RerankInput(u, "s1", (1..n).map { "jf$it" }, listOf("jf1"), label = "Viewer $u")

    @Test
    fun `a request that would pass the limit is not sent and stays waiting with the reason`() = runBlocking {
        enable(limit = 0.01)
        val t = FakeTransport { _, _, _ -> error("nothing may be sent") }
        val j = jobs(t)
        submit(j, viewer("u1"))
        assertTrue(t.calls.isEmpty())
        assertEquals(0L, db.aiQueries.countBatches().executeAsOne())
        val view = j.jobsView().jobs.first { it.job == AiRequests.RERANK_JOB }
        assertEquals(1, view.waiting.size)
        assertTrue(view.heldBack!!.startsWith("1 waiting — this month's limit"), view.heldBack)
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
        submit(jobs(t), AiJobs.RerankInput("u1", "s1", (1..10).map { "jf$it" }, listOf("jf9"), label = "Viewer one"))
        assertEquals(1, db.aiQueries.pendingBatches().executeAsList().size)
        // Phase 272 (FR-272-13) — the conversation is kept from the moment it is sent: the prompt, no answer yet.
        val waitingDetail = jobs(t).batchDetail("msgbatch_1")!!.requests.single()
        assertEquals("Viewer one", waitingDetail.label)
        assertTrue(waitingDetail.system.startsWith("You help a household media server"))
        assertTrue(waitingDetail.sent.contains("Shortlist:\nt1 | Film 1 (2001)"), waitingDetail.sent)
        assertNull(waitingDetail.answer)

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
        // FR-272-13/14 — the answer, the verdict in words and the picks with their titles.
        val read = after.batchDetail("msgbatch_1")!!
        assertEquals("ran · 1 viewer · $0.03", read.batch.outcome)
        val tr = read.requests.single()
        assertEquals("used · 2 picks, 8 topped up", tr.verdict)
        assertTrue(tr.answer!!.contains("\"t3\""))
        assertEquals("1. Film 3 (2003) — because", tr.readable!!.lines().first())
        assertEquals(27_500L, tr.costMicroUsd)

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

    // ─── The tab's status, encoded as the server encodes ──────────────────────

    @Test
    fun `the status carries the model list and the prices date on the wire`() = runBlocking {
        val status = jobs(FakeTransport { _, _, _ -> error("no call") }).status(viewers = 4)
        val wire = Json { ignoreUnknownKeys = true }.encodeToJsonElement(AiJobs.Status.serializer(), status).jsonObject
        assertEquals(3, (wire["models"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals("\"${AiPricing.AS_OF}\"", wire["pricesAsOf"].toString())
        assertEquals(3, wire["rerank"]!!.jsonObject["estimateMicroUsd"]!!.jsonObject.size)
    }

    // ─── Off means off (dev review item 8) ────────────────────────────────────

    @Test
    fun `with AI off no request leaves the server and no batch is recorded`() = runBlocking {
        enable(rerank = true, themes = true, on = false)
        val t = FakeTransport { _, _, _ -> error("AI is off: nothing may be sent") }
        val j = jobs(t, (1..10).map { film(it, keywords = false) })
        j.afterBuild(listOf(AiJobs.RerankInput("u1", "s1", (1..10).map { "jf$it" }, emptyList())), "weekly build", AiJobs.BY_SYSTEM)
        j.pollPending()
        assertTrue(t.calls.isEmpty())
        assertEquals(0L, db.aiQueries.countBatches().executeAsOne())
    }

    @Test
    fun `a title whose keywords were never fetched is not a themes candidate`() {
        assertFalse(AiRequests.wantsThemes(film(1).copy(keywords = null)))
        assertTrue(AiRequests.wantsThemes(film(1, keywords = false)))
        assertFalse(AiRequests.wantsThemes(film(1)))
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
        val j = jobs(t, lib)
        assertEquals(2, j.enqueueThemes())
        j.sendQueued()
        assertEquals(2, (bodies[0]["requests"] as kotlinx.serialization.json.JsonArray).size)
        // Both titles are out: a second pass queues nothing and sends nothing.
        assertEquals(0, jobs(t, lib).enqueueThemes())
        jobs(t, lib).sendQueued()
        assertEquals(1, bodies.size)
    }

    // ─── Phase 272 — the queue ────────────────────────────────────────────────

    /** A stand-in Anthropic that numbers batches b1, b2…, keeps each one's custom ids, answers every re-rank
     *  with picks t2, t1, and reports a batch ended once [ended] holds its id. */
    private class FakeAnthropic {
        val created = ArrayList<List<String>>()
        val bodies = ArrayList<String>()
        val ended = HashSet<String>()
        val cancelled = ArrayList<String>()
        var canceledIds = emptySet<String>()
        val transport = FakeTransport { method, url, body ->
            when {
                method == "POST" && url.endsWith("/cancel") -> {
                    cancelled += url.substringAfter("/batches/").substringBefore("/cancel")
                    AnthropicClient.Response(200, """{"id":"${cancelled.last()}","processing_status":"canceling"}""")
                }
                method == "POST" -> {
                    bodies += body!!
                    val reqs = Json.parseToJsonElement(body).jsonObject["requests"] as kotlinx.serialization.json.JsonArray
                    created += reqs.map { it.jsonObject["custom_id"].toString().trim('"') }
                    AnthropicClient.Response(200, """{"id":"b${created.size}","processing_status":"in_progress"}""")
                }
                url.endsWith("/results") -> {
                    val id = url.substringAfter("/batches/").substringBefore("/results")
                    val n = id.removePrefix("b").toInt()
                    AnthropicClient.Response(200, created[n - 1].joinToString("\n") { cid ->
                        if (cid in canceledIds) """{"custom_id":"$cid","result":{"type":"canceled"}}"""
                        else """{"custom_id":"$cid","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"{\"picks\":[{\"id\":\"t2\",\"reason\":\"r\"},{\"id\":\"t1\",\"reason\":\"r\"}]}"}],"stop_reason":"end_turn","usage":{"input_tokens":100,"output_tokens":100}}}}"""
                    })
                }
                else -> {
                    val id = url.substringAfterLast("/")
                    val status = if (id in ended) "ended" else "in_progress"
                    AnthropicClient.Response(200, """{"id":"$id","processing_status":"$status","request_counts":{"processing":1,"succeeded":0,"errored":0,"canceled":0,"expired":0},"results_url":"https://api.anthropic.com/v1/messages/batches/$id/results"}""")
                }
            }
        }
    }

    @Test
    fun `a request made while a batch is out waits and goes the moment that batch is read`() = runBlocking {
        enable()
        val a = FakeAnthropic()
        val applied = ArrayList<String>()
        val j = jobs(a.transport).also { it.rerankSink = AiJobs.RerankSink { u, _, _ -> applied += u } }
        submit(j, viewer("u1"))
        assertEquals(1, a.created.size)
        assertEquals("out", j.pendingFor("u1")!!.state)

        // The admin's Rebuild now for a second viewer while b1 is out: queued, not dropped (Today, 4).
        j.enqueueRerank(listOf(viewer("u2")), "Rebuild now", "admin")
        j.sendQueued()
        assertEquals(1, a.created.size)
        val waiting = j.jobsView().jobs.first { it.job == AiRequests.RERANK_JOB }.waiting.single()
        assertEquals("Viewer u2", waiting.label)
        assertEquals("Rebuild now", waiting.reason)
        assertEquals("admin", waiting.by)
        assertEquals("waiting", j.pendingFor("u2")!!.state)

        // Still out: the poll keeps Anthropic's counts and sends nothing.
        j.pollPending()
        assertEquals(1, a.created.size)
        assertEquals(1L, j.jobsView().jobs.first { it.job == AiRequests.RERANK_JOB }.out!!.counts["processing"])

        // b1 ends: it is read, and u2 goes in the same poll.
        a.ended += "b1"
        j.pollPending()
        assertEquals(listOf("u1"), applied)
        assertEquals(2, a.created.size)
        assertEquals("out", j.pendingFor("u2")!!.state)
        assertNull(j.pendingFor("u1"))
        a.ended += "b2"
        j.pollPending()
        assertEquals(listOf("u1", "u2"), applied)
    }

    @Test
    fun `queuing a viewer who is already waiting replaces the request with the newer shortlist`() = runBlocking {
        enable(limit = 0.0)   // nothing is sent: the queue is only looked at
        val j = jobs(FakeTransport { _, _, _ -> error("nothing may be sent") })
        j.enqueueRerank(listOf(viewer("u1", n = 5)), "weekly build", AiJobs.BY_SYSTEM)
        j.enqueueRerank(listOf(viewer("u1", n = 8)), "Rebuild now", "admin")
        val rows = db.aiQueries.waitingFor(AiRequests.RERANK_JOB).executeAsList()
        assertEquals(1, rows.size)
        assertEquals("Rebuild now", rows[0].reason)
        assertTrue(rows[0].payload.contains("\"jf8\""))
    }

    @Test
    fun `the monthly limit takes the oldest requests that fit and leaves the rest waiting`() = runBlocking {
        // Opus 5's worst case for one re-rank is about $0.10 (8 000 output tokens at the batch price).
        enable(limit = 0.15)
        val a = FakeAnthropic()
        val j = jobs(a.transport)
        j.enqueueRerank(listOf(viewer("u1"), viewer("u2"), viewer("u3")), "weekly build", AiJobs.BY_SYSTEM)
        j.sendQueued()
        assertEquals(listOf(1), a.created.map { it.size })
        val view = j.jobsView().jobs.first { it.job == AiRequests.RERANK_JOB }
        assertEquals(listOf("Viewer u2", "Viewer u3"), view.waiting.map { it.label })
        assertTrue(view.heldBack!!.startsWith("2 waiting — this month's limit:"), view.heldBack)
    }

    @Test
    fun `raising the limit sends what was held back without another click`() = runBlocking {
        enable(limit = 0.01)
        val a = FakeAnthropic()
        val j = jobs(a.transport)
        j.enqueueRerank(listOf(viewer("u1"), viewer("u2"), viewer("u3")), "weekly build", AiJobs.BY_SYSTEM)
        j.sendQueued()
        assertTrue(a.created.isEmpty())
        enable(limit = 5.0)
        j.sendQueued()   // the runner's minute tick does this
        assertEquals(listOf(3), a.created.map { it.size })
        assertNull(j.jobsView().jobs.first { it.job == AiRequests.RERANK_JOB }.heldBack)
    }

    @Test
    fun `only the five newest batches keep their record and conversations`() = runBlocking {
        enable()
        val a = FakeAnthropic()
        val j = jobs(a.transport)
        for (n in 1..6) {
            submit(j, viewer("u$n"))
            a.ended += "b$n"
            j.pollPending()
        }
        val history = j.jobsView().history
        assertEquals(AiJobs.HISTORY, history.size)
        assertFalse(history.any { it.id == "b1" })
        assertNull(j.batchDetail("b1"))
        assertEquals(0, db.aiQueries.transcriptsFor("b1").executeAsList().size)
        assertEquals(1, j.batchDetail("b6")!!.requests.size)
    }

    @Test
    fun `a cancel is sent to Anthropic and what was produced is still applied and paid for`() = runBlocking {
        enable()
        val a = FakeAnthropic()
        val applied = ArrayList<String>()
        val j = jobs(a.transport).also { it.rerankSink = AiJobs.RerankSink { u, _, _ -> applied += u } }
        submit(j, viewer("u1"), viewer("u2"))
        assertEquals(AiJobs.Cancel.SENT, j.cancel("b1"))
        assertEquals(listOf("b1"), a.cancelled)
        assertTrue(j.jobsView().jobs.first { it.job == AiRequests.RERANK_JOB }.out!!.cancelling)

        a.canceledIds = setOf(a.created[0][1])
        a.ended += "b1"
        j.pollPending()
        assertEquals(1, applied.size)
        assertEquals("ran · 1 viewer · $0.00 · 1 kept the standard list", j.batchDetail("b1")!!.batch.outcome)
        assertTrue(j.spentThisMonth(AiRequests.RERANK_JOB) > 0)
        assertTrue(j.batchDetail("b1")!!.requests.any { it.verdict == "kept the standard list — cancelled" })
        assertEquals(AiJobs.Cancel.ENDED, j.cancel("b1"))
        assertEquals(AiJobs.Cancel.NOT_FOUND, j.cancel("nope"))
    }

    @Test
    fun `what is sent for a viewer is exactly what 270 sends`() = runBlocking {
        enable()
        val a = FakeAnthropic()
        val lib = (1..10).map { film(it) }
        submit(jobs(a.transport, lib), viewer("u1"))
        val sent = (Json.parseToJsonElement(a.bodies.single()).jsonObject["requests"] as kotlinx.serialization.json.JsonArray).single()
        val expected = AiRequests.rerankRequest(AiJobs.opaqueId("r", "u1|s1"), "claude-opus-5", "medium", lib, listOf(lib[0]))
        assertEquals(expected, sent)
        assertFalse(sent.toString().contains("Viewer u1"))   // the admin's label never leaves the house
    }

    @Test
    fun `with the re-rank job off a viewer has nothing pending and nothing is queued`() = runBlocking {
        enable(rerank = false)
        val j = jobs(FakeTransport { _, _, _ -> error("nothing may be sent") })
        assertEquals(0, j.enqueueRerank(listOf(viewer("u1")), "Rebuild now", "admin"))
        assertNull(j.pendingFor("u1"))
    }

    @Test
    fun `a verdict says why an answer was not used`() {
        assertEquals("an id not on the shortlist (t999)", AiRequests.judgeRerank(answer(listOf("t1", "t999")), shortlist).why)
        assertEquals("a title picked twice (t1)", AiRequests.judgeRerank(answer(listOf("t1", "t2", "t1")), shortlist).why)
        assertEquals("stopped at the output limit", AiRequests.judgeRerank(answer(listOf("t1"), stop = "max_tokens"), shortlist).why)
        assertEquals("refused", AiRequests.judgeRerank(answer(listOf("t1"), stop = "refusal"), shortlist).why)
        assertEquals("not valid JSON", AiRequests.judgeRerank(AiRequests.Result("c", "succeeded", "end_turn", "{not json", null), shortlist).why)
        assertEquals("expired", AiRequests.judgeRerank(answer(listOf("t1"), type = "expired"), shortlist).why)
        assertEquals("more than 50 picks (51)", AiRequests.judgeRerank(answer((1..51).map { "t$it" }), shortlist).why)
        val errored = AiRequests.parseResult(Json.parseToJsonElement(
            """{"custom_id":"c","result":{"type":"errored","error":{"type":"error","error":{"type":"invalid_request_error","message":"bad model"}}}}""",
        ).jsonObject)!!
        assertEquals("errored — invalid_request_error: bad model", AiRequests.judgeRerank(errored, shortlist).why)
        assertEquals("used · 2 picks, 48 topped up", AiRequests.judgeRerank(answer(listOf("t1", "t2")), shortlist).why)
    }

    // ─── Phase 272 (FR-272-7) — a run started by hand builds ──────────────────

    @Test
    fun `a run started by hand is always due and a scheduled one keeps the cadence`() {
        val week = dev.jellystructure.config.RecommendationsStep.CADENCES.getValue("weekly")
        val now = 1_790_000_000L
        val builtYesterday = now - 86_400
        assertTrue(dev.jellystructure.config.RecommendationsStep.notDue(builtYesterday, now, week, byHand = false))
        assertFalse(dev.jellystructure.config.RecommendationsStep.notDue(builtYesterday, now, week, byHand = true))
        assertFalse(dev.jellystructure.config.RecommendationsStep.notDue(null, now, week, byHand = false))
        assertFalse(dev.jellystructure.config.RecommendationsStep.notDue(now - week, now, week, byHand = false))
    }
}
