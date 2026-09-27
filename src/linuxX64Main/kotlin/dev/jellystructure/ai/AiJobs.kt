package dev.jellystructure.ai

import dev.jellystructure.config.AiJobConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.ops.GateClass
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import platform.posix.localtime_r
import platform.posix.time
import platform.posix.time_tVar
import platform.posix.tm

/**
 * Phase 270 — the two AI jobs, on top of Phase 269: **re-rank** (per viewer, after 269's weekly build and a
 * viewer's first build) and **theme tags** (per title, once, for titles TMDB keywords miss). Both go through
 * the Message Batches API: nobody waits for them, and every token costs half.
 *
 * Rules the runner keeps:
 * - **Off means off** (FR-270-1): with AI off, or no key, no request leaves the server, polling included.
 * - **A limit is a hard stop, checked before spending** (FR-270-6): a batch whose worst case could take the
 *   month past the job's limit is not sent; 269's lists stand and the tab says *limit reached*.
 * - **Nothing trusted blindly** (FR-270-4): answers go through [AiRequests]' validators; a failure keeps 269's.
 * - **A restart loses nothing** (FR-270-5): a sent batch is an `ai_batch` row; `submitted` rows are polled
 *   again at boot, so results are neither lost nor paid for twice.
 * - **269 stays the only author of a list** (dev review item 9): this never scores or filters titles; it
 *   hands 269 an order through [RerankSink].
 */
