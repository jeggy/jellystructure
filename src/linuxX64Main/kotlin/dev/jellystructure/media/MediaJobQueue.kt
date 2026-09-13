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
 * Phase 109 — a persistent, single-worker FIFO queue for heavy media edits (ffmpeg remuxes: track
 * reorder/remove). Applying one of these used to run ffmpeg *inline on the request thread*, occupying
 * a Ktor CIO worker for the whole remux (minutes for 4K) and starving the server under any concurrent
 * traffic. Enqueue returns immediately; a dedicated single-concurrency dispatcher drains the queue one
 * job at a time so the request path, and the server's own responsiveness, are never blocked by ffmpeg.
 *
 * The bulk-reorder wizard (Phase 96) already runs off-request via its own `appScope.launch`; rather
 * than re-architect its well-exercised per-episode logic into individual queue rows, it registers one
 * `media_job` row for visibility on the Jobs page and takes [acquireBulkSlot] before starting — the same
 * mutual-exclusion the single-file jobs get, so a bulk run and a single reorder can never remux in
 * parallel and double the disk I/O.
 */
/** Phase 164 — [enqueueSegments]'s return: the snapshot plus whether this call actually inserted a new
 *  row or found an existing active one under the same [MediaJobSnapshot.id]'s dedupe key instead. */
