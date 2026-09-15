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
    data class LogLine(val level: String, val category: String, val message: String, val mediaId: String? = null, val runId: String? = null, val step: String? = null) : JobEvent()

    // Phase 135 — step-aware pipeline progress. `scan_files` keeps broadcasting Started/ItemScanned/
    // FileProgress unchanged (its own battle-tested worker pool, untouched); every step from PipelinePlan
    // onward (including scan_files itself, listed first) gets these three so the Activity page can render
    // the whole pipeline and highlight the active phase, not just freeze after scan_files.
    @Serializable @SerialName("pipeline_plan")
    data class PipelinePlan(val jobId: String, val steps: List<String>) : JobEvent()

    @Serializable @SerialName("step_started")
    data class StepStarted(val jobId: String, val step: String, val total: Int) : JobEvent()

    @Serializable @SerialName("step_progress")
    data class StepProgress(val jobId: String, val step: String, val item: String, val current: Int, val total: Int) : JobEvent()

    @Serializable @SerialName("step_finished")
    data class StepFinished(val jobId: String, val step: String, val summary: String) : JobEvent()

    // Phase 109 — media worker (ffmpeg remux) job queue. `mediaJobId` is the media_job row id, distinct
    // from the scan-style `jobId` used by the events above. One MediaJobSnapshot per state change or
    // progress tick — the Jobs page just re-renders from the latest snapshot, no client-side diffing.
    @Serializable @SerialName("media_job")
    data class MediaJobUpdate(val job: MediaJobSnapshot) : JobEvent()

    // Phase 178 §FR-178-2/FR-178-4 — a scheduled/event-driven pipeline run is deferring its start (or a
    // step) because a TV is playing. [devices] names who (PlaybackTracker.activeDevices()) for the
    // dashboard's "Paused — TV is watching {name}" state; [Deferred] fires once when waiting begins,
    // [Resumed] once when it clears and the run actually proceeds — never on every poll tick.
    @Serializable @SerialName("pipeline_deferred")
    data class Deferred(val jobId: String, val devices: List<String>) : JobEvent()

    @Serializable @SerialName("pipeline_resumed")
    data class Resumed(val jobId: String) : JobEvent()
}

/** Phase 109 — everything the Activity ▸ Jobs page needs to render one row, in one shape shared by the
 *  REST list endpoint and the WS push so the FE has a single render path for both. */
@Serializable
data class MediaJobSnapshot(
    val id: String,
    val type: String,           // reorder | remove | bulk_reorder | segments_movie | segments_season | segments_episodes
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
    val lane: String = "media",  // Phase 164/213 — "media" | "segments" | "subtitles"
)

/** Phase 164 (FR-164-6), widened by Phase 213 to three queues — one worker-line summary chip's worth of
 *  data for the Jobs & workers page: busy/running/queued/done-today, per queue. `configuredWorkers`
 *  reports the ONE shared pool's target on every entry since Phase 213 (queues no longer size
 *  independently). Shared shape (not duplicated frontend/backend) like [MediaJobSnapshot] above. */
@Serializable
data class LaneSummary(
    val lane: String,
    val runningCount: Int,
    val queuedCount: Int,
    val doneToday: Int,
    val configuredWorkers: Int,
)