class AiJobs(
    private val db: JellystructureDb,
    private val configStore: ConfigStore,
    private val library: suspend () -> List<MediaItem>,
    private val client: AnthropicClient = AnthropicClient(),
    private val clock: () -> Long = { nowSec() },
) {
    /** One viewer's shortlist from 269: ids in 269's order, and what they watched, newest first. */
    data class RerankInput(val userId: String, val scope: String, val shortlist: List<String>, val watched: List<String>)

    /** Where an accepted re-rank goes (269's `applyAiOrder`). */
    fun interface RerankSink { fun apply(userId: String, scope: String, picks: List<AiRequests.Pick>) }
    var rerankSink: RerankSink? = null

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class RerankCtx(val u: String, val s: String, val ids: List<String>)
    @Serializable private data class ThemeCtx(val item: String, val hash: String)

    fun start(scope: CoroutineScope) {
        scope.launch(GateClass.BACKGROUND) {
            while (true) {
                runCatching { pollPending() }.onFailure { Logger.warn("AI: polling failed: ${it.message}", "ai") }
                delay(POLL_MS)
            }
        }
    }

    private fun on(job: AiJobConfig): Boolean {
        val ai = configStore.current.ai
        return ai.enabled && ai.apiKey.isNotBlank() && job.enabled
    }

    /** After 269's weekly build (and a viewer's first build): tag what needs tagging, then re-rank. */
    suspend fun afterBuild(inputs: List<RerankInput>) {
        runCatching { submitThemes() }.onFailure { Logger.warn("AI: themes not sent: ${it.message}", "ai") }
        runCatching { submitRerank(inputs) }.onFailure { Logger.warn("AI: re-rank not sent: ${it.message}", "ai") }
    }

    // ─── Submitting ───────────────────────────────────────────────────────────

    suspend fun submitRerank(inputs: List<RerankInput>): Boolean = lock.withLock {
        val cfg = configStore.current.ai
        if (!on(cfg.rerank) || inputs.isEmpty()) return@withLock false
        if (db.aiQueries.pendingBatchForJob(AiRequests.RERANK_JOB).executeAsOneOrNull() != null) return@withLock false
        val byJf = library().mapNotNull { it.jellyfinId?.let { id -> id to it } }.toMap()
        val themes = themes()
        val requests = ArrayList<Pair<JsonObject, String>>()
        for (input in inputs) {
            val shortlist = input.shortlist.mapNotNull { byJf[it] }.take(AiRequests.RERANK_SHORTLIST)
            if (shortlist.size < 2) continue
            val customId = opaqueId("r", input.userId + "|" + input.scope)
            val ctx = json.encodeToString(RerankCtx.serializer(), RerankCtx(input.userId, input.scope, shortlist.mapNotNull { it.jellyfinId }))
            requests += AiRequests.rerankRequest(customId, cfg.rerank.model, cfg.rerank.effort, shortlist, input.watched.mapNotNull { byJf[it] }, themes) to ctx
        }
        send(AiRequests.RERANK_JOB, cfg.rerank, requests, AiRequests.RERANK_MAX_TOKENS, "viewer")
    }

    suspend fun submitThemes(): Boolean = lock.withLock {
        val cfg = configStore.current.ai
        if (!on(cfg.themes)) return@withLock false
        if (db.aiQueries.pendingBatchForJob(AiRequests.THEMES_JOB).executeAsOneOrNull() != null) return@withLock false
        val done = db.aiQueries.allThemes().executeAsList().associate { it.item_id to it.synopsis_hash }
        val requests = library().filter(AiRequests::wantsThemes).mapNotNull { item ->
            val key = item.jellyfinId ?: item.id
            val hash = AiRequests.synopsisHash(item)
            if (done[key] == hash) return@mapNotNull null
            val customId = opaqueId("t", key)
            AiRequests.themesRequest(customId, cfg.themes.model, cfg.themes.effort, item) to
                json.encodeToString(ThemeCtx.serializer(), ThemeCtx(key, hash))
        }.take(MAX_THEME_REQUESTS)
        send(AiRequests.THEMES_JOB, cfg.themes, requests, AiRequests.THEMES_MAX_TOKENS, "title")
    }

    /** FR-270-6 — the limit check, then one batch; `true` when it was sent. */
    private suspend fun send(job: String, cfg: AiJobConfig, requests: List<Pair<JsonObject, String>>, maxTokens: Int, noun: String): Boolean {
        if (requests.isEmpty()) return false
        val body = AiRequests.requestsJson(requests.map { it.first })
        val worst = AiPricing.worstCaseMicroUsd(cfg.model, body.length.toLong(), requests.size, maxTokens)
        val spent = spentThisMonth(job)
        if (AiPricing.wouldPassLimit(spent, worst, cfg.monthlyLimitUsd)) {
            run(job, "skipped: limit reached (${AiPricing.usd(spent)} spent, this run could cost up to ${AiPricing.usd(worst)})")
            return false
        }
        val key = configStore.current.ai.apiKey
        val batch = runCatching { client.createBatch(key, body) }.getOrElse {
            run(job, "couldn't send: ${it.message?.take(120)}")
            return false
        }
        val now = clock()
        db.transaction {
            db.aiQueries.insertBatch(batch.id, job, cfg.model, now, STATUS_SUBMITTED, requests.size.toLong())
            for ((req, ctx) in requests) db.aiQueries.insertBatchItem(batch.id, (req["custom_id"] as kotlinx.serialization.json.JsonPrimitive).content, ctx)
        }
        run(job, "sent ${hhmm(now)} · ${requests.size} $noun${if (requests.size == 1) "" else "s"} · waiting for Anthropic")
        Logger.info("AI: $job batch ${batch.id} sent (${requests.size} requests, worst case ${AiPricing.usd(worst)})", "ai")
        return true
    }

    // ─── Reading back ─────────────────────────────────────────────────────────

    /** Every batch still out: ended ones are read and applied. Off = nothing is polled either. */
    suspend fun pollPending() {
        val ai = configStore.current.ai
        if (!ai.enabled || ai.apiKey.isBlank()) return
        for (row in db.aiQueries.pendingBatches().executeAsList()) {
            val batch = runCatching { client.getBatch(ai.apiKey, row.id) }.getOrElse {
                if ((it as? AnthropicClient.ApiException)?.status == 404) {
                    db.aiQueries.setBatchStatus(STATUS_LOST, row.id)
                    run(row.job, "batch lost at Anthropic — standard list kept")
                }
                continue
            }
            if (batch.processingStatus != "ended") continue
            val url = batch.resultsUrl ?: "${AnthropicClient.BASE}/v1/messages/batches/${row.id}/results"
            val lines = runCatching { client.results(ai.apiKey, url) }.getOrElse { continue }
            applyResults(row.id, row.job, row.model, lines)
        }
    }

    private suspend fun applyResults(batchId: String, job: String, model: String, lines: List<JsonObject>) {
        val contexts = db.aiQueries.batchItems(batchId).executeAsList().associate { it.custom_id to it.context }
        var requests = 0L; var input = 0L; var output = 0L; var cacheWrite = 0L; var cacheRead = 0L
        var accepted = 0; var rejected = 0; var expired = 0
        for (line in lines) {
            val r = AiRequests.parseResult(line) ?: continue
            val ctx = contexts[r.customId] ?: continue
            r.usage?.let { u -> requests++; input += u.input; output += u.output; cacheWrite += u.cacheWrite; cacheRead += u.cacheRead }
            if (r.type == "expired" || r.type == "canceled") expired++
            when (job) {
                AiRequests.RERANK_JOB -> {
                    val c = runCatching { json.decodeFromString(RerankCtx.serializer(), ctx) }.getOrNull() ?: continue
                    val picks = AiRequests.validateRerank(r, c.ids)
                    if (picks == null) { rejected++; continue }
                    rerankSink?.apply(c.u, c.s, picks)
                    accepted++
                }
                AiRequests.THEMES_JOB -> {
                    val c = runCatching { json.decodeFromString(ThemeCtx.serializer(), ctx) }.getOrNull() ?: continue
                    val themes = AiRequests.validateThemes(r)
                    if (themes == null) {
                        rejected++
                        // A title the model could not tag is not retried until its synopsis changes: an answer
                        // that failed once would otherwise be paid for again on every run.
                        if (r.type == "succeeded") db.aiQueries.putTheme(c.item, "", c.hash, clock())
                        continue
                    }
                    db.aiQueries.putTheme(c.item, themes.joinToString("|"), c.hash, clock())
                    accepted++
                }
            }
        }
        val cost = AiPricing.costMicroUsd(model, AiPricing.Usage(input, output, cacheWrite, cacheRead))
        val now = clock()
        db.transaction {
            if (requests > 0) addUsage(job, monthKey(now), model, requests, input, output, cacheWrite, cacheRead, cost)
            db.aiQueries.setBatchStatus(STATUS_ENDED, batchId)
            db.aiQueries.deleteBatchItems(batchId)
        }
        val noun = if (job == AiRequests.RERANK_JOB) "viewer" else "title"
        val line = when {
            accepted == 0 && expired > 0 -> "batch expired — standard list kept"
            accepted == 0 -> "no usable answer — standard list kept"
            else -> "ran ${hhmm(now)} · $accepted $noun${if (accepted == 1) "" else "s"} · ${AiPricing.usd(cost)}" +
                (if (rejected + expired > 0) " · ${rejected + expired} kept the standard list" else "")
        }
        run(job, line)
        Logger.info("AI: $job batch $batchId read — $line", "ai")
    }

    private fun addUsage(job: String, month: String, model: String, requests: Long, input: Long, output: Long, cacheWrite: Long, cacheRead: Long, cost: Long) {
        val prev = db.aiQueries.usageRow(job, month, model).executeAsOneOrNull()
        db.aiQueries.putUsage(
            job, month, model,
            (prev?.requests ?: 0) + requests,
            (prev?.input_tokens ?: 0) + input,
            (prev?.output_tokens ?: 0) + output,
            (prev?.cache_write_tokens ?: 0) + cacheWrite,
            (prev?.cache_read_tokens ?: 0) + cacheRead,
            (prev?.cost_micro_usd ?: 0) + cost,
        )
    }

    private fun run(job: String, line: String) = db.aiQueries.putRun(job, clock(), line)

    fun spentThisMonth(job: String): Long = db.aiQueries.spentInMonth(job, monthKey(clock())).executeAsOne()

    /** Stored theme tags per title (jellyfin id), for 269's similarity. Failed titles have none. */
    fun themes(): Map<String, List<String>> =
        db.aiQueries.allThemes().executeAsList().filter { it.themes.isNotBlank() }.associate { it.item_id to it.themes.split('|') }

    // ─── The tab's status (dev review item 1) ─────────────────────────────────

    @Serializable
    data class ModelDto(val id: String, val label: String, val inputPerMTok: Double, val outputPerMTok: Double, val effort: Boolean)

    @Serializable
    data class JobStatus(
        val spentThisMonthMicroUsd: Long,
        val lastRun: String? = null,
        val lastRunAt: Long? = null,
        val pending: Boolean = false,
        /** A month at the current settings, per model id (the tab shows the selected model's). */
        val estimateMicroUsd: Map<String, Long> = emptyMap(),
        val estimateBasis: String = "",
    )

    /** No field here has a default: the server's `Json` leaves out a field equal to its default, and the tab
     *  then drew no model list and no prices date (found on production the day it shipped). */
    @Serializable
    data class Status(
        val keyHint: String?,
        val pricesAsOf: String,
        val models: List<ModelDto>,
        val rerank: JobStatus,
        val themes: JobStatus,
    )

    suspend fun status(viewers: Int): Status {
        val key = configStore.current.ai.apiKey
        fun job(job: String, estimate: Map<String, Long>, basis: String): JobStatus {
            val run = db.aiQueries.runFor(job).executeAsOneOrNull()
            return JobStatus(
                spentThisMonthMicroUsd = spentThisMonth(job),
                lastRun = run?.line, lastRunAt = run?.at,
                pending = db.aiQueries.pendingBatchForJob(job).executeAsOneOrNull() != null,
                estimateMicroUsd = estimate, estimateBasis = basis,
            )
        }
        val untagged = run {
            val done = db.aiQueries.allThemes().executeAsList().associate { it.item_id to it.synopsis_hash }
            library().count { AiRequests.wantsThemes(it) && done[it.jellyfinId ?: it.id] != AiRequests.synopsisHash(it) }
        }
        val (rerankEst, rerankBasis) = estimate(AiRequests.RERANK_JOB, RERANK_ASSUMED, viewers * RUNS_PER_MONTH)
        val (themesEst, themesBasis) = estimate(AiRequests.THEMES_JOB, THEMES_ASSUMED, untagged.toDouble())
        return Status(
            keyHint = key.takeIf { it.length >= 8 }?.let { "…" + it.takeLast(4) },
            pricesAsOf = AiPricing.AS_OF,
            models = AiPricing.MODELS.map { ModelDto(it.id, it.label, it.inputPerMTok, it.outputPerMTok, it.effort) },
            rerank = job(AiRequests.RERANK_JOB, rerankEst, "$viewers viewer${if (viewers == 1) "" else "s"} × about ${RUNS_PER_MONTH.toInt()} runs a month · $rerankBasis"),
            themes = job(AiRequests.THEMES_JOB, themesEst, "once, for $untagged untagged title${if (untagged == 1) "" else "s"} · $themesBasis"),
        )
    }

    /** FR-270-7 — cost per request from this job's own ledger once it has run on a model, else the documented
     *  starting assumption; times [requests]. Returns the per-model estimate and what it was based on. */
    private fun estimate(job: String, assumed: AiPricing.Usage, requests: Double): Pair<Map<String, Long>, String> {
        val rows = db.aiQueries.usageForJob(job).executeAsList()
        var measured = false
        val perModel = AiPricing.MODELS.associate { m ->
            val own = rows.filter { it.model == m.id && it.requests > 0 }
            val perRequest = if (own.isNotEmpty()) { measured = true; own.sumOf { it.cost_micro_usd }.toDouble() / own.sumOf { it.requests } }
                else AiPricing.costMicroUsd(m.id, assumed).toDouble()
            m.id to (perRequest * requests).toLong()
        }
        return perModel to (if (measured) "from this job's own runs" else "from a starting assumption until it has run")
    }

    companion object {
        /** The process's one instance, for the routes. Set by Main. */
        var current: AiJobs? = null
        /** How many viewers a re-rank covers, for the estimate. Set by Main (269's recently seen viewers). */
        var viewerCount: () -> Int = { 0 }
        const val STATUS_SUBMITTED = "submitted"
        const val STATUS_ENDED = "ended"
        const val STATUS_LOST = "lost"
        private const val POLL_MS = 5L * 60 * 1000
        private const val MAX_THEME_REQUESTS = 2_000
        /** 269's weekly build, about 4.3 times a month. */
        private const val RUNS_PER_MONTH = 4.33
        /** FR-270-7's starting assumptions: a re-rank ~12k in / ~5k out; a title's themes ~0.5k in / ~0.15k out. */
        private val RERANK_ASSUMED = AiPricing.Usage(input = 12_000, output = 5_000)
        private val THEMES_ASSUMED = AiPricing.Usage(input = 500, output = 150)

        @OptIn(ExperimentalForeignApi::class)
        fun nowSec(): Long = time(null)

        /** An opaque request key (FR-270-3): never a name or an id someone could read back. */
        fun opaqueId(prefix: String, seed: String): String {
            var h = 0xcbf29ce484222325uL
            for (b in seed.encodeToByteArray()) { h = h xor (b.toULong() and 0xffuL); h *= 0x100000001b3uL }
            return prefix + h.toString(16)
        }

        /** The calendar month in the server's time zone (FR-270-6), `YYYY-MM`. */
        @OptIn(ExperimentalForeignApi::class)
        fun monthKey(epochSec: Long): String = memScoped {
            val t = alloc<time_tVar>().apply { value = epochSec.convert() }
            val out = alloc<tm>()
            localtime_r(t.ptr, out.ptr)
            "${out.tm_year + 1900}-${(out.tm_mon + 1).toString().padStart(2, '0')}"
        }

        @OptIn(ExperimentalForeignApi::class)
        fun hhmm(epochSec: Long): String = memScoped {
            val t = alloc<time_tVar>().apply { value = epochSec.convert() }
            val out = alloc<tm>()
            localtime_r(t.ptr, out.ptr)
            "${out.tm_hour.toString().padStart(2, '0')}:${out.tm_min.toString().padStart(2, '0')}"
        }
    }
}
