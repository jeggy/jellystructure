package dev.jellystructure.media

import kotlinx.serialization.Serializable

/**
 * DEB-2051 — what an AVI→MKV conversion is, on the wire and in the rules that need no process or file to state.
 *
 * The job rows carry the state (no table of its own): `files_done` is the step (1–3), `speed` is a machine detail
 * written when the row finishes ([JobDetail]), `error` is the human sentence. Everything the admin draws comes from
 * [ConversionPreview] / [ConversionLatest]; it derives none of it.
 */
object ContainerConversion {
    /** The `media_job.type` of one conversion; it runs on the media lane. */
    const val JOB_TYPE = "container_convert"

    const val STEPS = 3

    /** Block reasons, in the order FR-5 asks them. */
    const val PATH_MISMATCH = "path_mismatch"
    const val SEEDED = "seeded"
    const val UNKNOWN = "unknown"
    const val NAME_TAKEN = "name_taken"
    const val NO_ROOM = "no_room"

    /** [ConversionLatest.state]. */
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val VERIFIED = "verified"
    const val FAILED_TOOL = "failed_tool"
    const val FAILED_UNVERIFIED = "failed_unverified"
    const val BLOCKED = "blocked"

    /** FR-4 — free space must cover the source plus the media lane's own preflight margin (10 %). */
    fun requiredFreeBytes(sourceBytes: Long): Long = (sourceBytes * 1.1).toLong()

    /** The container a source is converted from and to; only AVI→MKV exists. */
    fun destinationOf(sourcePath: String): String = "${sourcePath.substringBeforeLast('.')}.mkv"

    /** FR-7 step 3 — where the original goes: the hidden folder beside it, under its own file name. */
    fun keptPathOf(sourcePath: String): String = "${WorkFiles.dirFor(sourcePath)}/${sourcePath.substringAfterLast('/')}"

    /** FR-7 step 1 — the work file the copy is written to. */
    fun workPathOf(sourcePath: String): String = WorkFiles.pathFor(destinationOf(sourcePath), WorkFiles.Kind.CONVERT)

    /** The verdict of one FR-5 check, as the pure rule sees it. */
    enum class Seeding { ALLOWED, BLOCKED, UNREACHABLE }

    /**
     * FR-5 — the first thing that makes a conversion unsafe or unknowable, in the spec's order; null when it is safe.
     * [jellyfinPath] is the item's path in Jellyfin mapped to the backend's view, null when it cannot be read (then it
     * proves nothing either way, and step 3 asks again right before anything is renamed).
     */
    fun firstBlock(
        storedPath: String,
        storedExists: Boolean,
        jellyfinPath: String?,
        seeding: Seeding,
        seedingMessage: String?,
        destinationExists: Boolean,
        keptExists: Boolean,
        sourceBytes: Long?,
        freeBytes: Long?,
    ): ConversionBlock? {
        val name = destinationOf(storedPath).substringAfterLast('/')
        return when {
            !storedExists -> ConversionBlock(PATH_MISMATCH, "The stored path is not on disk.")
            jellyfinPath != null && jellyfinPath != storedPath ->
                ConversionBlock(PATH_MISMATCH, "Jellyfin has this title at a different path than the one stored.")
            seeding == Seeding.BLOCKED -> ConversionBlock(SEEDED, seedingMessage ?: "A torrent seeds this file.")
            seeding == Seeding.UNREACHABLE -> ConversionBlock(UNKNOWN, seedingMessage ?: "qBittorrent could not be asked.")
            destinationExists || keptExists -> ConversionBlock(NAME_TAKEN, "A file named $name is already there, so nothing was converted.")
            sourceBytes != null && freeBytes != null && freeBytes < requiredFreeBytes(sourceBytes) ->
                ConversionBlock(NO_ROOM, "Not enough free space.", neededBytes = requiredFreeBytes(sourceBytes), freeBytes = freeBytes)
            else -> null
        }
    }

    /** FR-4 — time is the source's size over the median throughput of the last three remux-class jobs; null = unknown. */
    fun estimateSeconds(sourceBytes: Long?, throughputsBytesPerSec: List<Double>): Long? {
        if (sourceBytes == null || sourceBytes <= 0L) return null
        val recent = throughputsBytesPerSec.filter { it > 0.0 }.take(3)
        if (recent.isEmpty()) return null
        val sorted = recent.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        return (sourceBytes / median).toLong().coerceAtLeast(1L)
    }

