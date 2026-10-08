package dev.jellystructure.tv

import dev.jellystructure.shared.tv.playbackFinished
import dev.jellystructure.util.isoToEpochSeconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 310 (FR-310-7) / 312 (FR-312-5) — putting back the watch places Jellyfin lost.
 *
 * - **310:** from 2026-10-06 15:34:40 UTC until the restart, no progress or stop reached Jellyfin; its idle check then
 *   stopped each play with no position and marked it watched by guessing. The backend's own `playback stop:` lines
 *   (saved before any redeploy) hold the real position of each.
 * - **312:** a stray stop at 0 ms, landing just after a real stop of the same item, reset the place.
 *
 * The candidates are prepared once, outside the backend, from those logs (a household-specific one-off) into
 * `playback-repair-candidates.json` next to the database. [planRepair] turns them into actions, and only for items that
 * still show the damage: an item played again since, finished, or not a film or episode is left alone. Nothing is
 * written until the owner presses *Put them back* (the Dashboard's row); after that the file is renamed `.applied`.
 */
@Serializable
data class RepairCandidate(
    @SerialName("jellyfin_id") val jellyfinId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("position_ms") val positionMs: Long,
    /** When the real stop happened (ISO-8601 UTC); becomes the item's `LastPlayedDate`. */
    @SerialName("stopped_at") val stoppedAt: String,
    /** `310` (the writer outage) or `312` (a stray stop at 0). */
    val source: String,
)

@Serializable
data class RepairAction(
    @SerialName("jellyfin_id") val jellyfinId: String,
    @SerialName("user_id") val userId: String,
    val title: String,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("last_played") val lastPlayed: String,
    val source: String,
    /** What Jellyfin holds now, in words (for the list the owner reads before Apply). */
    val now: String,
)

/** What the planner needs about one item as Jellyfin and the library see it now. */
data class RepairItemNow(
    val title: String,
    val durationMs: Long,
    val creditsStartMs: Long?,
    val played: Boolean,
    val positionMs: Long,
    val lastPlayedDate: String?,
)

/** A later play moved the date past the damage's own time by more than this: the viewer has been back since. */
internal const val REPAIR_LATER_PLAY_GRACE_S = 15 * 60L

internal suspend fun planRepair(candidates: List<RepairCandidate>, now: suspend (RepairCandidate) -> RepairItemNow?): List<RepairAction> {
    // One action per (user, item): the latest stop is the real last word.
    val latest = candidates.groupBy { it.userId to it.jellyfinId }.values
        .map { group -> group.maxBy { isoToEpochSeconds(it.stoppedAt) ?: 0L } }
    return latest.mapNotNull { c ->
        val item = now(c) ?: return@mapNotNull null                       // not a film or episode (a song), or gone
        if (c.positionMs <= 0L) return@mapNotNull null                     // nothing to put back (a shuffle's own 0)
        if (playbackFinished(c.positionMs, item.durationMs, item.creditsStartMs)) return@mapNotNull null   // R347: truly finished
        val stopped = isoToEpochSeconds(c.stoppedAt) ?: return@mapNotNull null
        val last = item.lastPlayedDate?.let { isoToEpochSeconds(it) }
        if (last != null && last > stopped + REPAIR_LATER_PLAY_GRACE_S) return@mapNotNull null   // played again since
        val damaged = item.played || kotlin.math.abs(item.positionMs - c.positionMs) > 2_000L
        if (!damaged) return@mapNotNull null
        RepairAction(c.jellyfinId, c.userId, item.title, c.positionMs, c.stoppedAt, c.source,
            now = if (item.played) "watched" else if (item.positionMs == 0L) "from the start" else "at ${item.positionMs / 1000} s")
    }.sortedBy { it.lastPlayed }
}
