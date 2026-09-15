package dev.jellystructure.media

import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.Media_job
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.LaneSummary
import dev.jellystructure.jobs.MediaJobParams
import dev.jellystructure.jobs.MediaJobSnapshot
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.resolver.primaryAudioLanguage
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

/**
 * Phase 109 — a persistent job queue for heavy media edits (ffmpeg remuxes: track reorder/remove) and,
 * since Phase 164/213, background detection/pre-warm work that was previously inline in the pipeline.
 * Applying a track edit used to run ffmpeg *inline on the request thread*, occupying a Ktor CIO worker
 * for the whole remux (minutes for 4K) and starving the server under any concurrent traffic. Enqueue
 * returns immediately; a worker pool drains the queue so the request path, and the server's own
 * responsiveness, are never blocked by ffmpeg.
 *
 * Phase 213 — three named FIFO queues (`media`, `segments`, `subtitles`) share ONE configurable worker
 * pool (`behavior.job_workers`, 1–3) instead of each sizing its own concurrency independently. The rule
 * that keeps this safe: **a queue may never have more than one worker actively drawing from it at a
 * time.** That single rule reproduces exactly what the old, independently-dispatched media lane's FIFO-1
 * dispatcher guaranteed — no two `reorder`/`remove`/`mkv_layout_repair` jobs can ever race the same
 * file's shared temp path — without needing a dedicated dispatcher to get it. See [start]/[claimNext]
 * and the phase-213 spec's FR-213-1.
 *
 * The bulk-reorder wizard (Phase 96) already runs off-request via its own `appScope.launch`; rather
 * than re-architect its well-exercised per-episode logic into individual queue rows, it registers one
 * `media_job` row for visibility on the Jobs page and takes [acquireBulkSlot] before starting — occupying
 * the `media` queue for its duration, the same mutual-exclusion a single-file reorder/remove job gets, so
 * a bulk run and a single-file edit can never remux in parallel and double the disk I/O. It has never
 * blocked `segments`/`subtitles` and still doesn't.
 */
/** Phase 164 — [enqueueSegments]'s/[enqueueSubtitles]'s return: the snapshot plus whether this call
 *  actually inserted a new row or found an existing active one under the same dedupe key instead. Shared
 *  by both read-only queues (Phase 213 added the second use) since the shape is identical. */
data class SegmentEnqueueResult(val snapshot: MediaJobSnapshot, val deduped: Boolean)

/** Phase 213 (FR-213-7) — surfaced on `GET /api/health` as a `job_queues` block: the shared pool's own
 *  occupancy plus per-queue counts, so the state that mattered during the 2026-09-15 incident (ten
 *  extractions jellystructure's own gates reported as idle) is at least reportable. Not a claim to
 *  measure Jellyfin-side cost — only the count of outstanding requests jellystructure itself issued. */
data class JobQueueHealth(
    val configuredWorkers: Int,
    val activeWorkers: Int,
    val mediaQueued: Int,
    val segmentsQueued: Int,
    val subtitlesQueued: Int,
    val subtitlesDeferredByPlayback: Boolean,
    val subtitlesLastFailure: String?,
) {
    fun toJson(): String =
        """{"configured_workers":$configuredWorkers,"active_workers":$activeWorkers,""" +
            """"media_queued":$mediaQueued,"segments_queued":$segmentsQueued,"subtitles_queued":$subtitlesQueued,""" +
            """"subtitles_deferred_by_playback":$subtitlesDeferredByPlayback,""" +
            """"subtitles_last_failure":${subtitlesLastFailure?.let { "\"" + it.replace("\"", "'").replace("\n", " ") + "\"" } ?: "null"}}"""
}

