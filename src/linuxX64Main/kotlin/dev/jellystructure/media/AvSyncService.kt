package dev.jellystructure.media

import dev.jellystructure.OutboundHttp
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.File_av_sync
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.filefix.FileFixCommands
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.nowEpochSec
import dev.jellystructure.resolver.LanguageResolver
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 317 (FR-317-7, 255's [TrackCoverageFlags]) — the process-wide handle Triage and the Library
 * filter read. [revision] bumps on every stored finding AND whenever the library's zero moves, so a count
 * cached against the library version alone cannot outlive either.
 */
object AvSyncFlags {
    var service: AvSyncService? = null
    private val rev = kotlin.concurrent.AtomicInt(0)
    val revision: Int get() = rev.value
    internal fun bump() { rev.incrementAndGet() }

    /** Currently-flagged paths with their reading (`null` = no service wired or the check is off — never
     *  an empty map pretending "clean"). */
    fun flaggedOrNull(): Map<String, AvSyncReading>? = service?.takeIf { it.enabled() }?.flaggedPaths()

    const val TYPE_AV_OFFSET = "av_offset"
    const val TYPE_AV_DRIFT = "av_drift"
    /** FR-317-9 — the *Since your last visit* record for a file newly found out of step. */
    const val HISTORY_ACTION = "av_sync_found"
}

/** FR-317-2 — the sidecar's `/health`. */
@Serializable
data class AvSyncHealthDto(
    val ok: Boolean = false,
    val gpu: String = "",
    @SerialName("vram_total_gb") val vramTotalGb: Double = 0.0,
    val model: String = "",
    val busy: Boolean = false,
)

/** FR-317-2 — the sidecar's `/stat`: the mount check's whole answer. */
@Serializable
data class AvSyncStatDto(val exists: Boolean = false, val size: Long = 0)

/** FR-317-13 — what the Settings card says, in the server's own words. */
@Serializable
data class AvSyncSettingsDto(
    val enabled: Boolean,
    val reachable: Boolean,
    val status: String,
    val model: String = "",
    /** Library roots the sidecar cannot see, as the operator would have to fix them. */
    @SerialName("unseen_roots") val unseenRoots: List<String> = emptyList(),
)

/** FR-317-10 — one window as the title page's folded evidence shows it. */
@Serializable
data class AvSyncWindowDto(
    @SerialName("at_ms") val atMs: Long,
    @SerialName("offset_ms") val offsetMs: Long?,
    val conf: Double,
    val frames: Int,
    val confident: Boolean,
)

/** FR-317-10 — one flagged (or skipped) file of a title. */
@Serializable
data class AvSyncFileDto(
    val path: String,
    val state: String,
    @SerialName("offset_ms") val offsetMs: Long? = null,
    @SerialName("drift_from_ms") val driftFromMs: Long? = null,
    @SerialName("drift_to_ms") val driftToMs: Long? = null,
    @SerialName("drift_from_ms_at") val driftFromMsAt: Long? = null,
    @SerialName("drift_to_ms_at") val driftToMsAt: Long? = null,
    val reason: String? = null,
    @SerialName("reason_label") val reasonLabel: String? = null,
    @SerialName("checked_at") val checkedAt: Long? = null,
    val season: Int? = null,
    /** The measured track's specifier, its own stream index (what the Tracks tab's rows carry) and the
     *  mark its row gets (*160 ms early*). */
    val audio: String? = null,
    @SerialName("audio_stream_index") val audioStreamIndex: Int? = null,
    @SerialName("audio_mark") val audioMark: String? = null,
    val what: String? = null,
    val experience: String? = null,
    val who: String? = null,
    val suggestion: String? = null,
    val command: String? = null,
    /** FR-317-24 — the one sentence that says why there is no command, where there is none to give.
     *  Additive and nullable: a reader that does not know the field is unaffected. */
    @SerialName("no_command_reason") val noCommandReason: String? = null,
    val shifted: List<String> = emptyList(),
    val left: List<String> = emptyList(),
    val windows: List<AvSyncWindowDto> = emptyList(),
)

@Serializable
data class AvSyncStatusDto(
    val enabled: Boolean,
    val total: Int,
    @SerialName("in_sync") val inSync: Int,
    val unchecked: Int,
    val unknown: Int,
    @SerialName("zero_frames") val zeroFrames: Int,
    val files: List<AvSyncFileDto> = emptyList(),
)

@Serializable
private data class MeasureRequest(
    val path: String,
    val audio: String,
    val others: List<String> = emptyList(),
    val windows: List<MeasureWindow> = emptyList(),
)

@Serializable
private data class StatRequest(val path: String)

@Serializable
private data class MeasureWindow(@SerialName("start_s") val startS: Double, @SerialName("dur_s") val durS: Double)

@Serializable
private data class MeasureResponse(
    val windows: List<AvSyncWindow> = emptyList(),
    val others: List<AvSyncOther> = emptyList(),
    val decode: String = "",
    val seconds: Double = 0.0,
)

/**
 * Phase 317 — sound that runs ahead of (or behind) the picture: measured (by the sidecar, on GPU 1),
 * stored raw, and explained. 255's [TrackCoverageService] shape throughout; the one new thing is that the
 * measurement is an HTTP call to a process with a lip-sync model on a GPU, because nothing ffprobe reports
 * can find this (the spec's "What was found" table).
 *
 * Nothing here writes a media file. See
 * `specs/requirements/phase-317-sound-that-runs-ahead-of-the-picture-is-found-and-explained.md`.
 */
class AvSyncService(
    private val db: JellystructureDb,
    private val integrity: FileIntegrityService,
    private val configStore: ConfigStore,
    private val store: MediaStore,
    private val history: MediaHistory? = null,
) {
    private val q get() = db.fileAvSyncQueries
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val windowsSer = ListSerializer(AvSyncWindow.serializer())
    private val othersSer = ListSerializer(AvSyncOther.serializer())

    /** FR-317-8, FR-317-17 — three windows, then three more when the first three give neither a steady
     *  offset nor an in-sync reading (too few confident, or confident and disagreeing). */
    private val firstWindows = listOf(0.20, 0.50, 0.80)
    private val secondWindows = listOf(0.35, 0.65, 0.90)
    private val windowSeconds = 45.0

    /** TMDB's Animation id — for films and series alike. Never the localised genre name (dev review item 8:
     *  *Tegnefilm* would miss every Danish-tagged cartoon). */
    private val animationGenreId = 16

    val config: dev.jellystructure.config.AvSyncConfig get() = configStore.current.avsync
    fun enabled(): Boolean = config.enabled

    /** The model string of the sidecar we last spoke to; a result stored under a different one is stale
     *  (FR-317-7). Null until `/health` has answered once. */
    private var lastModel: String? = null
    val model: String? get() = lastModel

    /** FR-317-16 (amendment 2026-10-12) — [ready]'s last answer and the moment it was taken, kept here
     *  beside [lastModel]. 0 = never asked. */
    private var readyAnswer = false
    private var readyAnsweredAtSec = 0L

    /**
     * FR-317-16 — the lane's own readiness condition, non-suspending, for the callers that render it on
     * every page (the Checks card's `avsync` line). [ready] itself is `suspend` and `/stat`s a file per
     * library root, so a card that asked it directly would cost a round trip per title page.
     *
     * Never asked yet reads as **not ready**, and so does an answer older than [READY_MAX_AGE_S]: the
     * lane's worker re-asks every 30–60 s while it is running, so an older answer means nobody is
     * maintaining it and the card must not claim a readiness on its behalf. The lane is not claiming
     * either, which is the whole point — one condition, read by the worker and by the card.
     */
    fun readyNow(nowSec: Long = nowEpochSec()): Boolean =
        readyAnswer && readyAnsweredAtSec > 0 && nowSec - readyAnsweredAtSec <= READY_MAX_AGE_S

    /** When [readyNow]'s answer was taken (epoch seconds); 0 = nothing has asked since boot. */
    val readyAnsweredAt: Long get() = readyAnsweredAtSec

    /** Records what [ready] (or [settings], which decides the same thing) just found. */
    private fun noteReady(answer: Boolean) {
        readyAnswer = answer
        readyAnsweredAtSec = nowEpochSec()
    }

    // ── the sidecar ─────────────────────────────────────────────────────────────────────────────

    /** FR-317-2 — `/health`, or null when nothing answered. Never throws. */
    suspend fun health(): AvSyncHealthDto? = runCatching {
        OutboundHttp.withPermit {
            val r = OutboundHttp.client.get("${config.baseUrl()}/health") {
                timeout { requestTimeoutMillis = 10_000; socketTimeoutMillis = 10_000 }
            }
            json.decodeFromString(AvSyncHealthDto.serializer(), r.bodyAsText())
        }
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        null
    }?.also { if (it.model.isNotBlank()) lastModel = it.model }

    /** FR-317-2 — `/stat`: can the sidecar see this path at all? Null when the sidecar did not answer,
     *  which is a different thing from "the file is not there" and is never treated as the latter. */
    suspend fun stat(path: String): AvSyncStatDto? = runCatching {
        OutboundHttp.withPermit {
            val r = OutboundHttp.client.post("${config.baseUrl()}/stat") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(StatRequest.serializer(), StatRequest(path)))
                timeout { requestTimeoutMillis = 10_000; socketTimeoutMillis = 10_000 }
            }
            json.decodeFromString(AvSyncStatDto.serializer(), r.bodyAsText())
        }
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        null
    }

    /**
     * FR-317-13 — the mount check: one known file per library root, `/stat`ed. Any root the sidecar
     * cannot see turns the check off for everything (a sweep against a missing mount is thousands of
     * 404s and a table full of nothing). The first draft of this phase assumed `/media`; the household
     * mounts `/mnt/media` and `/mnt/series`, which is exactly what this proves instead of assuming.
     *
     * Returns the roots it could not see — empty is "all good". A sidecar that did not answer at all
     * returns null (not reachable is reported on its own, FR-317-13).
     */
    suspend fun unseenRoots(cfg: AppConfig = configStore.current): List<String>? {
        val samples = samplePaths(cfg)
        if (samples.isEmpty()) return emptyList()
        val unseen = mutableListOf<String>()
        for ((root, sample) in samples) {
            val answer = stat(sample) ?: return null
            if (!answer.exists) unseen += root
        }
        return unseen
    }

    private var samplesCache: List<Pair<String, String>> = emptyList()
    private var samplesCacheRoots: List<String> = emptyList()

    /** One known video file per configured library root — what the mount check `/stat`s. Cached against the
     *  configured roots, because finding it walks the whole store and the roots do not move. A root with no
     *  file in the store yet is not in the answer: there is nothing to prove a mount with. */
    suspend fun samplePaths(cfg: AppConfig = configStore.current): List<Pair<String, String>> {
        val roots = cfg.libraries.filterNot { it.skip }.map { it.localPath.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        if (roots == samplesCacheRoots && samplesCache.size == roots.size) return samplesCache
        val found = LinkedHashMap<String, String>()
        for (item in store.allItems()) {
            for (path in integrity.videoPaths(listOf(item))) {
                val root = roots.firstOrNull { path.startsWith("$it/") } ?: continue
                if (root !in found) found[root] = path
            }
            if (found.size == roots.size) break
        }
        samplesCacheRoots = roots
        samplesCache = found.entries.map { it.key to it.value }
        return samplesCache
    }

    /**
     * FR-317-13 — the Settings card's own line, computed by the server so the page renders text only.
     *
     * FR-317-16 — [AvSyncSettingsDto.reachable] is the same condition as [ready], so the answer is recorded
     * for [readyNow] on the way out: the Settings card is asked on a Settings save and on every visit to the
     * page, which is two of the three refresh points FR-317-13 names.
     */
    suspend fun settings(cfg: AppConfig = configStore.current): AvSyncSettingsDto = settingsAnswer(cfg).also { noteReady(it.reachable) }

    private suspend fun settingsAnswer(cfg: AppConfig): AvSyncSettingsDto {
        if (!cfg.avsync.enabled) return AvSyncSettingsDto(false, false, "Off — nothing is measured and nothing is counted.")
        val h = health() ?: return AvSyncSettingsDto(true, false, "Not reachable at ${config.baseUrl()} — the sidecar is not running, or this is not its address.")
        if (!h.ok) return AvSyncSettingsDto(true, false, "Reachable, but it has no GPU: ${h.gpu}", h.model)
        val unseen = unseenRoots(cfg)
        if (unseen == null) return AvSyncSettingsDto(true, false, "Reachable, but it stopped answering mid-check.", h.model)
        if (unseen.isNotEmpty()) {
            return AvSyncSettingsDto(
                true, false,
                "Can't see ${unseen.joinToString(", ")} — mount ${if (unseen.size == 1) "it" else "them"} in the sidecar at the same path as here.",
                h.model, unseen,
            )
        }
        return AvSyncSettingsDto(true, true, "Measuring on ${h.gpu} · model ${h.model}", h.model)
    }

    /** FR-317-13 — is the check able to run right now? The lane's worker asks this before it claims, and
     *  FR-317-16's [readyNow] hands the same answer to the Checks card without asking again. */
    suspend fun ready(cfg: AppConfig = configStore.current): Boolean = readyAnswerFor(cfg).also { noteReady(it) }

    private suspend fun readyAnswerFor(cfg: AppConfig): Boolean {
        if (!cfg.avsync.enabled) return false
        val h = health() ?: return false
        if (!h.ok) return false
        return unseenRoots(cfg)?.isEmpty() == true
    }

    /** FR-317-2 — one `/measure`. Returns the answer, or the failure as a sentence for the job's error. */
    private suspend fun measureOnce(path: String, audio: String, others: List<String>, windows: List<MeasureWindow>): Result<MeasureResponse> = runCatching {
        OutboundHttp.withPermit {
            val r = OutboundHttp.client.post("${config.baseUrl()}/measure") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(MeasureRequest.serializer(), MeasureRequest(path, audio, others, windows)))
                timeout { requestTimeoutMillis = config.effectiveTimeoutMs(); socketTimeoutMillis = config.effectiveTimeoutMs() }
            }
            val body = r.bodyAsText()
            when (r.status.value) {
                200 -> json.decodeFromString(MeasureResponse.serializer(), body)
                404 -> throw AvSyncRefused("the sidecar cannot see $path — check its mounts")
                422 -> throw AvSyncRefused("the sidecar could not decode it: ${errorOf(body)}")
                503 -> throw AvSyncBusy(retryAfterOf(body))
                else -> throw AvSyncRefused("the sidecar answered ${r.status.value}: ${errorOf(body)}")
            }
        }
    }

    class AvSyncRefused(message: String) : Exception(message)
    class AvSyncBusy(val retryAfterS: Int) : Exception("the sidecar is already measuring a file")

    private fun errorOf(body: String): String =
        runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull() ?: body.take(200)

    private fun retryAfterOf(body: String): Int =
        runCatching { json.parseToJsonElement(body).jsonObject["retry_after_s"]?.jsonPrimitive?.content?.toInt() }.getOrNull() ?: 20

    // ── reading what is stored ──────────────────────────────────────────────────────────────────

    fun rowsByPath(): Map<String, File_av_sync> = q.all().executeAsList().associateBy { it.path }

    /** Phase 261 (FR-261-10) — one file's stored row, current or not (the caller applies the rule). */
    fun rowFor(path: String): File_av_sync? = q.byPath(path).executeAsOneOrNull()

    /**
     * FR-317-8 — what the enqueue pass counts as "already has a result": a row the CURRENT pipeline version
     * wrote (or a skip, which no version can change). A row from an older `model` is deliberately absent
     * from this map, which is what makes a model bump re-measure the library instead of silently standing.
     */
    fun rowsCurrentByPath(): Map<String, File_av_sync> {
        val m = lastModel
        return q.all().executeAsList()
            .filter { m == null || it.model == m || it.reason in SKIP_REASONS }
            .associateBy { it.path }
    }

    private fun windowsOf(row: File_av_sync): List<AvSyncWindow> =
        runCatching { json.decodeFromString(windowsSer, row.windows) }.getOrDefault(emptyList())

    private fun othersOf(row: File_av_sync): List<AvSyncOther> =
        runCatching { json.decodeFromString(othersSer, row.others) }.getOrDefault(emptyList())

    /** The size+mtime rule (254's), plus FR-317-7's: a row written by a different pipeline version is
     *  not current either — the whole point of versioning the model string. */
    private fun isCurrent(row: File_av_sync): Boolean {
        val stamp = FileIntegrityService.stampOf(row.path) ?: return false
        if (!FileIntegrityService.isCurrent(row.size, row.mtime, stamp)) return false
        // A skip (FR-317-8) was decided here, not by the sidecar: a new pipeline version cannot change
        // whether a title is animation, so such a row is not made due by one.
        if (row.reason in SKIP_REASONS) return true
        val m = lastModel ?: return true          // never spoken to the sidecar: trust what is stored
        return row.model == m
    }

    // ── the library's zero (FR-317-6) ───────────────────────────────────────────────────────────

    private var zeroCache: Int = AvSyncVerdict.DEFAULT_ZERO_FRAMES
    private var zeroCacheRevision: Int = -1

    /**
     * FR-317-6 — zero is the conf-weighted median of EVERY confident window stored, recomputed when the
     * table's revision changes, and −1 frame until [AvSyncVerdict.ZERO_MIN_WINDOWS] confident windows
     * exist. In-sync material reads −1…0, not 0 (measured over ~20 titles), so a zero of 0 would have
     * called a whole library one frame late.
     */
    fun zeroFrames(): Int {
        val rev = AvSyncFlags.revision
        if (rev == zeroCacheRevision) return zeroCache
        val model = lastModel
        val rows = if (model != null) q.measuredWindows(model).executeAsList() else q.all().executeAsList().filter { it.reason == null }.map { it.windows }
        val confident = rows.flatMap { raw ->
            runCatching { json.decodeFromString(windowsSer, raw) }.getOrDefault(emptyList())
                .filter { AvSyncVerdict.isConfident(it) }
                .map { it.offsetFrames!! to it.conf }
        }
        zeroCache = AvSyncVerdict.zeroFrames(confident)
        zeroCacheRevision = rev
        return zeroCache
    }

    /** FR-317-7 — one file's verdict, computed on read. Null when there is no current result at all
     *  (unchecked — which is never the same thing as `unknown`, and never the same thing as clean). */
    fun readingFor(path: String, rows: Map<String, File_av_sync> = rowsByPath(), zero: Int = zeroFrames(), runtimeS: Double? = null): AvSyncReading? {
        val row = rows[path] ?: return null
        if (!isCurrent(row)) return null
        return AvSyncVerdict.of(windowsOf(row), zero, runtimeS, row.reason)
    }

    /** FR-317-7 — the paths that need an operator's attention right now, with their reading. */
    fun flaggedPaths(): Map<String, AvSyncReading> {
        val zero = zeroFrames()
        val model = lastModel
        val rows = if (model != null) q.measured(model).executeAsList() else q.all().executeAsList().filter { it.reason == null }
        return rows.filter { isCurrent(it) }
            .associate { it.path to AvSyncVerdict.of(windowsOf(it), zero, null, it.reason) }
            .filterValues { it.isFinding }
    }

    /** Phase 261 (dev review item 1) — the Jobs view's *N findings today*: read from the results, not the
     *  job rows (a check succeeds whether or not the sound is out of step). */
    fun findingsSince(since: Long): Int {
        val zero = zeroFrames()
        return q.checkedSince(since).executeAsList().count { AvSyncVerdict.of(windowsOf(it), zero, null, it.reason).isFinding }
    }

    /** Phase 261 — a stored row's finding in one line for the Checks card, in the advice's own words. */
    fun findingSummary(row: File_av_sync): String? {
        row.reason?.let { return AvSyncReason.label(it) }
        val reading = AvSyncVerdict.of(windowsOf(row), zeroFrames(), null, null)
        return when (reading.state) {
            AvSyncState.OFFSET -> "audio ${AvSyncVerdict.signedMs(reading.offsetMs ?: 0)} — ${if (reading.audioEarly) "ahead of" else "behind"} the picture"
            AvSyncState.DRIFT -> "the sound drifts away from the picture"
            AvSyncState.UNKNOWN -> reading.why
            AvSyncState.IN_SYNC -> null
        }
    }

    // ── the check (FR-317-8) ────────────────────────────────────────────────────────────────────

    /** FR-317-8 — a file written off without a request, in the enqueue pass. Null means "ask the
     *  sidecar". [runtimeS] is what the backend knows of the length (TMDB's, or the probed one in the
     *  job itself); an unknown length is never a skip. */
    fun skipReason(item: MediaItem, path: String, runtimeS: Double?): String? {
        if (isAnimation(item)) return AvSyncReason.ANIMATION
        if (runtimeS != null && runtimeS < AvSyncVerdict.MIN_RUNTIME_S) return AvSyncReason.TOO_SHORT
        if (chooseAudio(item, path) == null && audioTracks(item, path).size > 1) return AvSyncReason.NO_ORIGINAL_TRACK
        return null
    }

    /** Dev review item 8 — by TMDB id, for films and series alike. */
    fun isAnimation(item: MediaItem): Boolean =
        animationGenreId in item.genreIds.filterNotNull() || animationGenreId in item.tmdbGenreIds.filterNotNull()

    /** The file's audio streams in container order — an episode's are the [dev.jellystructure.model.Episode]'s own
     *  (dev review item 9), never the series-level item's. */
    fun audioTracks(item: MediaItem, path: String): List<Track> {
        val tracks = if (item.kind == MediaKind.TV_SHOW) item.episodes.firstOrNull { it.path == path }?.tracks ?: emptyList() else item.tracks
        return tracks.filter { it.kind == TrackKind.AUDIO }.sortedBy { it.streamIndex }
    }

    /** FR-317-4 — which stream the lips are followed against. */
    fun chooseAudio(item: MediaItem, path: String): String? =
        AvSyncAdvice.measuredTrack(
            audioTracks(item, path).map { Triple(it.specifier, it.language, it.default) },
            item.originalLanguage,
            LanguageResolver::sameLanguage,
        )

    /** FR-317-8 — what the backend knows of a file's length before it probes anything: the episode's own
     *  runtime for a series, the film's otherwise, in seconds. */
    fun knownRuntimeS(item: MediaItem, path: String): Double? {
        val minutes = if (item.kind == MediaKind.TV_SHOW) item.episodes.firstOrNull { it.path == path }?.runtime ?: item.runtime else item.runtime
        return minutes?.takeIf { it > 0 }?.let { it * 60.0 }
    }

    /** FR-317-5 — a skip, written straight to the table with its reason: no request, and never "clean". */
    fun storeSkip(path: String, reason: String) {
        val stamp = FileIntegrityService.stampOf(path) ?: return
        q.put(path, stamp.size, stamp.mtime, nowEpochSec(), lastModel ?: PENDING_MODEL, null, reason, "[]", "[]", null)
    }

    /**
     * FR-317-8 — one file measured, persisted. Returns the reading, or null with the reason as a message
     * when nothing could be measured at all (the job turns that into its own failure text).
     *
     * The order matters: the file is stamped BEFORE the measurement and again after, and a file rewritten
     * while the GPU was reading it stores nothing (255's rule) — a half-copied file would otherwise be
     * recorded as a finding under a stamp that no longer describes it.
     */
    suspend fun check(item: MediaItem, path: String): Result<AvSyncReading> {
        val before = FileIntegrityService.stampOf(path) ?: return Result.failure(AvSyncRefused("the file is no longer on disk"))
        // The row is stored under the sidecar's own model string (FR-317-7), so ask once if we have never
        // been told it — a row written under [PENDING_MODEL] would be measured all over again next pass.
        if (lastModel == null) health()
        val runtimeS = FfmpegRunner.probeDurationSeconds(path) ?: knownRuntimeS(item, path)
        skipReason(item, path, runtimeS)?.let { reason ->
            storeSkip(path, reason)
            return Result.success(AvSyncVerdict.of(emptyList(), zeroFrames(), runtimeS, reason))
        }
        if (runtimeS == null) return Result.failure(AvSyncRefused("the file's length could not be read"))
        val audio = chooseAudio(item, path) ?: return Result.failure(AvSyncRefused("the file has no audio track to measure"))
        val others = audioTracks(item, path).map { it.specifier }.filter { it != audio }
        // FR-317-5 — the mkvmerge ids are read ONCE here, at check time, so the advice (and the command on
        // the title page) never reads a media file on the request path.
        val mkvIds = if (AvSyncAdvice.isMatroska(path)) readMkvAudioIds(path) else emptyMap()

        var windows = measure(path, audio, others, runtimeS, firstWindows).getOrElse { return Result.failure(it) }
        if (AvSyncVerdict.wantsMoreWindows(AvSyncVerdict.of(windows.windows, zeroFrames(), runtimeS, null))) {
            // FR-317-8 / FR-317-17 — three more windows elsewhere in the file before giving up: a film whose
            // 20/50/80 all land on a landscape is common, and so is one whose three confident windows
            // disagree — a drift needs four of them on a line, which three windows can never reach. One
            // extra pass only (there is no third), at ~4 s of one card, and only for a file with no answer.
            val more = measure(path, audio, others, runtimeS, secondWindows).getOrNull()
            if (more != null) windows = MeasureResponse(windows.windows + more.windows, more.others.ifEmpty { windows.others }, more.decode, windows.seconds + more.seconds)
        }
        val after = FileIntegrityService.stampOf(path) ?: return Result.failure(AvSyncRefused("the file is no longer on disk"))
        if (after.size != before.size || after.mtime != before.mtime) {
            return Result.failure(AvSyncRefused("the file changed while it was being measured — it will be measured again"))
        }
        val reading = AvSyncVerdict.of(windows.windows, zeroFrames(), runtimeS, null)
        val reason = if (reading.state == AvSyncState.UNKNOWN) reading.reason else null
        q.put(
            path, after.size, after.mtime, nowEpochSec(), lastModel ?: PENDING_MODEL, audio, reason,
            json.encodeToString(windowsSer, windows.windows), json.encodeToString(othersSer, windows.others),
            mkvIds.takeIf { it.isNotEmpty() }?.let { ids -> json.encodeToString(ListSerializer(MkvAudioId.serializer()), ids.map { MkvAudioId(it.key, it.value) }) },
        )
        // The zero moved (every stored window feeds it) whatever the verdict, so the revision bumps on
        // every measured file, not only on a finding (FR-317-6).
        AvSyncFlags.bump()
        if (reading.isFinding) {
            history?.record(item.id, AvSyncFlags.HISTORY_ACTION, "${path.substringAfterLast('/')}: ${reading.state.name.lowercase()} ${reading.offsetMs?.let { AvSyncVerdict.signedMs(it) } ?: ""}".trim())
            Logger.warn("av sync: ${reading.state.name.lowercase()} ${reading.offsetMs?.let { AvSyncVerdict.signedMs(it) } ?: ""} in $path (${windows.decode}, ${windows.seconds}s)", "integrity")
        }
        return Result.success(reading)
    }

    private suspend fun measure(path: String, audio: String, others: List<String>, runtimeS: Double, fractions: List<Double>): Result<MeasureResponse> {
        val windows = fractions.map { f ->
            // Clamped inside the file: a 2-minute episode's 80 % window would otherwise ask for 45 s that
            // is not there, and the sidecar would answer with however much it got.
            val start = (runtimeS * f).coerceIn(0.0, (runtimeS - windowSeconds).coerceAtLeast(0.0))
            MeasureWindow(startS = (start * 10).toLong() / 10.0, durS = minOf(windowSeconds, runtimeS - start))
        }.distinctBy { it.startS }.filter { it.durS >= 10.0 }
        if (windows.isEmpty()) return Result.failure(AvSyncRefused("the file is too short to measure"))
        return measureOnce(path, audio, others, windows)
    }

    /** `mkvmerge -J` → each `0:a:N`'s own mkvmerge track id (FR-317-5). The n-th audio track in the JSON is
     *  the n-th in ffmpeg's `0:a:<n>`, the same assumption [dev.jellystructure.filefix.parseTrackUids] makes. */
    suspend fun readMkvAudioIds(path: String): Map<String, Int> {
        val out = dev.jellystructure.ops.SegmentProcessGate.withPermit { captureShell(FileFixCommands.identify(path) + " 2>/dev/null") } ?: ""
        val ident = parseMkvIdentification(out)
        if (ident.ids.isEmpty()) Logger.warn(probeFailureWarning(path, out.encodeToByteArray().size, ident), "integrity")
        return ident.ids
    }

    companion object {
        /** The model a row is stored under before `/health` has ever answered — never equal to a real one,
         *  so such a row is re-measured once the sidecar has introduced itself. */
        const val PENDING_MODEL = "unknown"

        /** The reasons the backend decides by itself, without ever asking the sidecar (FR-317-8). */
        val SKIP_REASONS = setOf(AvSyncReason.ANIMATION, AvSyncReason.TOO_SHORT, AvSyncReason.NO_ORIGINAL_TRACK)

        /** FR-317-16 — how long [readyNow] will stand by a recorded answer. Five times the lane worker's
         *  own 60 s heartbeat: comfortably longer than a slow `/health` + a `/stat` per root, short enough
         *  that a lane that has stopped asking stops claiming readiness within minutes. */
        const val READY_MAX_AGE_S = 300L

        /** `mkvmerge -J` → `0:a:N` → the track's own `id`. Pure, so the mapping is tested rather than eyeballed. */
        fun parseMkvAudioIds(output: String): Map<String, Int> = parseMkvIdentification(output).ids

        /**
         * FR-317-21 / FR-317-26 — the whole answer: the ids, and whether the identification happened.
         *
         * A whole document is read whole. A document that is **not** whole is read as far as it is: the
         * `tracks` array is walked in order and only ids of complete track objects are taken, stopping at
         * the first object that cannot be read in full. An id is therefore either read in full or not read
         * at all, an incomplete object never advances the `0:a:N` ordinal (and because the walk stops there,
         * no later track exists to be mis-numbered), and the map is possibly short, never wrong.
         *
         * Why this matters at all: a document can arrive short for reasons that have nothing to do with the
         * C locale of phase 318 — a killed process, a full pipe — and throwing away a whole good prefix for
         * a bad tail is wrong on its own terms.
         */
        fun parseMkvIdentification(output: String): MkvIdentification {
            val root = runCatching { Json.parseToJsonElement(output).jsonObject }.getOrNull()
            if (root != null) {
                val errors = runCatching {
                    root["errors"]!!.jsonArray.map { it.jsonPrimitive.content }
                }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
                val tracks = runCatching { root["tracks"]!!.jsonArray.map { it.jsonObject } }.getOrNull()
                // Both at once is ordinary: mkvmerge reports plenty about a file it identifies perfectly
                // well, so the ids are read whatever the errors array says.
                return MkvIdentification(audioIdsOf(tracks ?: emptyList()), errors.isNotEmpty(), errors.firstOrNull())
            }
            // Not whole. Nothing to report but what the prefix carries — and no `errors` array to read,
            // since the document could not be parsed to find one.
            val ids = audioIdsOf(wholeTrackObjects(output))
            return MkvIdentification(ids, failed = ids.isEmpty(), truncated = output.contains("\"tracks\""))
        }

        /**
         * FR-317-23 — the one line a failed id probe leaves on the integrity channel. Pure, so the wording
         * is tested rather than eyeballed.
         *
         * [bytes] is not decoration: that single number separates a refusal to identify (~115 bytes of
         * `errors`) from a document that arrived short, at a glance and without a container shell. The
         * **exit status** cannot be had here — the call pipes `2>/dev/null` and `captureShell` discards
         * `pclose`'s answer — so mkvmerge's own first error line stands in its place, which also names
         * *why* it refused (FR-317-26, amendment of 2026-10-13).
         */
        fun probeFailureWarning(path: String, bytes: Int, ident: MkvIdentification): String {
            val why = ident.error?.let { "mkvmerge says: $it" }
                ?: when {
                    ident.truncated -> "and the document stops part-way through its own track list"
                    ident.failed -> "and nothing in them that could be read as an identification"
                    else -> "and no audio track named in them"
                }
            return "av sync: this file's own mkvmerge track numbers could not be read — $bytes bytes back, $why. " +
                "No fix command can be offered for it until the probe succeeds (↻ on the Checks card is the retry): $path"
        }

        /** The audio ids of [tracks] in document order, stopping at the first object that cannot be read
         *  in full — an unreadable `type` or `id` ends the walk rather than advancing the ordinal past it. */
        private fun audioIdsOf(tracks: List<JsonObject>): Map<String, Int> {
            var n = 0
            val out = LinkedHashMap<String, Int>()
            for (t in tracks) {
                val type = runCatching { t["type"]!!.jsonPrimitive.content }.getOrNull() ?: break
                if (type != "audio") continue
                val id = runCatching { t["id"]!!.jsonPrimitive.content.toInt() }.getOrNull() ?: break
                out["0:a:$n"] = id
                n++
            }
            return out
        }

        /** The complete objects of a truncated document's `tracks` array, in order, up to the first one the
         *  text does not finish. Each is parsed on its own, so a tail that is not JSON costs only itself. */
        private fun wholeTrackObjects(output: String): List<JsonObject> {
            val key = output.indexOf("\"tracks\"")
            if (key < 0) return emptyList()
            var i = output.indexOf('[', key)
            if (i < 0) return emptyList()
            i++
            val out = ArrayList<JsonObject>()
            while (i < output.length) {
                while (i < output.length && (output[i].isWhitespace() || output[i] == ',')) i++
                // A `]` (the array ended) or a cut: either way there is no further whole object.
                if (i >= output.length || output[i] != '{') break
                val end = endOfObject(output, i) ?: break
                val obj = runCatching { Json.parseToJsonElement(output.substring(i, end + 1)).jsonObject }.getOrNull() ?: break
                out.add(obj)
                i = end + 1
            }
            return out
        }

        /** The index of the `}` that closes the object starting at [from], or null if the text ends first. */
        private fun endOfObject(s: String, from: Int): Int? {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in from until s.length) {
                val c = s[i]
                when {
                    escaped -> escaped = false
                    inString && c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> {}
                    c == '{' || c == '[' -> depth++
                    c == '}' || c == ']' -> if (--depth == 0) return i
                }
            }
            return null
        }
    }

    // ── saying it (FR-317-10) ───────────────────────────────────────────────────────────────────

    /** The advice for one stored row, with the track labels a page shows. */
    fun fixFor(item: MediaItem, row: File_av_sync, reading: AvSyncReading): AvSyncFix? {
        val audio = row.audio ?: return null
        val tracks = audioTracks(item, row.path)
        val mkvIds = row.mkv_audio_ids?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(MkvAudioId.serializer()), raw) }.getOrDefault(emptyList())
        }?.associate { it.audio to it.id } ?: emptyMap()
        val others = othersOf(row).associateBy { it.audio }
        fun track(spec: String): AvSyncTrack {
            val t = tracks.firstOrNull { it.specifier == spec }
            val o = others[spec]
            return AvSyncTrack(
                specifier = spec,
                label = trackLabel(t, spec),
                mkvId = mkvIds[spec],
                lagFrames = o?.lagFrames,
                corr = o?.corr ?: 0.0,
            )
        }
        val measured = track(audio)
        val rest = tracks.map { it.specifier }.filter { it != audio }.map { track(it) }
        return AvSyncAdvice.advise(reading, row.path, measured, rest)
    }

    private fun trackLabel(t: Track?, spec: String): String = listOfNotNull(
        t?.language?.let { "\"$it\"" } ?: "untagged",
        t?.codec?.uppercase(),
        t?.title?.let { "“$it”" },
    ).joinToString(" ") + " ($spec)"

    /** FR-317-10 — one title's A/V sync, every word from the server. */
    fun statusFor(item: MediaItem): AvSyncStatusDto {
        val paths = integrity.videoPaths(listOf(item))
        val rows = rowsByPath()
        val zero = zeroFrames()
        val readings = paths.associateWith { readingFor(it, rows, zero, knownRuntimeS(item, it)) }
        val files = readings.filterValues { it != null && it.state != AvSyncState.IN_SYNC }.map { (path, r) ->
            val reading = r!!
            val row = rows[path]!!
            val episode = item.episodes.firstOrNull { it.path == path }
            val fix = if (reading.isFinding) fixFor(item, row, reading) else null
            AvSyncFileDto(
                path = path,
                state = reading.state.name.lowercase(),
                offsetMs = reading.offsetMs,
                driftFromMs = reading.driftFromMs, driftToMs = reading.driftToMs,
                driftFromMsAt = reading.driftFromS?.let { (it * 1000).toLong() },
                driftToMsAt = reading.driftToS?.let { (it * 1000).toLong() },
                reason = reading.reason, reasonLabel = reading.reason?.let { AvSyncReason.label(it) } ?: reading.why,
                checkedAt = row.checked_at,
                season = episode?.seasonNumber,
                audio = row.audio,
                audioStreamIndex = row.audio?.let { spec -> audioTracks(item, path).firstOrNull { it.specifier == spec }?.streamIndex },
                audioMark = reading.offsetMs?.takeIf { reading.state == AvSyncState.OFFSET }
                    ?.let { "${if (it < 0) -it else it} ms ${if (it > 0) "early" else "late"}" },
                what = fix?.what, experience = fix?.experience, who = fix?.who,
                suggestion = fix?.suggestion, command = fix?.command, noCommandReason = fix?.noCommandReason,
                shifted = fix?.shifted ?: emptyList(), left = fix?.left ?: emptyList(),
                windows = windowsOf(row).sortedBy { it.startS }.map { w ->
                    AvSyncWindowDto(
                        atMs = (w.startS * 1000).toLong(),
                        offsetMs = w.offsetFrames?.let { (it - zero) * AvSyncVerdict.MS_PER_FRAME },
                        conf = w.conf, frames = w.frames, confident = AvSyncVerdict.isConfident(w),
                    )
                },
            )
        }
        return AvSyncStatusDto(
            enabled = enabled(),
            total = paths.size,
            inSync = readings.values.count { it?.state == AvSyncState.IN_SYNC },
            unchecked = readings.values.count { it == null },
            unknown = readings.values.count { it?.state == AvSyncState.UNKNOWN },
            zeroFrames = zero,
            files = files.sortedBy { it.path },
        )
    }
}

/** One `0:a:N` → mkvmerge track id pair, as stored in `file_av_sync.mkv_audio_ids`. */
@Serializable
data class MkvAudioId(val audio: String, val id: Int)

/**
 * FR-317-21 / FR-317-26 — what one `mkvmerge -J` run yielded.
 *
 * [ids] is possibly short, never wrong. [failed] says the identification itself did not happen —
 * a document whose `errors` array is non-empty, or output that is not JSON at all — as against a
 * container that genuinely has nothing to report, which is an empty map and no failure. [error] is
 * mkvmerge's own first `errors[]` line where there is one, which is what the `WARN` carries in place
 * of an exit status that cannot be had here (FR-317-23, as amended 2026-10-13).
 */
data class MkvIdentification(
    val ids: Map<String, Int>,
    val failed: Boolean,
    val error: String? = null,
    /** The document was not whole and was read as far as it went — which is what the `WARN` says instead
     *  of claiming there was nothing in it to read. */
    val truncated: Boolean = false,
)
