package dev.jellystructure.jobs

import dev.jellystructure.model.MediaItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class JobEvent {
    @Serializable @SerialName("started")
    data class Started(val jobId: String, val total: Int) : JobEvent()

    @Serializable @SerialName("progress")
    data class FileProgress(val jobId: String, val file: String, val current: Int, val total: Int) : JobEvent()

    @Serializable @SerialName("file_done")
    data class FileDone(val jobId: String, val file: String, val ok: Boolean, val msg: String? = null) : JobEvent()

    @Serializable @SerialName("item_scanned")
    data class ItemScanned(val jobId: String, val item: MediaItem) : JobEvent()

    @Serializable @SerialName("finished")
    data class Finished(val jobId: String, val succeeded: Int, val failed: Int) : JobEvent()

    @Serializable @SerialName("log_line")
    data class LogLine(val level: String, val category: String, val message: String, val mediaId: String? = null, val runId: String? = null) : JobEvent()

    // Phase 109 — media worker (ffmpeg remux) job queue. `mediaJobId` is the media_job row id, distinct
    // from the scan-style `jobId` used by the events above. One MediaJobSnapshot per state change or
    // progress tick — the Jobs page just re-renders from the latest snapshot, no client-side diffing.
    @Serializable @SerialName("media_job")
    data class MediaJobUpdate(val job: MediaJobSnapshot) : JobEvent()
}

/** Phase 109 — everything the Activity ▸ Jobs page needs to render one row, in one shape shared by the
 *  REST list endpoint and the WS push so the FE has a single render path for both. */
@Serializable
data class MediaJobSnapshot(
    val id: String,
    val type: String,           // reorder | remove | bulk_reorder
    val mediaId: String,
    val label: String,
    val state: String,          // queued | running | done | failed | cancelled
    val enqueuedBy: String,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
    val fileCount: Int = 1,
    val filesDone: Int = 0,
    val pct: Double = 0.0,
    val speed: String? = null,
    val etaSeconds: Long? = null,
)