    /** The machine detail a finished row keeps in `speed`: a code and optional flags, `;`-joined. */
    object JobDetail {
        const val VERIFIED_CODE = "verified"
        const val UNVERIFIED = "failed_unverified"
        const val TOOL = "failed_tool"
        const val CANCELLED = "cancelled"
        const val INTERRUPTED = "interrupted"
        const val BLOCKED_PREFIX = "blocked:"
        const val FLAG_JELLYFIN_PENDING = "jellyfin_pending"
        const val FLAG_RADARR_NOT_TAKEN = "radarr_not_taken"

        fun verified(jellyfinPending: Boolean, radarrNotTaken: Boolean): String =
            listOfNotNull(VERIFIED_CODE, FLAG_JELLYFIN_PENDING.takeIf { jellyfinPending }, FLAG_RADARR_NOT_TAKEN.takeIf { radarrNotTaken }).joinToString(";")

        fun blocked(reason: String): String = "$BLOCKED_PREFIX$reason"
    }

    /** FR-9 — the file's latest conversion, from one job row. [rowState] is the row's `queued/running/done/failed/cancelled`. */
    fun latestOf(
        jobId: String, rowState: String, step: Int, pct: Double, detail: String?, error: String?,
        requested: List<ConversionRequested> = emptyList(),
    ): ConversionLatest {
        val parts = detail?.split(';').orEmpty()
        val code = parts.firstOrNull()
        return when (rowState) {
            "queued" -> ConversionLatest(jobId, QUEUED, requested = requested)
            "running" -> ConversionLatest(jobId, RUNNING, step = step.coerceIn(1, STEPS), pct = pct, requested = requested)
            "done" -> ConversionLatest(
                jobId, VERIFIED, step = STEPS, requested = requested,
                jellyfinPending = JobDetail.FLAG_JELLYFIN_PENDING in parts, radarrNotTaken = JobDetail.FLAG_RADARR_NOT_TAKEN in parts,
            )
            else -> when {
                code == JobDetail.UNVERIFIED -> ConversionLatest(jobId, FAILED_UNVERIFIED, step = 2, message = error, requested = requested)
                code?.startsWith(JobDetail.BLOCKED_PREFIX) == true ->
                    ConversionLatest(jobId, BLOCKED, step = step, reason = code.removePrefix(JobDetail.BLOCKED_PREFIX), message = error, requested = requested)
                else -> ConversionLatest(jobId, FAILED_TOOL, step = step, reason = code ?: JobDetail.TOOL, message = error, requested = requested)
            }
        }
    }
}

/** One stream of the source, as the preview lists it; every one is copied untouched. */
@Serializable
data class ConversionStream(val specifier: String, val kind: String, val codec: String)

/** The language the operator asked for on one track (FR-4: carried by every conversion). */
@Serializable
data class ConversionRequested(val specifier: String, val language: String)

/** FR-5 — why a conversion cannot start. [reason] is one of the `ContainerConversion` block constants. */
@Serializable
data class ConversionBlock(val reason: String, val message: String, val neededBytes: Long? = null, val freeBytes: Long? = null)

/** FR-9 — the latest conversion job for a file. */
@Serializable
data class ConversionLatest(
    val jobId: String,
    val state: String,
    val step: Int = 0,
    val pct: Double = 0.0,
    val reason: String? = null,
    val message: String? = null,
    /** Verified, but Jellyfin has not yet shown the item at the new path (FR-9 *verified*). */
    val jellyfinPending: Boolean = false,
    /** Verified, but Radarr's file link does not name the `.mkv` (FR-8). */
    val radarrNotTaken: Boolean = false,
    /** The languages that job carried, so the notice names them after a reload (FR-9 *verified* / *failed*). */
    val requested: List<ConversionRequested> = emptyList(),
)

/** FR-4 — `GET /api/media/{id}/container-conversion`: everything the notice, preview and confirmation show. */
@Serializable
data class ConversionPreview(
    /** False when there is nothing to offer (not an AVI film): the admin draws no notice from it. */
    val available: Boolean,
    val sourcePath: String = "",
    val destinationPath: String = "",
    val keptPath: String = "",
    val streams: List<ConversionStream> = emptyList(),
    val requested: List<ConversionRequested> = emptyList(),
    /** Null when it cannot be worked out: the row then reads "Estimate unavailable" (D4). */
    val estimateSeconds: Long? = null,
    val tempBytes: Long? = null,
    val block: ConversionBlock? = null,
    val latest: ConversionLatest? = null,
)

/** FR-6 — `POST /api/media/{id}/container-conversion`. */
@Serializable
data class StartConversionRequest(val requested: List<ConversionRequested> = emptyList())

/** FR-6 — 202: the job; 409: [block] says which FR-5 check refused. */
@Serializable
data class StartConversionResponse(val jobId: String? = null, val block: ConversionBlock? = null, val error: String? = null)