class MediaJobQueue(
    private val db: JellystructureDb,
    private val store: MediaStore,
    private val broadcaster: WsBroadcaster,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
    private val mediaHistory: MediaHistory,
    private val seedingGuard: SeedingGuard,
    private val arrRescan: ArrRescanService?,
    private val appScope: CoroutineScope,
    // Phase 164 — the segments queue's own dependencies. Nullable so this class stays constructible
    // (and the media queue fully functional) in any test/bootstrap context that doesn't wire segments.
    private val segmentStore: MediaSegmentStore? = null,
    private val fingerprintService: FingerprintService? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val queries get() = db.mediaJobQueries

    // Phase 213 — the shared worker pool. Replaces the old per-lane pair (media's fixed FIFO-1
    // dispatcher, segments' own independent supervisor) with one live-rescaled pool sized by
    // behavior.job_workers, matching runPipelineStepPool's/the old segments supervisor's own
    // live-rescale pattern: a Settings change takes effect on the next dispatch, not a restart.
    private val poolActiveWorkers = kotlin.concurrent.AtomicInt(0)
    private val poolTargetWorkers = kotlin.concurrent.AtomicInt(2)

    // Guards claim-then-mark-running AND occupiedQueues' own mutation — both must be atomic together,
    // or two workers could both observe a queue as free and both claim from it.
    private val claimMutex = kotlinx.coroutines.sync.Mutex()

    // A queue name is present here for exactly as long as one worker is actively running a job claimed
    // from it — THIS is the rule that keeps the merge safe (class doc, FR-213-1). Only ever mutated
    // under claimMutex.
    private val occupiedQueues = mutableSetOf<String>()

    // media queue — identifies the one job (if any) currently running from it, for pkill-based cancel
    // (see cancel()). At most one can ever be running (occupiedQueues' own guarantee), so a single field
    // is still correct — unchanged from the pre-213 media lane.
    @Volatile private var runningJobId: String? = null
    @Volatile private var cancelRunning = false
    @Volatile private var bulkRunning = false
    // Guards the check-then-set on bulkRunning below — two admins submitting a bulk reorder at the same
    // instant must not both observe bulkRunning=false and both start (a plain @Volatile flag alone races).
    private val bulkClaimMutex = kotlinx.coroutines.sync.Mutex()

    // segments/subtitles queues — cooperative cancel (FR-164-5, extended by Phase 213 to subtitles):
    // neither has a temp file to pkill (their ffmpeg calls only ever READ the source file), so
    // cancellation is a per-job-id flag the running worker's isCancelled callback polls between units of
    // work. @Volatile + whole-set replacement (never mutated in place) gives safe publication across
    // worker threads without a mutex on the hot read path.
    @Volatile private var cooperativeCancelledIds: Set<String> = emptySet()

    /** Boots the worker pool: any row left `running` from a prior crash/restart is re-queued (a
     *  media-queue row's temp copy, if any, is simply overwritten or ignored on the retry — the original
     *  file was never touched; a segments/subtitles-queue row is idempotent to re-run outright). */
    fun start() {
        queries.requeueRunning()
        // Phase 182 (FR-182-6) — background work (ffmpeg remuxes, segment detection, subtitle pre-warm),
        // never request-serving; tags this coroutine and everything launched under it so
        // OutboundHttp/ProcessGate reserve interactive capacity it can never consume.
        appScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND) { poolSupervisorLoop() }
    }

    fun isBusy(): Boolean = bulkRunning || occupiedQueues.isNotEmpty()

    // ── Enqueue / cancel / retry ──────────────────────────────────────────────

    suspend fun enqueue(type: String, mediaId: String, label: String, params: MediaJobParams, fileCount: Int = 1): MediaJobSnapshot {
        val id = "mj-${genId()}"
        queries.insert(id, type, mediaId, label, json.encodeToString(MediaJobParams.serializer(), params), "queued", "admin", epochSeconds(), fileCount.toLong(), "media", null)
        val snap = snapshotOf(id)!!
        broadcaster.broadcast(JobEvent.MediaJobUpdate(snap))
        return snap
    }

    /** Phase 164 (FR-164-1/7) — the segments queue's own enqueue: dedup-aware via the partial unique
     *  index on `dedupe_key` (`media_job_dedupe_active`), not a check-then-insert (two concurrent
     *  enqueues — a pipeline run and an operator clicking "detect again" — must not both observe "not
     *  present" and both insert). A unique-constraint violation on the insert is not an error: it means
     *  this exact work unit is already queued or running, and [SegmentEnqueueResult.deduped] tells the
     *  caller so (the redetect endpoint uses it for "already queued" vs "queued" toast copy). */
    suspend fun enqueueSegments(type: String, mediaId: String, label: String, params: MediaJobParams, fileCount: Int, dedupeKey: String): SegmentEnqueueResult =
        enqueueDeduped(type, mediaId, label, params, fileCount, dedupeKey, "segments")

    /** Phase 213 (FR-213-2) — the subtitles queue's own enqueue: one job per item, always deduplicated
     *  on `(media_id)` via a deterministic `sub:<mediaId>` key so a re-run (another pipeline pass, a
     *  timed-out retry landing while the original is somehow still active) can never stack duplicates. */
    suspend fun enqueueSubtitles(mediaId: String, label: String, params: MediaJobParams): SegmentEnqueueResult =
        enqueueDeduped("prewarm_subtitles", mediaId, label, params, 1, "sub:$mediaId", "subtitles")

    private suspend fun enqueueDeduped(type: String, mediaId: String, label: String, params: MediaJobParams, fileCount: Int, dedupeKey: String, lane: String): SegmentEnqueueResult {
        val id = "mj-${genId()}"
        val inserted = runCatching {
            queries.insert(id, type, mediaId, label, json.encodeToString(MediaJobParams.serializer(), params), "queued", "admin", epochSeconds(), fileCount.toLong(), lane, dedupeKey)
        }.isSuccess
        if (inserted) {
            val snap = snapshotOf(id)!!
            broadcaster.broadcast(JobEvent.MediaJobUpdate(snap))
            return SegmentEnqueueResult(snap, deduped = false)
        }
        val existing = queries.findByDedupeKeyActive(dedupeKey).executeAsOneOrNull()?.let { toSnapshot(it) }
        // Vanishingly unlikely (the row that lost the race to us finished between our insert failing and
        // this lookup) but must not crash the request — fall back to a synthetic not-yet-persisted
        // snapshot so the caller still gets an honest "it's spoken for" answer.
        val snap = existing ?: MediaJobSnapshot(
            id = "", type = type, mediaId = mediaId, label = label, state = "queued", enqueuedBy = "admin",
            createdAt = epochSeconds(), fileCount = fileCount, lane = lane,
        )
        return SegmentEnqueueResult(snap, deduped = true)
    }

    /** Registers a job row for a bulk-reorder run that executes via its own existing code path — see
     *  the class doc. Caller is responsible for [acquireBulkSlot]/[markBulkFinished] around the run. */
    suspend fun registerBulkJob(mediaId: String, label: String, params: MediaJobParams, fileCount: Int): MediaJobSnapshot =
        enqueue("bulk_reorder", mediaId, label, params, fileCount)

    /** Blocks (politely) until no `media`-queue job is running and no other bulk run is in flight, then
     *  claims the slot. Mirrors the media queue's one-active-job guarantee for the bulk path without
     *  routing its per-episode logic through the generic worker loop. */
    suspend fun acquireBulkSlot(jobId: String) {
        bulkClaimMutex.lock()
        try {
            while ("media" in occupiedQueues || bulkRunning) delay(500)
            bulkRunning = true
        } finally {
            bulkClaimMutex.unlock()
        }
        queries.markRunning(epochSeconds(), jobId)
        broadcastSnapshot(jobId)
    }

    suspend fun markBulkFinished(jobId: String, ok: Boolean, error: String?, filesDone: Int) {
        queries.markFinished(if (ok) "done" else "failed", epochSeconds(), error, filesDone.toLong(), jobId)
        bulkRunning = false
        broadcastSnapshot(jobId)
    }

    /** Cancels a queued job outright, or best-effort stops a running one. The media queue kills its
     *  ffmpeg child by matching its unique temp output path in the process list (see class doc on why
     *  that's simpler/safer than tracking a PID through a nested shell/nice/ionice invocation). The
     *  segments/subtitles queues have no such child to kill (their ffmpeg/fpcalc/HTTP calls only ever
     *  READ the source file) — cancellation there is cooperative (FR-164-5/Phase 213): the job stops
     *  after the unit of work currently in flight, not instantly. Returns false if the job wasn't
     *  cancellable. */
    suspend fun cancel(jobId: String): Boolean {
        val row = queries.findById(jobId).executeAsOneOrNull() ?: return false
        return when (row.state) {
            "queued" -> {
                queries.markFinished("cancelled", epochSeconds(), "Cancelled before it started", row.files_done, jobId)
                broadcastSnapshot(jobId)
                true
            }
            "running" -> {
                if (row.lane == "segments" || row.lane == "subtitles") {
                    cooperativeCancelledIds = cooperativeCancelledIds + jobId
                    return true
                }
                cancelRunning = true
                val tmp = tmpFileFor(row)
                // Security fix (2026-08-02 review, finding L1) — `tmp` is shell-quoted correctly, but
                // pkill -f matches its pattern as an EXTENDED REGEX against the whole process command
                // line, not a literal string. The path derives from a real media FILENAME (attacker/
                // user-influenceable — a torrent or download can be named anything), so a name
                // containing ERE metacharacters (e.g. ".*") widens the match far beyond this one temp
                // file and can kill unrelated processes, including the server itself. Escape the ERE
                // metacharacters first (adds literal backslashes, which pkill's regex engine then reads
                // correctly), THEN shell-quote the result for the single-quoted context.
                if (tmp != null) {
                    val ereEscaped = tmp.replace(Regex("""([.^$*+?()\[\]{}|\\])"""), """\\$1""")
                    fireAndForget("pkill -f '${ereEscaped.replace("'", "'\\''")}'")
                }
                true
            }
            else -> false
        }
    }

    suspend fun retry(jobId: String): MediaJobSnapshot? {
        val row = queries.findById(jobId).executeAsOneOrNull() ?: return null
        if (row.state != "failed" && row.state != "cancelled") return null
        val params = runCatching { json.decodeFromString(MediaJobParams.serializer(), row.params) }.getOrNull() ?: return null
        return when (row.lane) {
            "segments" -> {
                // dedupe_key survives a retry unchanged — a retried job is still "this exact work unit",
                // and the same partial-unique-index guarantee must keep applying to it.
                val key = row.dedupe_key ?: "seg:retry:${row.id}"
                enqueueSegments(row.type, row.media_id, row.label, params, row.file_count.toInt(), key).snapshot
            }
            // Phase 213 — an operator's explicit retry starts a fresh attempt count: FR-213-4's 3-attempt
            // cap governs automatic Jellyfin-timeout backoff, not a deliberate manual "try again".
            "subtitles" -> enqueueSubtitles(row.media_id, row.label, params.copy(subtitleRetryCount = 0)).snapshot
            else -> enqueue(row.type, row.media_id, row.label, params, row.file_count.toInt())
        }
    }

    // ── Read model for the Jobs page ──────────────────────────────────────────

    /** Every currently-running job across all three queues — `media` has at most one, `segments` and
     *  `subtitles` can each have at most one too (FR-213-1's occupancy rule), so this is at most 3 rows. */
    fun running(): List<MediaJobSnapshot> = queries.listRunning().executeAsList().map { toSnapshot(it) }
    fun queued(): List<MediaJobSnapshot> = queries.listQueued().executeAsList().map { toSnapshot(it) }
    fun recent(limit: Int = 20): List<MediaJobSnapshot> = queries.listRecent(limit.toLong()).executeAsList().map { toSnapshot(it) }

    /** Phase 164 (FR-164-6), widened by Phase 213 to three queues — the worker-line summary chips:
     *  busy/running/queued/done-today, split by queue, so the Jobs page can show each queue's own state
     *  independently. `configuredWorkers` now reports the SHARED pool's target on every entry (Phase
     *  213 — it is one number, not sized per queue). */
    fun laneSummaries(): List<LaneSummary> {
        val midnightToday = epochSeconds() - (epochSeconds() % 86_400L)
        val counts = queries.countByLaneState().executeAsList().associate { (it.lane to it.state) to it.n.toInt() }
        val doneToday = queries.countDoneTodayByLane(midnightToday).executeAsList().associate { it.lane to it.n.toInt() }
        val shared = poolTargetWorkers.value
        fun summary(lane: String) = LaneSummary(
            lane = lane,
            runningCount = counts[lane to "running"] ?: 0,
            queuedCount = counts[lane to "queued"] ?: 0,
            doneToday = doneToday[lane] ?: 0,
            configuredWorkers = shared,
        )
        return listOf(summary("media"), summary("segments"), summary("subtitles"))
    }

    /** Phase 213 (FR-213-7) — surfaced on `GET /api/health` as `job_queues`. */
    fun healthSnapshot(): JobQueueHealth {
        val counts = queries.countByLaneState().executeAsList().associate { (it.lane to it.state) to it.n.toInt() }
        val subtitlesQueuedRows = queries.listQueuedByLane("subtitles").executeAsList()
        val deferredByPlayback = subtitlesQueuedRows.isNotEmpty() &&
            subtitlesQueuedRows.all { it.deferWhilePlaying() } &&
            dev.jellystructure.tv.isPlaybackActive()
        val lastFailure = queries.listRecent(50).executeAsList().firstOrNull { it.lane == "subtitles" && it.state == "failed" }?.error
        return JobQueueHealth(
            configuredWorkers = poolTargetWorkers.value,
            activeWorkers = poolActiveWorkers.value,
            mediaQueued = counts["media" to "queued"] ?: 0,
            segmentsQueued = counts["segments" to "queued"] ?: 0,
            subtitlesQueued = counts["subtitles" to "queued"] ?: 0,
            subtitlesDeferredByPlayback = deferredByPlayback,
            subtitlesLastFailure = lastFailure,
        )
    }

    /** Phase 164 (FR-164-8) — `deleteOld` existed in the `.sq` from Phase 109 but was never called from
     *  anywhere; with a segments/subtitles-queue row added per unit of work, the table now grows fast
     *  enough that this needed wiring for real. Called from a daily sweep in Main.kt (same background-
     *  launcher family as the WAL checkpoint). */
    fun pruneOld(olderThanDays: Int = 14) {
        val cutoff = epochSeconds() - olderThanDays * 86_400L
        queries.deleteOld(cutoff)
    }

    // ── Shared worker pool (Phase 213) ──────────────────────────────────────────

    /** Supervises the shared pool's worker count, re-polled live every 500ms (matching
     *  `runPipelineStepPool`'s/the pre-213 segments supervisor's own live-rescale pattern) so a
     *  `behavior.job_workers` change in Settings takes effect on the next dispatch, not a restart.
     *  Workers launched here drain themselves (see [workerLoop]) when scaled down; new ones spin up
     *  live when scaled up. */
    private suspend fun poolSupervisorLoop() {
        poolTargetWorkers.value = currentJobWorkers()
        repeat(poolTargetWorkers.value) { launchWorker() }
        while (true) {
            delay(500)
            val newTarget = currentJobWorkers()
            if (newTarget != poolTargetWorkers.value) {
                Logger.info("job pool: workers ${poolTargetWorkers.value} → $newTarget", "jobs")
                poolTargetWorkers.value = newTarget
            }
            val active = poolActiveWorkers.value
            val target = poolTargetWorkers.value
            if (target > active) {
                repeat(target - active) { launchWorker() }
            }
        }
    }

    // Coerced 1..3: FR-213-1 — a 4th worker could never find a 4th queue to occupy (only three exist).
    private fun currentJobWorkers(): Int = configStore.current.behavior.jobWorkers.coerceIn(1, 3)

    private fun launchWorker() {
        poolActiveWorkers.incrementAndGet()
        // Phase 182 (FR-182-6) — explicit, not inherited: appScope is a stored field, so a plain
        // appScope.launch{} here does NOT pick up poolSupervisorLoop's own BACKGROUND tag (context
        // propagation only flows through the calling coroutine's OWN scope).
        appScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND) { workerLoop() }
    }

    private suspend fun workerLoop() {
        try {
            while (true) {
                if (poolActiveWorkers.value > poolTargetWorkers.value) {
                    Logger.info("job pool: worker draining (scale-down)", "jobs")
                    return
                }
                val row = claimMutex.withLock { claimNext() }
                if (row == null) { delay(1000); continue }
                broadcastSnapshot(row.id)
                runClaimed(row)
            }
        } finally {
            poolActiveWorkers.decrementAndGet()
        }
    }

    /** Must run under [claimMutex]. Looks across the three queues that are not currently occupied
     *  (FR-213-1's rule — `media` also counts as occupied while [bulkRunning], since bulk holds it
     *  exclusively outside this claim path entirely), picks each candidate queue's own oldest ELIGIBLE
     *  row (a deferrable job still yields to the next non-deferrable one in its own queue while a TV
     *  plays — Phase 178's existing per-queue rule, unchanged), then claims the globally oldest of those
     *  candidates by `created_at` — comparing age honestly rather than iterating queues in a fixed order,
     *  so a queue with a steady trickle of new jobs can't starve one holding a single old job (open
     *  question 6 in the phase-213 spec). */
    private fun claimNext(): Media_job? {
        val candidates = mutableListOf<Media_job>()
        for (lane in QUEUE_NAMES) {
            if (lane in occupiedQueues) continue
            if (lane == "media" && bulkRunning) continue
            val queued = queries.listQueuedByLane(lane).executeAsList()
            val playing = if (queued.any { it.deferWhilePlaying() }) dev.jellystructure.tv.isPlaybackActive() else false
            queued.firstOrNull { !playing || !it.deferWhilePlaying() }?.let { candidates += it }
        }
        val chosen = candidates.minByOrNull { it.created_at } ?: return null
        occupiedQueues += chosen.lane
        if (chosen.lane == "media") runningJobId = chosen.id
        queries.markRunning(epochSeconds(), chosen.id)
        return snapshotRowOf(chosen.id) ?: chosen
    }

    private fun snapshotRowOf(id: String): Media_job? = queries.findById(id).executeAsOneOrNull()

    /** Phase 178 §FR-178-2 — a corrupt/unparseable params blob (should never happen; every enqueue path
     *  writes valid JSON) defaults to non-deferrable rather than silently starving the queue. */
    private fun Media_job.deferWhilePlaying(): Boolean =
        runCatching { json.decodeFromString(MediaJobParams.serializer(), params).deferWhilePlaying }.getOrDefault(false)

    /** Runs a claimed job (any of the three queues) and applies its [Outcome] generically: this is what
     *  replaces the old per-lane `runJob`/`runSegmentsJob`'s own duplicated markFinished/broadcast/
     *  cleanup tails. */
    private suspend fun runClaimed(row: Media_job) {
        val outcome = try {
            when (row.lane) {
                "media" -> runMediaJob(row)
                "segments" -> runSegmentsJobBody(row)
                "subtitles" -> runSubtitlesJob(row)
                else -> Failure("Unknown queue '${row.lane}'")
            }
        } catch (e: Exception) {
            Logger.error("job ${row.id} threw: ${e.message}", "jobs")
            Failure(e.message ?: "unexpected error")
        }

        val filesDone = snapshotRowOf(row.id)?.files_done ?: row.files_done
        when (outcome) {
            is Success -> queries.markFinished("done", epochSeconds(), null, filesDone, row.id)
            is Cancelled -> queries.markFinished("cancelled", epochSeconds(), outcome.reason, filesDone, row.id)
            is Failure -> {
                queries.markFinished("failed", epochSeconds(), outcome.reason, filesDone, row.id)
                Logger.warn("job ${row.id} (${row.type} on ${row.media_id}) failed: ${outcome.reason}", "jobs")
            }
            is Requeue -> if (outcome.inPlace) {
                // FR-213-2/213-3 — same row, same id, `created_at` untouched: the job keeps its place in
                // FIFO order instead of jumping to the back on every playback-deferral.
                queries.requeueOne(row.id)
                Logger.info("job ${row.id} (${row.type}) re-queued — ${outcome.reason}", "jobs")
            } else {
                // FR-213-4 — a bounded backoff retry: this row ends terminal, a fresh one is enqueued
                // after the delay (dedupe is free the instant this row is no longer active).
                queries.markFinished("cancelled", epochSeconds(), outcome.reason, filesDone, row.id)
                Logger.info("job ${row.id} (${row.type}) will retry — ${outcome.reason}", "jobs")
                val mediaId = row.media_id; val label = row.label
                val nextParams = outcome.nextParams ?: MediaJobParams()
                appScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND) {
                    delay(outcome.delayMs)
                    enqueueSubtitles(mediaId, label, nextParams)
                }
            }
        }

        if (row.lane == "media") { runningJobId = null; cancelRunning = false }
        cooperativeCancelledIds = cooperativeCancelledIds - row.id
        claimMutex.withLock { occupiedQueues -= row.lane }
        broadcastSnapshot(row.id)
    }

    private sealed class Outcome
    private object Success : Outcome()
    private data class Cancelled(val reason: String? = "Cancelled") : Outcome()
    private data class Failure(val reason: String) : Outcome()
    /** Phase 213 — a job that stopped cleanly mid-way and should run again, either immediately in its
     *  existing queue position ([inPlace], a playback deferral) or as a fresh row after [delayMs] with
     *  [nextParams] carrying an incremented retry count (a Jellyfin-side timeout, FR-213-4). Only ever
     *  produced by the subtitles queue today. */
    private data class Requeue(val inPlace: Boolean, val reason: String, val delayMs: Long = 0, val nextParams: MediaJobParams? = null) : Outcome()

    // ── media queue: reorder / remove / mkv_layout_repair ───────────────────────

    private suspend fun runMediaJob(row: Media_job): Outcome {
        val params = runCatching { json.decodeFromString(MediaJobParams.serializer(), row.params) }.getOrNull()
            ?: return Failure("Corrupt job parameters")
        return when (row.type) {
            "reorder" -> runReorder(row, params)
            "remove" -> runRemove(row, params)
            "mkv_layout_repair" -> runMkvLayoutRepair(row, params)
            else -> Failure("Unknown job type '${row.type}'")
        }
    }

    private suspend fun runReorder(row: Media_job, params: MediaJobParams): Outcome {
        val item = store.resolve(row.media_id) ?: return Failure("Media item no longer exists")
        val kind = when (params.kind) { "audio" -> TrackKind.AUDIO; "subtitle" -> TrackKind.SUBTITLE; else -> null }
            ?: return Failure("Missing/invalid track kind")
        val order = params.order ?: return Failure("Missing target order")

        val targetPath: String
        val tracks: List<dev.jellystructure.model.Track>
        if (params.episodeFilename != null) {
            val ep = item.episodes.firstOrNull { it.filename == params.episodeFilename } ?: return Failure("Episode not found")
            targetPath = ep.path; tracks = ep.tracks
        } else {
            targetPath = item.path; tracks = item.tracks
        }

        guard(targetPath)?.let { return it }

        val orderedTracks = order.mapNotNull { spec -> tracks.firstOrNull { it.specifier == spec } }
        if (orderedTracks.size != order.size) return Failure("One or more track specifiers no longer exist")
        val orderedIndices = orderedTracks.map { it.streamIndex }

        if (!preflightDiskSpace(targetPath)) return Failure("disk_space")

        val cmd = TrackCommandBuilder.ffmpegReorder(targetPath, orderedIndices, kind == TrackKind.AUDIO)
        val duration = FfmpegRunner.probeDurationSeconds(targetPath)
        val ok = FfmpegRunner.runRemuxTracked(targetPath, cmd, duration) { pct, speed, etaSeconds -> onProgress(row.id, pct, speed, etaSeconds) }
        if (cancelRunning) return Cancelled()
        if (!ok) return Failure("ffmpeg remux failed")

        val newTracks = FfprobeRunner.probe(targetPath)
        val newIssue = newTracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
        val fresh = store.resolve(row.media_id) ?: item
        if (params.episodeFilename != null) {
            val epIdx = fresh.episodes.indexOfFirst { it.filename == params.episodeFilename }
            if (epIdx >= 0) {
                val ep = fresh.episodes[epIdx]
                val epResolved = if (kind == TrackKind.AUDIO) primaryAudioLanguage(configStore.current, targetPath, newTracks) else ep.resolvedLanguage
                val updated = fresh.episodes.toMutableList()
                updated[epIdx] = ep.copy(tracks = newTracks, issueCount = newIssue, resolvedLanguage = epResolved)
                val seriesResolved = if (kind == TrackKind.AUDIO) reVoteSeriesLanguage(updated) ?: fresh.resolvedLanguage else fresh.resolvedLanguage
                store.updateOne(fresh.copy(episodes = updated, resolvedLanguage = seriesResolved))
            }
        } else {
            val newResolved = if (kind == TrackKind.AUDIO) primaryAudioLanguage(configStore.current, targetPath, newTracks) else fresh.resolvedLanguage
            store.updateOne(fresh.copy(tracks = newTracks, issueCount = newIssue, resolvedLanguage = newResolved))
        }
        mediaHistory.record(row.media_id, "reorder_tracks", "${params.episodeFilename?.let { "ep=$it " } ?: ""}kind=${params.kind} order=${order.joinToString(",")}")
        postWriteSync(fresh)
        return Success
    }

    private suspend fun runRemove(row: Media_job, params: MediaJobParams): Outcome {
        val item = store.resolve(row.media_id) ?: return Failure("Media item no longer exists")
        val specifier = params.specifier ?: return Failure("Missing target specifier")

        // Phase 144: episode-aware, mirroring runReorder — a series' cover-art-as-video track lives on
        // the individual episode file, not the series-level item.path.
        val targetPath: String
        val tracks: List<dev.jellystructure.model.Track>
        if (params.episodeFilename != null) {
            val ep = item.episodes.firstOrNull { it.filename == params.episodeFilename } ?: return Failure("Episode not found")
            targetPath = ep.path; tracks = ep.tracks
        } else {
            targetPath = item.path; tracks = item.tracks
        }
        val target = tracks.firstOrNull { it.specifier == specifier } ?: return Failure("Track not found")

        guard(targetPath)?.let { return it }

        if (!preflightDiskSpace(targetPath)) return Failure("disk_space")

        val escaped = targetPath.replace("'", "'\\''")
        val escapedTmp = FfmpegRunner.tmpPath(targetPath).replace("'", "'\\''")
        val cmd = "ffmpeg -y -i '$escaped' -map 0 -map -0:${target.streamIndex} -c copy '$escapedTmp' 2>&1 && mv '$escapedTmp' '$escaped'"
        val duration = FfmpegRunner.probeDurationSeconds(targetPath)
        val ok = FfmpegRunner.runRemuxTracked(targetPath, cmd, duration) { pct, speed, etaSeconds -> onProgress(row.id, pct, speed, etaSeconds) }
        if (cancelRunning) return Cancelled()
        if (!ok) return Failure("ffmpeg remux failed")

        val newTracks = FfprobeRunner.probe(targetPath)
        val newIssue = newTracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
        val fresh = store.resolve(row.media_id) ?: item
        if (params.episodeFilename != null) {
            val epIdx = fresh.episodes.indexOfFirst { it.filename == params.episodeFilename }
            if (epIdx >= 0) {
                val updated = fresh.episodes.toMutableList()
                updated[epIdx] = updated[epIdx].copy(tracks = newTracks, issueCount = newIssue)
                store.updateOne(fresh.copy(episodes = updated))
            }
        } else {
            store.updateOne(fresh.copy(tracks = newTracks, issueCount = newIssue))
        }
        mediaHistory.record(row.media_id, "remove_track", "${params.episodeFilename?.let { "ep=$it " } ?: ""}specifier=$specifier")
        postWriteSync(fresh)
        return Success
    }

    /** Phase 201 amendment (2026-09-13) — repairs [MediaJobParams.repairPaths] one file at a time,
     *  reporting `filesDone`/`pct` after each so a many-episode repair shows real progress on the Jobs
     *  page instead of sitting at "running" with no feedback for minutes (the gap that led an operator
     *  to re-click/reload and fire the same repair concurrently — see [MkvLayoutAudit.repair]'s doc).
     *  Running through the `media` queue is itself the fix for that race: no other media-queue job (this
     *  type or otherwise) can be remuxing anything while this one runs. */
    private suspend fun runMkvLayoutRepair(row: Media_job, params: MediaJobParams): Outcome {
        val paths = params.repairPaths?.takeIf { it.isNotEmpty() } ?: return Failure("Missing paths")
        val fixed = mutableSetOf<String>()
        for ((index, path) in paths.withIndex()) {
            if (cancelRunning) return Cancelled()
            if (MkvLayoutAudit.repair(listOf(path))[path] == true) fixed += path
            val done = index + 1
            queries.updateProgress(done.toDouble() / paths.size * 100.0, null, done.toLong(), null, row.id)
            broadcastSnapshot(row.id)
        }
        MkvHealthCache.markRepaired(fixed)
        val failed = paths.size - fixed.size
        mediaHistory.record(row.media_id, "mkv_layout_repair", "fixed=${fixed.size} failed=$failed of ${paths.size}")
        return if (failed == 0) Success else Failure("$failed of ${paths.size} file(s) could not be repaired")
    }

    // ── segments queue: N-concurrent-by-config work, Phase 164, worker count now shared (Phase 213) ────

    private suspend fun runSegmentsJobBody(row: Media_job): Outcome {
        fun isCancelled() = row.id in cooperativeCancelledIds
        val segStore = segmentStore ?: return Failure("Segments store not available")
        val item = store.resolve(row.media_id) ?: return Failure("Media item no longer exists")
        val params = runCatching { json.decodeFromString(MediaJobParams.serializer(), row.params) }.getOrNull() ?: return Failure("Corrupt job parameters")
        if (isCancelled()) return Cancelled()
        return when (row.type) {
            "segments_movie" -> runSegmentsMovie(row, item, params, segStore, isCancelled = ::isCancelled)
            "segments_season" -> runSegmentsSeason(row, item, params, segStore, isCancelled = ::isCancelled)
            "segments_episodes" -> runSegmentsEpisodes(row, item, params, segStore, isCancelled = ::isCancelled)
            else -> Failure("Unknown segments job type '${row.type}'")
        }
    }

    private fun segmentPipelineSettings(): Pair<List<String>, Boolean> {
        val step = configStore.current.scan.pipeline.firstOrNull { it.step == "detect_segments" }
        return (step?.chapterKeywords ?: emptyList()) to (step?.detectFingerprint ?: false)
    }

    private suspend fun segmentsProgress(jobId: String, done: Int, total: Int) {
        val pct = if (total > 0) (done.toDouble() / total * 100.0) else 100.0
        queries.updateProgress(pct, null, done.toLong(), null, jobId)
        broadcastSnapshot(jobId)
    }

    private suspend fun segmentsDetail(jobId: String, filesDone: Int, detail: String?) {
        queries.updateProgress(100.0, detail, filesDone.toLong(), null, jobId)
        broadcastSnapshot(jobId)
    }

    private suspend fun runSegmentsMovie(row: Media_job, item: dev.jellystructure.model.MediaItem, params: MediaJobParams, segmentStore: MediaSegmentStore, isCancelled: () -> Boolean): Outcome {
        val (chapterKeywords, _) = segmentPipelineSettings()
        // A movie's only detection tier is chapter/heuristic — detectSegments' fingerprint tier is
        // gated on MediaKind.TV_SHOW and would be a no-op here anyway; calling this directly (instead
        // of detectSegments) is what gives this job real progress/cancel granularity.
        PipelineStepOps.detectChapterAndHeuristic(item, segmentStore, chapterKeywords, force = params.segmentForce, isCancelled = isCancelled, mediaHistory = mediaHistory) { done, total ->
            segmentsProgress(row.id, done, total)
        }
        return if (isCancelled()) Cancelled() else Success
    }

    private suspend fun runSegmentsSeason(row: Media_job, item: dev.jellystructure.model.MediaItem, params: MediaJobParams, segmentStore: MediaSegmentStore, isCancelled: () -> Boolean): Outcome {
        val season = params.segmentSeason ?: return Failure("Missing season")
        val seasonEpisodes = item.episodes.filter { (it.seasonNumber ?: 0) == season && it.partCount == 1 }
        if (seasonEpisodes.isEmpty()) return Failure("No eligible episodes in this season")
        val (chapterKeywords, detectFingerprint) = segmentPipelineSettings()
        val force = params.segmentForce

        PipelineStepOps.detectChapterAndHeuristic(item.copy(episodes = seasonEpisodes), segmentStore, chapterKeywords, force = force, isCancelled = isCancelled, mediaHistory = mediaHistory) { done, total ->
            segmentsProgress(row.id, done, total)
        }
        if (isCancelled()) return Cancelled()

        if (detectFingerprint && fingerprintService != null && seasonEpisodes.size >= 2) {
            PipelineStepOps.detectIntroFingerprintsForSeason(
                item, segmentStore, fingerprintService, seasonEpisodes, force = force,
                reportDetail = { detail -> segmentsDetail(row.id, seasonEpisodes.size, detail) },
                isCancelled = isCancelled, mediaHistory = mediaHistory,
            )
            if (isCancelled()) return Cancelled()
            // Phase 159 (FR-159-3) — outro/credits counterpart, same season-scoped shape.
            PipelineStepOps.detectOutroFingerprintsForSeason(
                item, segmentStore, fingerprintService, seasonEpisodes, force = force,
                reportDetail = { detail -> segmentsDetail(row.id, seasonEpisodes.size, detail) },
                isCancelled = isCancelled, mediaHistory = mediaHistory,
            )
        }
        return if (isCancelled()) Cancelled() else Success
    }

    /** Episodes redetect deliberately skips the fingerprint tier — it's a pairwise, whole-season
     *  consensus algorithm; dropping the unselected siblings from comparison would degrade the
     *  consensus for everyone, not just narrow the work (matches `SegmentRoutes.kt`'s existing
     *  `redetectEpisodes` behavior, which this job type replaces the direct-launch version of). */
    private suspend fun runSegmentsEpisodes(row: Media_job, item: dev.jellystructure.model.MediaItem, params: MediaJobParams, segmentStore: MediaSegmentStore, isCancelled: () -> Boolean): Outcome {
        val keys = params.segmentEpisodeKeys?.toSet() ?: return Failure("Missing episode selection")
        val selected = item.episodes.filter { it.partCount == 1 && "${it.filename}#${it.episodeNumber}" in keys }
        if (selected.isEmpty()) return Failure("No matching episodes")
        val (chapterKeywords, _) = segmentPipelineSettings()

        PipelineStepOps.detectChapterAndHeuristic(item.copy(episodes = selected), segmentStore, chapterKeywords, force = params.segmentForce, isCancelled = isCancelled, mediaHistory = mediaHistory) { done, total ->
            segmentsProgress(row.id, done, total)
        }
        return if (isCancelled()) Cancelled() else Success
    }

    // ── subtitles queue: prewarm_subtitles, Phase 213 ───────────────────────────

    /** One job = one movie/series' full subtitle pre-warm. See [PipelineStepOps.prewarmSubtitles] for
     *  the per-stream playback/cancel checks and the timeout-abandons-the-rest-of-the-list behaviour
     *  (FR-213-3/213-4) — this function only translates its outcome into a queue [Outcome]. */
    private suspend fun runSubtitlesJob(row: Media_job): Outcome {
        fun isCancelled() = row.id in cooperativeCancelledIds
        val item = store.resolve(row.media_id) ?: return Failure("Media item no longer exists")
        val params = runCatching { json.decodeFromString(MediaJobParams.serializer(), row.params) }.getOrNull() ?: return Failure("Corrupt job parameters")
        if (isCancelled()) return Cancelled()

        var warmedCount = 0
        val outcome = PipelineStepOps.prewarmSubtitles(
            item, jellyfinClient, configStore.current,
            onStreamWarmed = { warmedCount++ },
            isCancelled = ::isCancelled,
        )
        return when (outcome) {
            is PipelineStepOps.PrewarmOutcome.Warmed -> {
                Logger.info("prewarm_subtitles: ${outcome.count} stream(s) warmed for '${item.title}'", "jobs")
                Success
            }
            PipelineStepOps.PrewarmOutcome.Skipped -> Success
            PipelineStepOps.PrewarmOutcome.LookupFailed -> Failure("Could not read this item's subtitle stream list from Jellyfin")
            PipelineStepOps.PrewarmOutcome.Cancelled -> Cancelled()
            is PipelineStepOps.PrewarmOutcome.Deferred ->
                Requeue(inPlace = true, reason = "a TV started playing (${outcome.warmedSoFar} already warmed)")
            is PipelineStepOps.PrewarmOutcome.TimedOut -> {
                val attempt = params.subtitleRetryCount + 1
                if (attempt > 3) {
                    Failure("Jellyfin is still busy on this file after 3 attempts — giving up")
                } else {
                    val backoffMinutes = attempt * 5
                    Requeue(
                        inPlace = false,
                        reason = "Jellyfin busy on this file — retrying in $backoffMinutes min (attempt $attempt/3)",
                        delayMs = backoffMinutes * 60_000L,
                        nextParams = params.copy(subtitleRetryCount = attempt),
                    )
                }
            }
        }
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    private suspend fun postWriteSync(item: dev.jellystructure.model.MediaItem) {
        val cfg = configStore.current
        if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
            // Bug fix (live report, 2026-08-16) — this ran after a reorder/remove job had just physically
            // remuxed the file (its actual stream layout on disk changed), but used the default
            // ValidationOnly mode, which "skips the re-read if the item was recently refreshed"
            // (JellyfinClient.refreshItem's own doc). A remux is exactly the case that must force a
            // real re-read regardless of recency — otherwise Jellyfin's cached MediaStreams can stay
            // stale relative to the new on-disk layout until some unrelated later full sync catches up.
            jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)
        }
        arrRescan?.nudge(item)
    }

    private fun reVoteSeriesLanguage(episodes: List<dev.jellystructure.model.Episode>): String? {
        val votes = mutableMapOf<String, Int>()
        for (e in episodes) {
            e.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.language
                ?.let { LanguageResolver.normalize(it) }
                ?.let { votes[it] = (votes[it] ?: 0) + 1 }
        }
        return votes.maxByOrNull { it.value }?.key
    }

    /** Null = the seeding guard passed; non-null = the [Failure] to short-circuit the job with. */
    private suspend fun guard(path: String): Outcome? = when (val g = seedingGuard.check(path, configStore.current)) {
        is SeedingCheckResult.Blocked -> Failure("File is seeded by '${g.torrentName}'")
        is SeedingCheckResult.Unreachable -> Failure("qBittorrent unreachable: ${g.reason}")
        else -> null
    }

    private fun onProgress(jobId: String, pct: Double, speed: String?, etaSeconds: Long?) {
        queries.updateProgress(pct, speed, 0, etaSeconds, jobId)
        kotlinx.coroutines.runBlocking { broadcastSnapshot(jobId) }
    }

    /** Preflight (FR B.3): refuse to start unless free space on the target filesystem ≥ source size +
     *  10% — the temp copy is a second full-size file living alongside the original during the remux. */
    private suspend fun preflightDiskSpace(filePath: String): Boolean {
        val dir = filePath.substringBeforeLast('/', ".")
        val sourceBytes = fileSizeBytes(filePath) ?: return true  // can't stat the source — don't block on it
        val escapedDir = dir.replace("'", "'\\''")
        val out = shellCapture("df -B1 --output=avail '$escapedDir' 2>/dev/null | tail -n1")
        val availBytes = out?.trim()?.toLongOrNull() ?: return true  // df unavailable/unparseable — don't block
        val required = (sourceBytes * 1.1).toLong()
        if (availBytes < required) {
            Logger.warn("disk preflight failed for '$filePath': need ~${required / 1_048_576}MB, have ${availBytes / 1_048_576}MB free", "jobs")
            return false
        }
        return true
    }

    private fun fileSizeBytes(filePath: String): Long? {
        val escaped = filePath.replace("'", "'\\''")
        val out = shellCapture("stat -c '%s' '$escaped' 2>/dev/null")
        return out?.trim()?.toLongOrNull()
    }

    private suspend fun tmpFileFor(row: Media_job): String? {
        val item = store.resolve(row.media_id) ?: return null
        val params = runCatching { json.decodeFromString(MediaJobParams.serializer(), row.params) }.getOrNull() ?: return null
        val path = if (params.episodeFilename != null)
            item.episodes.firstOrNull { it.filename == params.episodeFilename }?.path
        else item.path
        return path?.let { FfmpegRunner.tmpPath(it) }
    }

    private fun snapshotOf(id: String): MediaJobSnapshot? = queries.findById(id).executeAsOneOrNull()?.let { toSnapshot(it) }

    private suspend fun broadcastSnapshot(id: String) {
        snapshotOf(id)?.let { broadcaster.broadcast(JobEvent.MediaJobUpdate(it)) }
    }

    private fun toSnapshot(row: Media_job): MediaJobSnapshot = MediaJobSnapshot(
        id = row.id, type = row.type, mediaId = row.media_id, label = row.label, state = row.state,
        enqueuedBy = row.enqueued_by, createdAt = row.created_at, startedAt = row.started_at,
        finishedAt = row.finished_at, error = row.error, fileCount = row.file_count.toInt(),
        filesDone = row.files_done.toInt(), pct = row.pct, speed = row.speed, etaSeconds = row.eta_seconds,
        lane = row.lane,
    )

    @OptIn(ExperimentalForeignApi::class)
    private fun fireAndForget(cmd: String) {
        val pipe = popen("$cmd >/dev/null 2>&1 &", "r") ?: return
        pclose(pipe)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun shellCapture(cmd: String): String? = memScoped {
        val pipe = popen(cmd, "r") ?: return null
        val sb = StringBuilder()
        val buf = allocArray<ByteVar>(4096)
        while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
        pclose(pipe)
        sb.toString()
    }

    private fun epochSeconds(): Long = dev.jellystructure.nowEpochSec()

    private companion object {
        val QUEUE_NAMES = listOf("media", "segments", "subtitles")
    }
}

// Concurrent HTTP handlers can call enqueue() at the same time — AtomicInt (not a plain var) keeps the
// id suffix collision-free under real concurrency, matching the AtomicInt usage in the scan worker pool.
private val jobSeq = kotlin.concurrent.AtomicInt(0)
private fun genId(): String = "${dev.jellystructure.nowEpochSec()}-${jobSeq.incrementAndGet()}"
