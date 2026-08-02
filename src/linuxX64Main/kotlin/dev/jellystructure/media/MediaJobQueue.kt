package dev.jellystructure.media

import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.Media_job
import dev.jellystructure.jobs.JobEvent
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

    /** Boots the worker: any row left `running` from a prior crash/restart is re-queued (the temp copy,
     *  if any, is simply overwritten or ignored on the retry — the original file was never touched). */
    fun start() {
        queries.requeueRunning()
        appScope.launch(dispatcher) { workerLoop() }
    }

    fun isBusy(): Boolean = runningJobId != null || bulkRunning

    // ── Enqueue / cancel / retry ──────────────────────────────────────────────

    suspend fun enqueue(type: String, mediaId: String, label: String, params: MediaJobParams, fileCount: Int = 1): MediaJobSnapshot {
        val id = "mj-${genId()}"
        queries.insert(id, type, mediaId, label, json.encodeToString(MediaJobParams.serializer(), params), "queued", "admin", epochSeconds(), fileCount.toLong())
        val snap = snapshotOf(id)!!
        broadcaster.broadcast(JobEvent.MediaJobUpdate(snap))
        return snap
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

    /** Cancels a queued job outright, or best-effort kills a running one (by matching its unique temp
     *  output path in the process list — see class doc on why this is simpler and safer than tracking a
     *  PID through a nested shell/nice/ionice invocation). Returns false if the job wasn't cancellable. */
    suspend fun cancel(jobId: String): Boolean {
        val row = queries.findById(jobId).executeAsOneOrNull() ?: return false
        return when (row.state) {
            "queued" -> {
                queries.markFinished("cancelled", epochSeconds(), "Cancelled before it started", row.files_done, jobId)
                broadcastSnapshot(jobId)
                true
            }
            "running" -> {
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
        return enqueue(row.type, row.media_id, row.label, params, row.file_count.toInt())
    }

    // ── Read model for the Jobs page ──────────────────────────────────────────

    fun running(): MediaJobSnapshot? = queries.listRunning().executeAsList().firstOrNull()?.let { toSnapshot(it) }
    fun queued(): List<MediaJobSnapshot> = queries.listQueued().executeAsList().map { toSnapshot(it) }
    fun recent(limit: Int = 20): List<MediaJobSnapshot> = queries.listRecent(limit.toLong()).executeAsList().map { toSnapshot(it) }
    fun doneToday(): Int {
        val midnightToday = epochSeconds() - (epochSeconds() % 86_400L)
        return queries.countDoneToday(midnightToday).executeAsOne().toInt()
    }

    // ── Worker loop ────────────────────────────────────────────────────────────

    private suspend fun workerLoop() {
        while (true) {
            if (bulkRunning) { delay(500); continue }
            val next = queries.listQueued().executeAsList().firstOrNull()
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

    private suspend fun postWriteSync(item: dev.jellystructure.model.MediaItem) {
        val cfg = configStore.current
        if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
            jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
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