data class SegmentEnqueueResult(val snapshot: MediaJobSnapshot, val deduped: Boolean)

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
    // Phase 164 — the segments lane's own dependencies. Nullable so this class stays constructible
    // (and the media lane fully functional) in any test/bootstrap context that doesn't wire segments.
    private val segmentStore: MediaSegmentStore? = null,
    private val fingerprintService: FingerprintService? = null,
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val json = Json { ignoreUnknownKeys = true }
    private val queries get() = db.mediaJobQueries

    @Volatile private var runningJobId: String? = null
    @Volatile private var cancelRunning = false
    @Volatile private var bulkRunning = false
    // Guards the check-then-set on bulkRunning below — two admins submitting a bulk reorder at the same
    // instant must not both observe bulkRunning=false and both start (a plain @Volatile flag alone races).
    private val bulkClaimMutex = kotlinx.coroutines.sync.Mutex()

    // Phase 164 — the segments lane: concurrency > 1 (behavior.segment_workers), unlike the media lane's
    // FIFO-1. targetWorkers is re-polled live by segmentsSupervisorLoop (matching runPipelineStepPool's
    // own live-rescale pattern) so a Settings change takes effect on the next dispatch, not a restart.
    // AtomicInt (not a plain @Volatile var) — unlike runningJobId/cancelRunning above (only ever written
    // by one coroutine at a time), segmentsActiveWorkers is decremented by up to N worker coroutines
    // exiting concurrently; @Volatile only guarantees visibility, not atomicity of `--`'s read-modify-
    // write, so concurrent decrements could lose updates. Matches ScanTracker.activeWorkers/targetWorkers'
    // own AtomicInt usage for the identical pattern (PipelineStepPool.kt).
    private val segmentsActiveWorkers = kotlin.concurrent.AtomicInt(0)
    private val segmentsTargetWorkers = kotlin.concurrent.AtomicInt(1)
    // segmentsClaimMutex serializes "pick the next queued row + mark it running" across the N concurrent
    // workers — without it, two workers could both read the same queued row before either claims it.
    private val segmentsClaimMutex = kotlinx.coroutines.sync.Mutex()
    // Cooperative cancel (FR-164-5) — there is no temp file to pkill for a segments job (it's ffmpeg/
    // fpcalc calls reading, never writing, the source file), so cancellation is a per-job-id flag the
    // running worker's PipelineStepOps isCancelled callback polls between episodes. @Volatile + whole-set
    // replacement (never mutated in place) gives safe publication across worker threads without a mutex
    // on the hot read path, matching this class's existing cancelRunning/runningJobId convention.
    @Volatile private var segmentsCancelledIds: Set<String> = emptySet()

    /** Boots the workers: any row left `running` from a prior crash/restart is re-queued (a media-lane
     *  row's temp copy, if any, is simply overwritten or ignored on the retry — the original file was
     *  never touched; a segments-lane row is idempotent to re-run outright). */
    fun start() {
        queries.requeueRunning()
        // Phase 182 (FR-182-6) — background work (ffmpeg remuxes, segment detection), never request-
        // serving; tags this coroutine and everything launched under it so OutboundHttp/ProcessGate
        // reserve interactive capacity it can never consume. See GateClass's own doc.
        appScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND + dispatcher) { workerLoop() }
        appScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND) { segmentsSupervisorLoop() }
    }

    fun isBusy(): Boolean = runningJobId != null || bulkRunning

    // ── Enqueue / cancel / retry ──────────────────────────────────────────────

    suspend fun enqueue(type: String, mediaId: String, label: String, params: MediaJobParams, fileCount: Int = 1): MediaJobSnapshot {
        val id = "mj-${genId()}"
        queries.insert(id, type, mediaId, label, json.encodeToString(MediaJobParams.serializer(), params), "queued", "admin", epochSeconds(), fileCount.toLong(), "media", null)
        val snap = snapshotOf(id)!!
        broadcaster.broadcast(JobEvent.MediaJobUpdate(snap))
        return snap
    }

    /** Phase 164 (FR-164-1/7) — the segments lane's own enqueue: dedup-aware via the partial unique
     *  index on `dedupe_key` (`media_job_dedupe_active`), not a check-then-insert (two concurrent
     *  enqueues — a pipeline run and an operator clicking "detect again" — must not both observe "not
     *  present" and both insert). A unique-constraint violation on the insert is not an error: it means
     *  this exact work unit is already queued or running, and [SegmentEnqueueResult.deduped] tells the
     *  caller so (the redetect endpoint uses it for "already queued" vs "queued" toast copy). */
    suspend fun enqueueSegments(type: String, mediaId: String, label: String, params: MediaJobParams, fileCount: Int, dedupeKey: String): SegmentEnqueueResult {
        val id = "mj-${genId()}"
        val inserted = runCatching {
            queries.insert(id, type, mediaId, label, json.encodeToString(MediaJobParams.serializer(), params), "queued", "admin", epochSeconds(), fileCount.toLong(), "segments", dedupeKey)
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
            createdAt = epochSeconds(), fileCount = fileCount, lane = "segments",
        )
        return SegmentEnqueueResult(snap, deduped = true)
    }

    /** Registers a job row for a bulk-reorder run that executes via its own existing code path — see
     *  the class doc. Caller is responsible for [acquireBulkSlot]/[markBulkFinished] around the run. */
    suspend fun registerBulkJob(mediaId: String, label: String, params: MediaJobParams, fileCount: Int): MediaJobSnapshot =
        enqueue("bulk_reorder", mediaId, label, params, fileCount)

    /** Blocks (politely) until no single-file job is running and no other bulk run is in flight, then
     *  claims the slot. Mirrors the single-worker guarantee for the bulk path without routing its
     *  per-episode logic through [runJob]. */
    suspend fun acquireBulkSlot(jobId: String) {
        bulkClaimMutex.lock()
        try {
            while (runningJobId != null || bulkRunning) delay(500)
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

    /** Cancels a queued job outright, or best-effort stops a running one. The media lane kills its
     *  ffmpeg child by matching its unique temp output path in the process list (see class doc on why
     *  that's simpler/safer than tracking a PID through a nested shell/nice/ionice invocation). The
     *  segments lane has no such child to kill (its ffmpeg/fpcalc calls only ever READ the source file)
     *  — cancellation there is cooperative (FR-164-5): the job stops after the episode currently in
     *  flight, not instantly. Returns false if the job wasn't cancellable. */
    suspend fun cancel(jobId: String): Boolean {
        val row = queries.findById(jobId).executeAsOneOrNull() ?: return false
        return when (row.state) {
            "queued" -> {
                queries.markFinished("cancelled", epochSeconds(), "Cancelled before it started", row.files_done, jobId)
                broadcastSnapshot(jobId)
                true
            }
            "running" -> {
                if (row.lane == "segments") {
                    segmentsCancelledIds = segmentsCancelledIds + jobId
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
        return if (row.lane == "segments") {
            // dedupe_key survives a retry unchanged — a retried job is still "this exact work unit",
            // and the same partial-unique-index guarantee must keep applying to it.
            val key = row.dedupe_key ?: "seg:retry:${row.id}"
            enqueueSegments(row.type, row.media_id, row.label, params, row.file_count.toInt(), key).snapshot
        } else {
            enqueue(row.type, row.media_id, row.label, params, row.file_count.toInt())
        }
    }

    // ── Read model for the Jobs page ──────────────────────────────────────────

    /** Every currently-running job across BOTH lanes — the media lane has at most one (its FIFO-1
     *  guarantee), the segments lane can have up to `behavior.segment_workers`. */
    fun running(): List<MediaJobSnapshot> = queries.listRunning().executeAsList().map { toSnapshot(it) }
    fun queued(): List<MediaJobSnapshot> = queries.listQueued().executeAsList().map { toSnapshot(it) }
    fun recent(limit: Int = 20): List<MediaJobSnapshot> = queries.listRecent(limit.toLong()).executeAsList().map { toSnapshot(it) }

    /** Phase 164 (FR-164-6) — the two worker-line summary chips: busy/running/queued/done-today, split
     *  by lane, so the Jobs page can show "Media worker" and "Segment detection" as independent lines
     *  rather than one combined (and therefore misleading, given the very different concurrency models)
     *  count. */
    fun laneSummaries(): List<LaneSummary> {
        val midnightToday = epochSeconds() - (epochSeconds() % 86_400L)
        val counts = queries.countByLaneState().executeAsList().associate { (it.lane to it.state) to it.n.toInt() }
        val doneToday = queries.countDoneTodayByLane(midnightToday).executeAsList().associate { it.lane to it.n.toInt() }
        fun summary(lane: String, configuredWorkers: Int) = LaneSummary(
            lane = lane,
            runningCount = counts[lane to "running"] ?: 0,
            queuedCount = counts[lane to "queued"] ?: 0,
            doneToday = doneToday[lane] ?: 0,
            configuredWorkers = configuredWorkers,
        )
        return listOf(
            summary("media", 1),
            summary("segments", segmentsTargetWorkers.value),
        )
    }

    /** Phase 164 (FR-164-8) — `deleteOld` existed in the `.sq` from Phase 109 but was never called from
     *  anywhere; with a segments-lane row added per season per full run, the table now grows fast enough
     *  that this needed wiring for real. Called from a daily sweep in Main.kt (same background-launcher
     *  family as the WAL checkpoint). */
    fun pruneOld(olderThanDays: Int = 14) {
        val cutoff = epochSeconds() - olderThanDays * 86_400L
        queries.deleteOld(cutoff)
    }

    // ── Media lane worker loop (unchanged concurrency-1 FIFO) ───────────────────

    private suspend fun workerLoop() {
        while (true) {
            if (bulkRunning) { delay(500); continue }
            // Phase 164: listQueued() is now COMBINED across both lanes (feeds the Jobs page's one
            // interleaved queue view) — this lane must only ever pick up its own "media" rows.
            val next = queries.listQueuedByLane("media").executeAsList().firstOrNull()
            if (next == null) { delay(1500); continue }
            runJob(next)
        }
    }

    private suspend fun runJob(row: Media_job) {
        runningJobId = row.id
        cancelRunning = false
        queries.markRunning(epochSeconds(), row.id)
        broadcastSnapshot(row.id)

        val outcome = try {
            val params = runCatching { json.decodeFromString(MediaJobParams.serializer(), row.params) }.getOrNull()
            when {
                params == null -> Failure("Corrupt job parameters")
                row.type == "reorder" -> runReorder(row, params)
                row.type == "remove" -> runRemove(row, params)
                row.type == "mkv_layout_repair" -> runMkvLayoutRepair(row, params)
                else -> Failure("Unknown job type '${row.type}'")
            }
        } catch (e: Exception) {
            Logger.error("media job ${row.id} threw: ${e.message}", "jobs")
            Failure(e.message ?: "unexpected error")
        }

        when (outcome) {
            is Success -> queries.markFinished("done", epochSeconds(), null, row.file_count, row.id)
            is Cancelled -> queries.markFinished("cancelled", epochSeconds(), "Cancelled", row.files_done, row.id)
            is Failure -> {
                queries.markFinished("failed", epochSeconds(), outcome.reason, row.files_done, row.id)
                Logger.warn("media job ${row.id} (${row.type} on ${row.media_id}) failed: ${outcome.reason}", "jobs")
            }
        }
        broadcastSnapshot(row.id)
        runningJobId = null
    }

    // ── Segments lane: N-concurrent worker pool (Phase 164) ─────────────────────

    /** Supervises the segments lane's worker count, re-polled live every 500ms (matching
     *  `runPipelineStepPool`'s own live-rescale pattern) so a `behavior.segment_workers` change in
     *  Settings takes effect on the next dispatch, not a restart. Workers launched here drain
     *  themselves (see [segmentsWorkerLoop]) when scaled down; new ones spin up live when scaled up. */
    private suspend fun segmentsSupervisorLoop() {
        segmentsTargetWorkers.value = currentSegmentWorkers()
        repeat(segmentsTargetWorkers.value) { launchSegmentsWorker() }
        while (true) {
            delay(500)
            val newTarget = currentSegmentWorkers()
            if (newTarget != segmentsTargetWorkers.value) {
                Logger.info("segments lane: workers ${segmentsTargetWorkers.value} → $newTarget", "jobs")
                segmentsTargetWorkers.value = newTarget
            }
            val active = segmentsActiveWorkers.value
            val target = segmentsTargetWorkers.value
            if (target > active) {
                repeat(target - active) { launchSegmentsWorker() }
            }
        }
    }

    private fun currentSegmentWorkers(): Int = configStore.current.behavior.segmentWorkers.coerceIn(1, 8)

    private fun launchSegmentsWorker() {
        segmentsActiveWorkers.incrementAndGet()
        // Phase 182 (FR-182-6) — explicit, not inherited: appScope is a stored field, so a plain
        // appScope.launch{} here does NOT pick up segmentsSupervisorLoop's own BACKGROUND tag (context
        // propagation only flows through the calling coroutine's OWN scope, e.g. coroutineScope{}'s
        // receiver — never through a captured CoroutineScope field).
        appScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND) { segmentsWorkerLoop() }
    }

    private suspend fun segmentsWorkerLoop() {
        try {
            while (true) {
                if (segmentsActiveWorkers.value > segmentsTargetWorkers.value) {
                    Logger.info("segments lane: worker draining (scale-down)", "jobs")
                    return
                }
                // segmentsClaimMutex: see its declaration doc — atomically "pick next queued + mark
                // running" so two concurrent workers can never claim the same row.
                val row = segmentsClaimMutex.withLock {
                    val queued = queries.listQueuedByLane("segments").executeAsList()
                    // Phase 178 §FR-178-2 — a deferrable job (MediaJobParams.deferWhilePlaying) is
                    // skipped while a TV is playing, in FIFO order otherwise: the first candidate that
                    // either isn't deferrable or finds no active playback wins, so a non-deferrable job
                    // (an operator's explicit "detect again") queued behind a deferred one still runs.
                    val playing = if (queued.any { it.deferWhilePlaying() }) dev.jellystructure.tv.isPlaybackActive() else false
                    val next = queued.firstOrNull { !playing || !it.deferWhilePlaying() } ?: return@withLock null
                    queries.markRunning(epochSeconds(), next.id)
                    next
                }
                if (row == null) { delay(1500); continue }
                broadcastSnapshot(row.id)
                runSegmentsJob(row)
            }
        } finally {
            segmentsActiveWorkers.decrementAndGet()
        }
    }

    /** Phase 178 §FR-178-2 — a corrupt/unparseable params blob (should never happen; every enqueue path
     *  writes valid JSON) defaults to non-deferrable rather than silently starving the queue. */
    private fun Media_job.deferWhilePlaying(): Boolean =
        runCatching { json.decodeFromString(MediaJobParams.serializer(), params).deferWhilePlaying }.getOrDefault(false)

    private suspend fun runSegmentsJob(row: Media_job) {
        fun isCancelled() = row.id in segmentsCancelledIds
        val segStore = segmentStore
        val item = store.resolve(row.media_id)
        val params = runCatching { json.decodeFromString(MediaJobParams.serializer(), row.params) }.getOrNull()

        val outcome = try {
            when {
                segStore == null -> Failure("Segments store not available")
                item == null -> Failure("Media item no longer exists")
                params == null -> Failure("Corrupt job parameters")
                isCancelled() -> Cancelled
                row.type == "segments_movie" -> runSegmentsMovie(row, item, params, segStore, isCancelled = ::isCancelled)
                row.type == "segments_season" -> runSegmentsSeason(row, item, params, segStore, isCancelled = ::isCancelled)
                row.type == "segments_episodes" -> runSegmentsEpisodes(row, item, params, segStore, isCancelled = ::isCancelled)
                else -> Failure("Unknown segments job type '${row.type}'")
            }
        } catch (e: Exception) {
            Logger.error("segments job ${row.id} threw: ${e.message}", "jobs")
            Failure(e.message ?: "unexpected error")
        }

        val fresh = queries.findById(row.id).executeAsOneOrNull() ?: row
        when (outcome) {
            is Success -> queries.markFinished("done", epochSeconds(), null, fresh.files_done, row.id)
            is Cancelled -> queries.markFinished("cancelled", epochSeconds(), "Cancelled after the current episode", fresh.files_done, row.id)
            is Failure -> {
                queries.markFinished("failed", epochSeconds(), outcome.reason, fresh.files_done, row.id)
                Logger.warn("segments job ${row.id} (${row.type} on ${row.media_id}) failed: ${outcome.reason}", "jobs")
            }
        }
        segmentsCancelledIds = segmentsCancelledIds - row.id
        broadcastSnapshot(row.id)
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
        return if (isCancelled()) Cancelled else Success
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
        if (isCancelled()) return Cancelled

        if (detectFingerprint && fingerprintService != null && seasonEpisodes.size >= 2) {
            PipelineStepOps.detectIntroFingerprintsForSeason(
                item, segmentStore, fingerprintService, seasonEpisodes, force = force,
                reportDetail = { detail -> segmentsDetail(row.id, seasonEpisodes.size, detail) },
                isCancelled = isCancelled, mediaHistory = mediaHistory,
            )
            if (isCancelled()) return Cancelled
            // Phase 159 (FR-159-3) — outro/credits counterpart, same season-scoped shape.
            PipelineStepOps.detectOutroFingerprintsForSeason(
                item, segmentStore, fingerprintService, seasonEpisodes, force = force,
                reportDetail = { detail -> segmentsDetail(row.id, seasonEpisodes.size, detail) },
                isCancelled = isCancelled, mediaHistory = mediaHistory,
            )
        }
        return if (isCancelled()) Cancelled else Success
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
        return if (isCancelled()) Cancelled else Success
    }

    private sealed class Outcome
    private object Success : Outcome()
    private object Cancelled : Outcome()
    private data class Failure(val reason: String) : Outcome()

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
        if (cancelRunning) return Cancelled
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
        if (cancelRunning) return Cancelled
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
     *  Running through this queue's single-worker media lane is itself the fix for that race: no other
     *  media-lane job (this type or otherwise) can be remuxing anything while this one runs. */
    private suspend fun runMkvLayoutRepair(row: Media_job, params: MediaJobParams): Outcome {
        val paths = params.repairPaths?.takeIf { it.isNotEmpty() } ?: return Failure("Missing paths")
        val fixed = mutableSetOf<String>()
        for ((index, path) in paths.withIndex()) {
            if (cancelRunning) return Cancelled
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
}

// Concurrent HTTP handlers can call enqueue() at the same time — AtomicInt (not a plain var) keeps the
// id suffix collision-free under real concurrency, matching the AtomicInt usage in the scan worker pool.
private val jobSeq = kotlin.concurrent.AtomicInt(0)
private fun genId(): String = "${dev.jellystructure.nowEpochSec()}-${jobSeq.incrementAndGet()}"
