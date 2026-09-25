package dev.jellystructure.tv

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.Playback_qoe
import dev.jellystructure.nowEpochSec
import dev.jellystructure.shared.tv.PlaybackQoeReport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 177 (FR-177-5) — the admin-facing read model for a `playback_qoe` row. [hasIssue] is the one
 * signal both Activity's per-playback row and the Users & devices per-device summary badge on: a session
 * with any rebuffer or dropped frame is worth flagging, a clean one isn't badged at all (per the spec's
 * own "badged `--warn`; a clean one is not badged at all" wording).
 */
@Serializable
data class QoeSummary(
    @SerialName("device_id") val deviceId: String,
    @SerialName("jellyfin_id") val jellyfinId: String,
    @SerialName("play_session_id") val playSessionId: String,
    @SerialName("dropped_frames") val droppedFrames: Int,
    @SerialName("rebuffer_count") val rebufferCount: Int,
    @SerialName("rebuffer_ms") val rebufferMs: Long,
    @SerialName("bandwidth_estimate_bps") val bandwidthEstimateBps: Long?,
    @SerialName("video_decoder") val videoDecoder: String?,
    @SerialName("direct_play") val directPlay: Boolean,
    @SerialName("link_kind") val linkKind: String,
    @SerialName("link_mbps") val linkMbps: Int,
    // Phase 179 (FR-179-3).
    @SerialName("subtitle_load_errors") val subtitleLoadErrors: Int = 0,
    // R292 (FR-R292-11) — the ladder's count/rung/time and the two return counters; see PlaybackQoe.sq.
    @SerialName("video_output_recoveries") val videoOutputRecoveries: Int = 0,
    @SerialName("video_output_recovery_rung") val videoOutputRecoveryRung: Int = 0,
    @SerialName("video_output_recovery_ms") val videoOutputRecoveryMs: Long = 0,
    @SerialName("background_returns") val backgroundReturns: Int = 0,
    @SerialName("restored_after_recreate") val restoredAfterRecreate: Int = 0,
    // R237 (FR-R237-6) — non-null only when the start never reached the player.
    @SerialName("start_failure_status") val startFailureStatus: Int? = null,
    @SerialName("updated_at") val updatedAt: Long,
) {
    // R292 (dev review item 7) — a recovery-ladder firing and a restore after a recreation are worth a second
    // look; a plain return from the background is not (it is what HOME does), so it is carried, not badged.
    val hasIssue: Boolean get() = rebufferCount > 0 || droppedFrames > 0 || subtitleLoadErrors > 0 || startFailureStatus != null ||
        videoOutputRecoveries > 0 || restoredAfterRecreate > 0
}

private const val QOE_RETENTION_DAYS = 90L

/** Phase 177 (FR-177-5) — "this is the requirement that pays for the other four": the first place any
 *  jellystructure playback session's actual delivery quality (dropped frames, rebuffers, bandwidth) is
 *  ever recorded, closing the gap that made three separate stutter investigations a forensic
 *  reconstruction from router/Jellyfin logs days later. */
class PlaybackQoeStore(private val db: JellystructureDb) {
    private val queries get() = db.playbackQoeQueries

    /** [deviceId]/[playSessionId] are server-resolved (never client-supplied) — see the route doc in
     *  TvRoutes.kt for why. Upserts: [report]'s counters are the session's running totals (R216 —
     *  PlayerStore never resets them between posts), so a later post for the same session just advances
     *  this row to its latest cumulative state. */
    fun record(deviceId: String, playSessionId: String, report: PlaybackQoeReport) {
        queries.upsertQoe(
            device_id = deviceId,
            jellyfin_id = report.itemId,
            play_session_id = playSessionId,
            dropped_frames = report.droppedFrames.toLong(),
            rebuffer_count = report.rebufferCount.toLong(),
            rebuffer_ms = report.rebufferMs,
            bandwidth_estimate_bps = report.bandwidthEstimateBps,
            video_decoder = report.videoDecoder,
            direct_play = if (report.directPlay) 1L else 0L,
            link_kind = report.linkKind,
            link_mbps = report.linkMbps.toLong(),
            subtitle_load_errors = report.subtitleLoadErrors.toLong(),
            start_failure_status = report.startFailureStatus?.toLong(),
            video_output_recoveries = report.videoOutputRecoveries.toLong(),
            video_output_recovery_rung = report.videoOutputRecoveryRung.toLong(),
            video_output_recovery_ms = report.videoOutputRecoveryMs,
            background_returns = report.backgroundReturns.toLong(),
            restored_after_recreate = report.restoredAfterRecreate.toLong(),
            updated_at = nowEpochSec(),
        )
    }

    fun recentForDevice(deviceId: String, limit: Int = 10): List<QoeSummary> =
        queries.recentForDevice(deviceId, limit.toLong()).executeAsList().map { it.toSummary() }

    fun recent(limit: Int = 50): List<QoeSummary> =
        queries.recent(limit.toLong()).executeAsList().map { it.toSummary() }

    /** Diagnostic table, not a ledger — pruned so it can't grow unbounded (called on a periodic tick,
     *  see Main.kt). */
    fun pruneOld() {
        queries.deleteOlderThan(nowEpochSec() - QOE_RETENTION_DAYS * 86_400L)
    }
}

private fun Playback_qoe.toSummary() = QoeSummary(
    deviceId = device_id,
    jellyfinId = jellyfin_id,
    playSessionId = play_session_id,
    droppedFrames = dropped_frames.toInt(),
    rebufferCount = rebuffer_count.toInt(),
    rebufferMs = rebuffer_ms,
    bandwidthEstimateBps = bandwidth_estimate_bps,
    videoDecoder = video_decoder,
    directPlay = direct_play == 1L,
    linkKind = link_kind,
    linkMbps = link_mbps.toInt(),
    subtitleLoadErrors = subtitle_load_errors.toInt(),
    startFailureStatus = start_failure_status?.toInt(),
    videoOutputRecoveries = video_output_recoveries.toInt(),
    videoOutputRecoveryRung = video_output_recovery_rung.toInt(),
    videoOutputRecoveryMs = video_output_recovery_ms,
    backgroundReturns = background_returns.toInt(),
    restoredAfterRecreate = restored_after_recreate.toInt(),
    updatedAt = updated_at,
)
