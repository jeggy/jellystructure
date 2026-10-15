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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * - **A limit is a hard stop, checked before spending** (FR-270-6): nothing is sent whose worst case could take
 *   the month past the job's limit; 269's lists stand.
 * - **Nothing trusted blindly** (FR-270-4): answers go through [AiRequests]' validators; a failure keeps 269's.
 * - **A restart loses nothing** (FR-270-5): a sent batch is an `ai_batch` row; `submitted` rows are polled
 *   again at boot, so results are neither lost nor paid for twice.
 * - **269 stays the only author of a list** (dev review item 9): this never scores or filters titles; it
 *   hands 269 an order through [RerankSink].
 *
 * Phase 272 — **a queue, not a fire-and-forget**: every request is an `ai_queue` row first (newest wins per
 * viewer or title), at most one batch per job is out, and what waits goes the moment it can — at once when
 * queued, and again as soon as the batch out is read. The monthly limit takes the oldest requests that fit and
 * leaves the rest waiting. The last five batches keep their conversations (`ai_transcript`).
 */
class AiJobs(
    private val db: JellystructureDb,
    private val configStore: ConfigStore,
    private val library: suspend () -> List<MediaItem>,
    private val client: AnthropicClient = AnthropicClient(),
    private val clock: () -> Long = { nowSec() },
) {
    /** One viewer's shortlist from 269: ids in 269's order, and what they watched, newest first. [label] is the
     *  viewer's name, for the admin only (Phase 272) — it is never part of a request. */
    @Serializable
    data class RerankInput(val userId: String, val scope: String, val shortlist: List<String>, val watched: List<String>, val label: String = "")

    /** Where an accepted re-rank goes (269's `applyAiOrder`). */
    fun interface RerankSink { fun apply(userId: String, scope: String, picks: List<AiRequests.Pick>) }
    var rerankSink: RerankSink? = null

    /** Phase 274 — where the suggestion groups go: accepted, or the reason the build keeps its genre groups. The build
     *  they belong to is [builtAt]; an answer for an older build is dropped by the sink. */
    interface ClustersSink {
        suspend fun apply(builtAt: Long, clusters: List<AiRequests.Cluster>, candidates: List<Int>)
        suspend fun failed(builtAt: Long, why: String)
    }
    var clustersSink: ClustersSink? = null

    /**
     * Phase 321 — the fourth job's half that is not the rail: building a request from a ticket, applying its answer, and
     * the three things a person waiting on it is owed (a forward when the limit holds it back, when its batch is lost,
     * when AI goes away). The rail stays ignorant of tickets: it hands over a subject and a result and keeps the ledger.
     */
    interface SupportLane {
        /** FR-321-8 — called before each pass over what waits, so a viewer's count within one batch starts clean. */
        fun beginPass()
        /** One queued ticket as a request, or null when there is nothing to ask (gone, taken by a person, flagged, over a limit — already forwarded). */
        fun build(subject: String, customId: String, cfg: AiJobConfig): BuiltSupport?
        /** The request left in [batchId]: the transcript row (FR-321-10). */
        fun sent(ctx: String, batchId: String, customId: String, model: String, sent: String)
        /** One result read: apply it (or discard it) and say what became of it. [cost] is what the ledger takes. */
        suspend fun answered(ctx: String, batchId: String, r: AiRequests.Result, model: String, cost: Long): SupportVerdict
        /** FR-321-8 — the month's limit holds this ticket back and a person is waiting: forward it, say why. */
        fun heldByLimit(subject: String, why: String)
        /** The batch is lost at Anthropic: nobody will answer these, so forward them. */
        fun lost(ctx: String)
        /** FR-321-3 — tickets whose request has just been read, so `aiComposing` has turned off: push. */
        fun settled(tickets: List<Long>)
        /** FR-321-9 — AI, the key, the job or phase 320's lane went off: forward everything `AI Agent` holds. */
        fun reconcile(): List<Long>
        fun ticketOf(ctx: String): Long?
    }
    class BuiltSupport(val request: JsonObject, val ctx: String)
    class SupportVerdict(val verdict: String, val readable: String?, val used: Boolean)
    var supportLane: SupportLane? = null

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val kick = Channel<Unit>(Channel.CONFLATED)
    /** FR-272-4 — per job, why what is waiting was not sent (the monthly limit). Guarded by [lock]. */
    private val heldBack = HashMap<String, String>()
    /** After a send failed, per job: not before this instant (a dead network is not hammered every minute). */
    private val retryAt = HashMap<String, Long>()

    @Serializable private data class RerankCtx(val u: String, val s: String, val ids: List<String>)
    @Serializable private data class ThemeCtx(val item: String, val hash: String)
    @Serializable private data class ClustersCtx(val builtAt: Long, val ids: List<Int>, val sources: List<String>)

    fun start(scope: CoroutineScope) {
        scope.launch(GateClass.BACKGROUND) {
            while (true) {
                runCatching { reconcileSupport() }.onFailure { Logger.warn("AI: support reconcile failed: ${it.message}", "ai") }
                runCatching { pollPending() }.onFailure { Logger.warn("AI: polling failed: ${it.message}", "ai") }
                runCatching { sendQueued() }.onFailure { Logger.warn("AI: sending failed: ${it.message}", "ai") }
                // FR-272-5 — every minute, or at once when something is queued or cancelled.
                withTimeoutOrNull(POLL_MS) { kick.receive() }
            }
        }
    }

    private fun nudge() { kick.trySend(Unit) }

    /** A saved config may have switched something off: look now, rather than at the next minute. */
    fun configChanged() = nudge()

    // ─── Phase 321 — the support job ──────────────────────────────────────────

    /** FR-321-3 — one row per ticket, newest wins. Not suspend: a viewer's POST calls it, and a row written beside the
     *  runner's own read is safe — an older id is simply gone when the batch it went in deletes by id. */
    fun enqueueSupport(ticket: Long, reason: String) {
        if (!jobOn(AiRequests.SUPPORT_JOB)) return
        db.aiQueries.enqueue(AiRequests.SUPPORT_JOB, ticket.toString(), "Ticket #$ticket", reason, BY_SYSTEM, ticket.toString(), clock())
        nudge()
    }

    /** FR-321-3 — queued for `AI Agent`, or in the batch that is out. The server state behind a thread's `aiComposing`. */
    fun supportComposing(ticket: Long): Boolean {
        if (!jobOn(AiRequests.SUPPORT_JOB)) return false
        if (db.aiQueries.queuedForSubjectPrefix(AiRequests.SUPPORT_JOB, ticket.toString()).executeAsOneOrNull() != null) return true
        val out = db.aiQueries.pendingBatchForJob(AiRequests.SUPPORT_JOB).executeAsOneOrNull() ?: return false
        val lane = supportLane ?: return false
        return db.aiQueries.batchItems(out.id).executeAsList().any { lane.ticketOf(it.context) == ticket }
    }

    /** FR-320-17 — a ticket was deleted: its waiting request goes, and what the last five batches kept of its thread is blanked. */
    fun supportTicketDeleted(ticket: Long) {
        db.aiQueries.waitingFor(AiRequests.SUPPORT_JOB).executeAsList().filter { it.subject == ticket.toString() }
            .forEach { db.aiQueries.deleteQueued(it.id) }
        db.aiQueries.scrubTranscripts(SCRUBBED, opaqueId("s", "support|$ticket"))
    }

    /** FR-321-9 — whatever `AI Agent` holds goes to a person the moment the job cannot run, and what waited is dropped. */
    suspend fun reconcileSupport() {
        val lane = supportLane ?: return
        if (jobOn(AiRequests.SUPPORT_JOB)) return
        val moved = lane.reconcile()
        db.aiQueries.waitingFor(AiRequests.SUPPORT_JOB).executeAsList().forEach { db.aiQueries.deleteQueued(it.id) }
        if (moved.isNotEmpty()) {
            run(AiRequests.SUPPORT_JOB, "switched off — ${moved.size} ticket${if (moved.size == 1) "" else "s"} forwarded to an admin")
            Logger.info("AI: support switched off — ${moved.size} ticket(s) forwarded", "ai")
        }
    }

    private fun on(job: AiJobConfig): Boolean {
        val ai = configStore.current.ai
        return ai.enabled && ai.apiKey.isNotBlank() && job.enabled
    }

    private fun cfgFor(job: String): AiJobConfig = configStore.current.ai.let {
        when (job) {
            AiRequests.RERANK_JOB -> it.rerank
            AiRequests.CLUSTERS_JOB -> it.clusters
            AiRequests.SUPPORT_JOB -> it.support.job()
            else -> it.themes
        }
    }

    /** [on] for a job by name: Phase 321's carries one more term, phase 320's `[support] enabled` (FR-321-1). */
    private fun jobOn(job: String): Boolean =
        on(cfgFor(job)) && (job != AiRequests.SUPPORT_JOB || configStore.current.support.enabled)

    /** Phase 274 — whether a build may ask for groups at all (AI on, a key, the job on). */
    fun clustersOn(): Boolean = on(configStore.current.ai.clusters)

    /**
     * Phase 274 (FR-274-5) — one request per build, the household's: a newer build replaces one still waiting. Sends
     * at once when it can, and answers what became of it: `waiting` (sent or queued) or `limit` (this month's limit
     * holds it back); `off` when the job cannot run.
     */
    suspend fun enqueueClusters(input: AiRequests.ClustersInput, reason: String, by: String): String {
        val queued = lock.withLock {
            if (!on(configStore.current.ai.clusters) || input.candidates.isEmpty()) return@withLock false
            db.aiQueries.enqueue(AiRequests.CLUSTERS_JOB, "household", "Household suggestions", reason, by,
                json.encodeToString(AiRequests.ClustersInput.serializer(), input), clock())
            true
        }
        if (!queued) return "off"
        runCatching { sendJob(AiRequests.CLUSTERS_JOB) }.onFailure { Logger.warn("AI: sending failed: ${it.message}", "ai") }
        return lock.withLock { if (heldBack[AiRequests.CLUSTERS_JOB] != null) "limit" else "waiting" }
    }

    /** After 269's build (and a viewer's first build or the admin's *Rebuild now*): queue what needs tagging and
     *  the re-ranks, then send what can go now. [reason] and [by] are what the Activity card says. */
    suspend fun afterBuild(inputs: List<RerankInput>, reason: String, by: String) {
        runCatching { enqueueThemes() }.onFailure { Logger.warn("AI: themes not queued: ${it.message}", "ai") }
        runCatching { enqueueRerank(inputs, reason, by) }.onFailure { Logger.warn("AI: re-rank not queued: ${it.message}", "ai") }
        runCatching { sendQueued() }.onFailure { Logger.warn("AI: sending failed: ${it.message}", "ai") }
    }

    // ─── Queuing (FR-272-1/2) ─────────────────────────────────────────────────

    private fun rerankSubject(userId: String, scope: String) = "$userId|$scope"

    /** One row per viewer and scope; a viewer already waiting is replaced by this, the newer shortlist. */
    suspend fun enqueueRerank(inputs: List<RerankInput>, reason: String, by: String): Int = lock.withLock {
        if (!on(configStore.current.ai.rerank)) return@withLock 0
        val now = clock()
        var queued = 0
        db.transaction {
            for (input in inputs) {
                if (input.shortlist.size < 2) continue
                db.aiQueries.enqueue(
                    AiRequests.RERANK_JOB, rerankSubject(input.userId, input.scope), input.label.ifBlank { input.userId },
                    reason, by, json.encodeToString(RerankInput.serializer(), input), now,
                )
                queued++
            }
        }
        if (queued > 0) nudge()
        queued
    }

    /** Titles that want themes (FR-270 job 2) and are neither tagged for this synopsis, waiting, nor out. */
    suspend fun enqueueThemes(): Int = lock.withLock {
        if (!on(configStore.current.ai.themes)) return@withLock 0
        val done = db.aiQueries.allThemes().executeAsList().associate { it.item_id to it.synopsis_hash }
        val waiting = db.aiQueries.waitingFor(AiRequests.THEMES_JOB).executeAsList().mapTo(HashSet()) { it.subject }
        val out = db.aiQueries.pendingBatchForJob(AiRequests.THEMES_JOB).executeAsOneOrNull()?.let { b ->
            db.aiQueries.batchItems(b.id).executeAsList().mapNotNullTo(HashSet()) { runCatching { json.decodeFromString(ThemeCtx.serializer(), it.context).item }.getOrNull() }
        }.orEmpty()
        val now = clock()
        val room = (MAX_THEME_REQUESTS - waiting.size).coerceAtLeast(0)
        val want = library().filter(AiRequests::wantsThemes).filter { item ->
            val key = item.jellyfinId ?: item.id
            key !in waiting && key !in out && done[key] != AiRequests.synopsisHash(item)
        }.take(room)
        db.transaction {
            for (item in want) {
                val key = item.jellyfinId ?: item.id
                db.aiQueries.enqueue(AiRequests.THEMES_JOB, key, item.title + (item.year?.let { " ($it)" } ?: ""), "untagged title", BY_SYSTEM, key, now)
            }
        }
        if (want.isNotEmpty()) nudge()
        want.size
    }

    /** FR-272-9 — *Remove* one waiting request; false once it has been sent (or never existed). */
    suspend fun removeQueued(id: Long): Boolean = lock.withLock {
        val row = db.aiQueries.queuedById(id).executeAsOneOrNull() ?: return@withLock false
        db.aiQueries.deleteQueued(id)
        // FR-321-3 — a ticket taken off the queue stops composing: the viewer's indicator turns off.
        if (row.job == AiRequests.SUPPORT_JOB) row.subject.toLongOrNull()?.let { supportLane?.settled(listOf(it)) }
        true
    }

    // ─── Sending (FR-272-3/4) ─────────────────────────────────────────────────

    private class Built(val queueId: Long, val customId: String, val request: JsonObject, val ctx: String, val label: String, val worst: Long)

    /** Both jobs: whatever waits goes, if its job has no batch out. */
    suspend fun sendQueued() {
        sendJob(AiRequests.THEMES_JOB)
        sendJob(AiRequests.RERANK_JOB)
        sendJob(AiRequests.CLUSTERS_JOB)
        sendJob(AiRequests.SUPPORT_JOB)
    }

    private suspend fun sendJob(job: String): Boolean = lock.withLock {
        val ai = configStore.current.ai
        val cfg = cfgFor(job)
        if (!jobOn(job)) return@withLock false
        if (db.aiQueries.pendingBatchForJob(job).executeAsOneOrNull() != null) return@withLock false
        if ((retryAt[job] ?: 0L) > clock()) return@withLock false
        val waiting = db.aiQueries.waitingFor(job).executeAsList()
        if (waiting.isEmpty()) { heldBack.remove(job); return@withLock false }

        val items = library()
        val byJf = items.mapNotNull { it.jellyfinId?.let { id -> id to it } }.toMap()
        val byKey = items.associateBy { it.jellyfinId ?: it.id }
        val themes = themes()
        val done = if (job == AiRequests.THEMES_JOB) db.aiQueries.allThemes().executeAsList().associate { it.item_id to it.synopsis_hash } else emptyMap()
        val maxTokens = when (job) {
            AiRequests.RERANK_JOB -> AiRequests.RERANK_MAX_TOKENS
            AiRequests.CLUSTERS_JOB -> AiRequests.CLUSTERS_MAX_TOKENS
            AiRequests.SUPPORT_JOB -> AiRequests.SUPPORT_MAX_TOKENS
            else -> AiRequests.THEMES_MAX_TOKENS
        }
        val lane = if (job == AiRequests.SUPPORT_JOB) supportLane else null
        lane?.beginPass()
        val built = ArrayList<Built>()
        val gone = ArrayList<Long>()
        for (row in waiting) {
            val b: Pair<JsonObject, String>? = when (job) {
                AiRequests.RERANK_JOB -> runCatching { json.decodeFromString(RerankInput.serializer(), row.payload) }.getOrNull()?.let { input ->
                    val shortlist = input.shortlist.mapNotNull { byJf[it] }.take(AiRequests.RERANK_SHORTLIST)
                    if (shortlist.size < 2) null else {
                        val customId = opaqueId("r", input.userId + "|" + input.scope)
                        AiRequests.rerankRequest(customId, cfg.model, cfg.effort, shortlist, input.watched.mapNotNull { byJf[it] }, themes) to
                            json.encodeToString(RerankCtx.serializer(), RerankCtx(input.userId, input.scope, shortlist.mapNotNull { it.jellyfinId }))
                    }
                }
                AiRequests.SUPPORT_JOB -> lane?.build(row.subject, opaqueId("s", "support|${row.subject}"), cfg)?.let { it.request to it.ctx }
                AiRequests.CLUSTERS_JOB -> runCatching { json.decodeFromString(AiRequests.ClustersInput.serializer(), row.payload) }.getOrNull()?.let { input ->
                    AiRequests.clustersRequest(opaqueId("c", "household|" + input.builtAt), cfg.model, cfg.effort, input) to
                        json.encodeToString(ClustersCtx.serializer(), ClustersCtx(input.builtAt, input.candidates.map { it.tmdbId }, AiRequests.clusterSourceIds(input).sorted()))
                }
                else -> byKey[row.subject]?.takeIf { AiRequests.wantsThemes(it) && done[row.subject] != AiRequests.synopsisHash(it) }?.let { item ->
                    AiRequests.themesRequest(opaqueId("t", row.subject), cfg.model, cfg.effort, item) to
                        json.encodeToString(ThemeCtx.serializer(), ThemeCtx(row.subject, AiRequests.synopsisHash(item)))
                }
            }
            // A title that has gone, or got TMDB keywords meanwhile, or a shortlist that emptied: nothing to ask.
            if (b == null) { gone += row.id; continue }
            val (request, ctx) = b
            val customId = (request["custom_id"] as JsonPrimitive).content
            val worst = AiPricing.worstCaseMicroUsd(cfg.model, request.toString().length.toLong(), 1, maxTokens)
            built += Built(row.id, customId, request, ctx, row.label, worst)
            if (built.size >= MAX_THEME_REQUESTS) break
        }
        if (gone.isNotEmpty()) db.transaction { gone.forEach { db.aiQueries.deleteQueued(it) } }
        if (built.isEmpty()) { heldBack.remove(job); return@withLock false }

        // FR-272-4 — oldest first, while the worst case still fits under what is left of the month's limit.
        val spent = spentThisMonth(job)
        val limit = (cfg.monthlyLimitUsd * 1_000_000.0).toLong()
        var sum = 0L
        val take = ArrayList<Built>()
        for (b in built) {
            if (spent + sum + b.worst > limit) break
            sum += b.worst
            take += b
        }
        // FR-321-8 — a person is waiting on a support ticket, so what the month's limit holds back is forwarded rather than
        // left waiting on the AI (FR-272-4 keeps background work waiting, which suits the other jobs).
        if (lane != null && take.size < built.size) {
            val limitWhy = "this month's AI limit is reached"
            val held = built.drop(take.size)
            val subjects = waiting.associate { it.id to it.subject }
            db.transaction { held.forEach { db.aiQueries.deleteQueued(it.queueId) } }
            held.forEach { b -> subjects[b.queueId]?.let { lane.heldByLimit(it, limitWhy) } }
            run(job, "$limitWhy — ${held.size} ticket${if (held.size == 1) "" else "s"} forwarded to an admin")
            heldBack.remove(job)
        } else if (take.size < built.size) {
            val left = built.size - take.size
            heldBack[job] = "$left waiting — this month's limit: ${AiPricing.usd((limit - spent - sum).coerceAtLeast(0))} left, " +
                "the next request could cost up to ${AiPricing.usd(built[take.size].worst)}"
        } else heldBack.remove(job)
        if (take.isEmpty()) return@withLock false

        val batch = runCatching { client.createBatch(ai.apiKey, AiRequests.requestsJson(take.map { it.request })) }.getOrElse {
            retryAt[job] = clock() + SEND_RETRY_SEC
            run(job, "couldn't send: ${it.message?.take(120)}")
            return@withLock false
        }
        retryAt.remove(job)
        val now = clock()
        val effort = if (AiPricing.model(cfg.model).effort) cfg.effort else ""
        db.transaction {
            db.aiQueries.insertBatch(batch.id, job, cfg.model, now, STATUS_SUBMITTED, take.size.toLong())
            for (b in take) {
                db.aiQueries.insertBatchItem(batch.id, b.customId, b.ctx)
                db.aiQueries.insertTranscript(batch.id, b.customId, b.label, cfg.model, effort, AiRequests.systemOf(b.request), AiRequests.sentOf(b.request))
                db.aiQueries.deleteQueued(b.queueId)
                lane?.sent(b.ctx, batch.id, b.customId, cfg.model, AiRequests.sentOf(b.request))
            }
        }
        val noun = nounOf(job)
        run(job, "sent · ${take.size} $noun${if (take.size == 1) "" else "s"} · waiting for Anthropic")
        Logger.info("AI: $job batch ${batch.id} sent (${take.size} requests, worst case ${AiPricing.usd(sum)})", "ai")
        true
    }

    // ─── Reading back ─────────────────────────────────────────────────────────

    /** Every batch still out: its counts are kept, an ended one is read and applied, and then whatever waits
     *  for that job goes (FR-272-3). Off = nothing is polled either. */
    suspend fun pollPending() {
        val ai = configStore.current.ai
        if (!ai.enabled || ai.apiKey.isBlank()) return
        for (row in db.aiQueries.pendingBatches().executeAsList()) {
            val batch = runCatching { client.getBatch(ai.apiKey, row.id) }.getOrElse {
                if ((it as? AnthropicClient.ApiException)?.status == 404) {
                    val line = if (row.job == AiRequests.SUPPORT_JOB) "batch lost at Anthropic — its tickets were forwarded to an admin"
                        else "batch lost at Anthropic — standard list kept"
                    if (row.job == AiRequests.SUPPORT_JOB) {
                        // FR-321-9 — nobody will answer these now, and a person is waiting.
                        val ctxs = db.aiQueries.batchItems(row.id).executeAsList().map { it.context }
                        ctxs.forEach { supportLane?.lost(it) }
                        supportLane?.settled(ctxs.mapNotNull { supportLane?.ticketOf(it) })
                        db.aiQueries.deleteBatchItems(row.id)
                    }
                    db.aiQueries.setBatchEnded(STATUS_LOST, clock(), line, 0L, row.id)
                    run(row.job, line)
                    pruneHistory()
                }
                continue
            }
            if (batch.counts.isNotEmpty()) db.aiQueries.setBatchCounts(json.encodeToString(COUNTS, batch.counts), row.id)
            if (batch.processingStatus != "ended") continue
            val url = batch.resultsUrl ?: "${AnthropicClient.BASE}/v1/messages/batches/${row.id}/results"
            val lines = runCatching { client.results(ai.apiKey, url) }.getOrElse { continue }
            applyResults(row.id, row.job, row.model, row.cancelling != 0L, lines)
            sendJob(row.job)
        }
    }

    private suspend fun applyResults(batchId: String, job: String, model: String, cancelled: Boolean, lines: List<JsonObject>) {
        val contexts = db.aiQueries.batchItems(batchId).executeAsList().associate { it.custom_id to it.context }
        val titles = if (job == AiRequests.RERANK_JOB) library().mapNotNull { m -> m.jellyfinId?.let { it to m.title + (m.year?.let { y -> " ($y)" } ?: "") } }.toMap() else emptyMap()
        var requests = 0L; var input = 0L; var output = 0L; var cacheWrite = 0L; var cacheRead = 0L
        var accepted = 0; var rejected = 0; var expired = 0
        val settled = ArrayList<Long>()
        for (line in lines) {
            val r = AiRequests.parseResult(line) ?: continue
            val ctx = contexts[r.customId] ?: continue
            r.usage?.let { u -> requests++; input += u.input; output += u.output; cacheWrite += u.cacheWrite; cacheRead += u.cacheRead }
            if (r.type == "expired" || r.type == "canceled") expired++
            var verdict: String
            var readable: String? = null
            when (job) {
                AiRequests.RERANK_JOB -> {
                    val c = runCatching { json.decodeFromString(RerankCtx.serializer(), ctx) }.getOrNull() ?: continue
                    val judged = AiRequests.judgeRerank(r, c.ids)
                    val picks = judged.value
                    if (picks == null) {
                        if (r.type == "succeeded") rejected++
                        verdict = "kept the standard list — ${judged.why}"
                    } else {
                        rerankSink?.apply(c.u, c.s, picks)
                        accepted++
                        verdict = judged.why
                        readable = picks.mapIndexed { i, p -> "${i + 1}. ${titles[p.jellyfinId] ?: p.jellyfinId} — ${p.reason.ifEmpty { "(topped up from the standard list)" }}" }.joinToString("\n")
                    }
                }
                AiRequests.SUPPORT_JOB -> {
                    val lane = supportLane ?: continue
                    lane.ticketOf(ctx)?.let { settled += it }
                    val cost = r.usage?.let { AiPricing.costMicroUsd(model, it) } ?: 0L
                    val v = lane.answered(ctx, batchId, r, model, cost)
                    if (v.used) accepted++ else if (r.type == "succeeded") rejected++
                    verdict = v.verdict
                    readable = v.readable
                }
                AiRequests.CLUSTERS_JOB -> {
                    val c = runCatching { json.decodeFromString(ClustersCtx.serializer(), ctx) }.getOrNull() ?: continue
                    val judged = AiRequests.judgeClusters(r, c.ids.size, c.sources.toSet())
                    val groups = judged.value
                    if (groups == null) {
                        if (r.type == "succeeded") rejected++
                        clustersSink?.failed(c.builtAt, judged.why)
                        verdict = "kept the genre groups — ${judged.why}"
                    } else {
                        clustersSink?.apply(c.builtAt, groups, c.ids)
                        accepted++
                        verdict = judged.why
                        readable = groups.joinToString("\n") { g -> "${g.name} · ${g.candidates.size} film${if (g.candidates.size == 1) "" else "s"} · ${g.sources.joinToString(" ")}" }
                    }
                }
                else -> {
                    val c = runCatching { json.decodeFromString(ThemeCtx.serializer(), ctx) }.getOrNull() ?: continue
                    val judged = AiRequests.judgeThemes(r)
                    val themes = judged.value
                    if (themes == null) {
                        if (r.type == "succeeded") rejected++
                        // A title the model could not tag is not retried until its synopsis changes: an answer
                        // that failed once would otherwise be paid for again on every run.
                        if (r.type == "succeeded") db.aiQueries.putTheme(c.item, "", c.hash, clock())
                        verdict = "not tagged — ${judged.why}"
                    } else {
                        db.aiQueries.putTheme(c.item, themes.joinToString("|"), c.hash, clock())
                        accepted++
                        verdict = judged.why
                        readable = themes.joinToString(", ")
                    }
                }
            }
            val u = r.usage
            db.aiQueries.setTranscriptAnswer(
                r.text ?: r.error, r.type, r.stopReason, verdict, readable,
                u?.input ?: 0L, u?.output ?: 0L, u?.let { AiPricing.costMicroUsd(model, it) } ?: 0L,
                batchId, r.customId,
            )
        }
        val cost = AiPricing.costMicroUsd(model, AiPricing.Usage(input, output, cacheWrite, cacheRead))
        val now = clock()
        val noun = nounOf(job)
        val kept = if (job == AiRequests.SUPPORT_JOB) "forwarded to an admin" else "kept the standard list"
        val tail = if (job == AiRequests.SUPPORT_JOB) "tickets forwarded to an admin" else "standard list kept"
        val line = when {
            accepted == 0 && cancelled -> "cancelled — $tail"
            accepted == 0 && expired > 0 -> "batch expired — $tail"
            // A support result that was not applied was discarded or forwarded (the ticket's transcript says which).
            accepted == 0 && job == AiRequests.SUPPORT_JOB -> "no answer was applied"
            accepted == 0 -> "no usable answer — $tail"
            else -> "ran · $accepted $noun${if (accepted == 1) "" else "s"} · ${AiPricing.usd(cost)}" +
                (if (rejected + expired > 0) " · ${rejected + expired} $kept" else "")
        }
        db.transaction {
            if (requests > 0) addUsage(job, monthKey(now), model, requests, input, output, cacheWrite, cacheRead, cost)
            db.aiQueries.setBatchEnded(STATUS_ENDED, now, line, cost, batchId)
            db.aiQueries.deleteBatchItems(batchId)
        }
        run(job, line)
        pruneHistory()
        // FR-321-3 — the batch is no longer out, so these tickets' `aiComposing` has just turned off: push once more.
        if (settled.isNotEmpty()) supportLane?.settled(settled)
        Logger.info("AI: $job batch $batchId read — $line", "ai")
    }

    /** FR-272-13 — the five newest batches keep their record and conversations; older ones go. */
    private fun pruneHistory() {
        val old = db.aiQueries.endedBatches().executeAsList().drop(HISTORY)
        if (old.isEmpty()) return
        db.transaction {
            for (b in old) {
                db.aiQueries.deleteTranscripts(b.id)
                db.aiQueries.deleteBatchItems(b.id)
                db.aiQueries.deleteBatch(b.id)
            }
        }
    }

    // ─── Cancel (FR-272-10) ───────────────────────────────────────────────────

    enum class Cancel { SENT, NOT_FOUND, ENDED, FAILED }

    /** Asks Anthropic to cancel; the batch then ends as usual and is read like any other. */
    suspend fun cancel(batchId: String): Cancel {
        val row = db.aiQueries.batchById(batchId).executeAsOneOrNull() ?: return Cancel.NOT_FOUND
        if (row.status != STATUS_SUBMITTED) return Cancel.ENDED
        val key = configStore.current.ai.apiKey
        if (key.isBlank()) return Cancel.FAILED
        runCatching { client.cancelBatch(key, batchId) }.onFailure {
            Logger.warn("AI: cancel of $batchId failed: ${it.message}", "ai")
            return Cancel.FAILED
        }
        db.aiQueries.setBatchCancelling(batchId)
        Logger.info("AI: ${row.job} batch $batchId — cancel asked", "ai")
        nudge()
        return Cancel.SENT
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

    // ─── What Activity shows (FR-272-8..15) ───────────────────────────────────
    // No field below has a default: the server's `Json` leaves out a field equal to its default (270's lesson).

    @Serializable
    data class QueuedDto(val id: Long, val label: String, val reason: String, val by: String, val queuedAt: Long)

    @Serializable
    data class OutDto(val id: String, val sentAt: Long, val requests: Long, val counts: Map<String, Long>, val cancelling: Boolean)

    @Serializable
    data class JobView(
        val job: String, val label: String, val on: Boolean, val model: String, val modelLabel: String,
        val spentMicroUsd: Long, val limitMicroUsd: Long,
        val waiting: List<QueuedDto>, val heldBack: String?, val out: OutDto?,
    )

    @Serializable
    data class HistoryDto(
        val id: String, val job: String, val model: String, val modelLabel: String, val sentAt: Long, val endedAt: Long?,
        val requests: Long, val outcome: String?, val costMicroUsd: Long?, val hasTranscripts: Boolean,
    )

    @Serializable
    data class JobsView(val enabled: Boolean, val jobs: List<JobView>, val history: List<HistoryDto>)

    @Serializable
    data class TranscriptDto(
        val label: String, val model: String, val effort: String, val system: String, val sent: String,
        val answer: String?, val resultType: String?, val stopReason: String?, val verdict: String?, val readable: String?,
        val inputTokens: Long, val outputTokens: Long, val costMicroUsd: Long,
    )

    @Serializable
    data class BatchDetail(val batch: HistoryDto, val requests: List<TranscriptDto>)

    /** One viewer's re-rank in flight, for *Rebuild now*'s line (FR-272-12): `waiting` or `out`, and since when. */
    @Serializable
    data class ViewerPending(val state: String, val since: Long)

    private fun historyOf(b: dev.jellystructure.db.Ai_batch, hasTranscripts: Boolean) = HistoryDto(
        b.id, b.job, b.model, AiPricing.model(b.model).label, b.submitted_at, b.ended_at, b.request_count, b.outcome, b.cost_micro_usd, hasTranscripts,
    )

    suspend fun jobsView(): JobsView = lock.withLock {
        val ai = configStore.current.ai
        val jobs = listOf(
            AiRequests.RERANK_JOB to "Re-rank Recommended", AiRequests.THEMES_JOB to "Theme tags",
            AiRequests.CLUSTERS_JOB to "Suggestion clusters", AiRequests.SUPPORT_JOB to "Support",
        ).map { (job, label) ->
            val cfg = cfgFor(job)
            val out = db.aiQueries.pendingBatchForJob(job).executeAsOneOrNull()
            JobView(
                job = job, label = label, on = jobOn(job), model = cfg.model, modelLabel = AiPricing.model(cfg.model).label,
                spentMicroUsd = spentThisMonth(job), limitMicroUsd = (cfg.monthlyLimitUsd * 1_000_000.0).toLong(),
                waiting = db.aiQueries.waitingFor(job).executeAsList().map { QueuedDto(it.id, it.label, it.reason, it.queued_by, it.queued_at) },
                heldBack = heldBack[job],
                out = out?.let { b ->
                    OutDto(b.id, b.submitted_at, b.request_count,
                        b.counts?.let { c -> runCatching { json.decodeFromString(COUNTS, c) }.getOrNull() }.orEmpty(), b.cancelling != 0L)
                },
            )
        }
        val history = db.aiQueries.endedBatches().executeAsList().take(HISTORY).map { b ->
            historyOf(b, db.aiQueries.transcriptsFor(b.id).executeAsList().isNotEmpty())
        }
        JobsView(enabled = ai.enabled && ai.apiKey.isNotBlank(), jobs = jobs, history = history)
    }

    /** FR-272-15 — one batch's conversations (a batch still out shows its prompts and no answers yet). */
    fun batchDetail(batchId: String): BatchDetail? {
        val b = db.aiQueries.batchById(batchId).executeAsOneOrNull() ?: return null
        val rows = db.aiQueries.transcriptsFor(batchId).executeAsList()
        return BatchDetail(historyOf(b, rows.isNotEmpty()), rows.map {
            TranscriptDto(it.label, it.model, it.effort, it.system_prompt, it.sent, it.answer, it.result_type, it.stop_reason,
                it.verdict, it.readable, it.input_tokens, it.output_tokens, it.cost_micro_usd)
        })
    }

    /** FR-272-12 — null when the re-rank job is off or nothing is in flight for this viewer. */
    fun pendingFor(userId: String): ViewerPending? {
        if (!on(configStore.current.ai.rerank)) return null
        db.aiQueries.pendingBatchForJob(AiRequests.RERANK_JOB).executeAsOneOrNull()?.let { b ->
            val mine = db.aiQueries.batchItems(b.id).executeAsList().any {
                runCatching { json.decodeFromString(RerankCtx.serializer(), it.context).u }.getOrNull() == userId
            }
            if (mine) return ViewerPending("out", b.submitted_at)
        }
        return db.aiQueries.queuedForSubjectPrefix(AiRequests.RERANK_JOB, "$userId|%").executeAsOneOrNull()?.let { ViewerPending("waiting", it.queued_at) }
    }

    /** Phase 274 — a clusters request still queued or out: a build waiting on it keeps saying *waiting*. */
    fun clustersInFlight(): Boolean =
        db.aiQueries.waitingFor(AiRequests.CLUSTERS_JOB).executeAsList().isNotEmpty() || db.aiQueries.pendingBatchForJob(AiRequests.CLUSTERS_JOB).executeAsOneOrNull() != null

    /** Waiting requests and batches out, for Activity's tab badge (FR-272-11). */
    fun inFlight(): Long = db.aiQueries.countQueued().executeAsOne() + db.aiQueries.pendingBatches().executeAsList().size

    // ─── The tab's status (dev review item 1) ─────────────────────────────────

    @Serializable
    data class ModelDto(val id: String, val label: String, val inputPerMTok: Double, val outputPerMTok: Double, val effort: Boolean)

    @Serializable
    data class JobStatus(
        val spentThisMonthMicroUsd: Long,
        val lastRun: String? = null,
        val lastRunAt: Long? = null,
        val pending: Boolean = false,
        /** Phase 272 — how many requests wait, and why they were held back (the monthly limit), if they were. */
        val waiting: Long = 0,
        val heldBack: String? = null,
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
        /** Phase 274. */
        val clusters: JobStatus,
        /** Phase 321. */
        val support: JobStatus,
    )

    suspend fun status(viewers: Int): Status {
        val key = configStore.current.ai.apiKey
        fun job(job: String, estimate: Map<String, Long>, basis: String): JobStatus {
            val run = db.aiQueries.runFor(job).executeAsOneOrNull()
            return JobStatus(
                spentThisMonthMicroUsd = spentThisMonth(job),
                lastRun = run?.line, lastRunAt = run?.at,
                pending = db.aiQueries.pendingBatchForJob(job).executeAsOneOrNull() != null,
                waiting = db.aiQueries.waitingFor(job).executeAsList().size.toLong(),
                heldBack = heldBack[job],
                estimateMicroUsd = estimate, estimateBasis = basis,
            )
        }
        val untagged = run {
            val done = db.aiQueries.allThemes().executeAsList().associate { it.item_id to it.synopsis_hash }
            library().count { AiRequests.wantsThemes(it) && done[it.jellyfinId ?: it.id] != AiRequests.synopsisHash(it) }
        }
        val (rerankEst, rerankBasis) = estimate(AiRequests.RERANK_JOB, RERANK_ASSUMED, viewers * RUNS_PER_MONTH)
        val (themesEst, themesBasis) = estimate(AiRequests.THEMES_JOB, THEMES_ASSUMED, untagged.toDouble())
        val (clustersEst, clustersBasis) = estimate(AiRequests.CLUSTERS_JOB, CLUSTERS_ASSUMED, RUNS_PER_MONTH)
        val (supportEst, supportBasis) = estimate(AiRequests.SUPPORT_JOB, SUPPORT_ASSUMED, SUPPORT_ASKS_PER_MONTH)
        return Status(
            keyHint = key.takeIf { it.length >= 8 }?.let { "…" + it.takeLast(4) },
            pricesAsOf = AiPricing.AS_OF,
            models = AiPricing.MODELS.map { ModelDto(it.id, it.label, it.inputPerMTok, it.outputPerMTok, it.effort) },
            rerank = job(AiRequests.RERANK_JOB, rerankEst, "$viewers viewer${if (viewers == 1) "" else "s"} × about ${RUNS_PER_MONTH.toInt()} runs a month · $rerankBasis"),
            themes = job(AiRequests.THEMES_JOB, themesEst, "once, for $untagged untagged title${if (untagged == 1) "" else "s"} · $themesBasis"),
            clusters = job(AiRequests.CLUSTERS_JOB, clustersEst, "one request per suggestions build, about ${RUNS_PER_MONTH.toInt()} a month · $clustersBasis"),
            support = job(AiRequests.SUPPORT_JOB, supportEst, "one request per ticket message, about ${SUPPORT_ASKS_PER_MONTH.toInt()} a month · $supportBasis"),
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
        private fun nounOf(job: String) = when (job) { AiRequests.RERANK_JOB -> "viewer"; AiRequests.CLUSTERS_JOB -> "build"; AiRequests.SUPPORT_JOB -> "ticket"; else -> "title" }

        /** The process's one instance, for the routes. Set by Main. */
        var current: AiJobs? = null
        /** How many viewers a re-rank covers, for the estimate. Set by Main (269's recently seen viewers). */
        var viewerCount: () -> Int = { 0 }
        const val STATUS_SUBMITTED = "submitted"
        const val STATUS_ENDED = "ended"
        const val STATUS_LOST = "lost"
        /** FR-272-5 — a batch out is looked at every minute. */
        private const val POLL_MS = 60L * 1000
        /** After a send failed (no answer, a 5xx after backoff): try again in five minutes. */
        private const val SEND_RETRY_SEC = 5L * 60
        /** FR-272-13 — how many batches keep their record and conversations. */
        const val HISTORY = 5
        /** Who queued a request nobody asked for by hand. */
        const val BY_SYSTEM = "jellystructure"
        private val COUNTS = MapSerializer(String.serializer(), Long.serializer())
        private const val MAX_THEME_REQUESTS = 2_000
        /** 269's weekly build, about 4.3 times a month. */
        private const val RUNS_PER_MONTH = 4.33
        /** FR-270-7's starting assumptions: a re-rank ~12k in / ~5k out; a title's themes ~0.5k in / ~0.15k out. */
        private val RERANK_ASSUMED = AiPricing.Usage(input = 12_000, output = 5_000)
        private val THEMES_ASSUMED = AiPricing.Usage(input = 500, output = 150)
        /** Phase 321 — the system prompt and a short thread: ~2.5k in, ~0.5k out; and a guess at how often viewers write. */
        private val SUPPORT_ASSUMED = AiPricing.Usage(input = 2_500, output = 500)
        private const val SUPPORT_ASKS_PER_MONTH = 30.0
        /** What a deleted ticket's conversation is blanked to in the batches that still hold it (FR-320-17). */
        const val SCRUBBED = "[deleted with the ticket]"
        /** Phase 274 — a household's watching and 50 candidates as ids: ~9k in, ~2k out. */
        private val CLUSTERS_ASSUMED = AiPricing.Usage(input = 9_000, output = 2_000)

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
    }
}
