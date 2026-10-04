package dev.jellystructure.tv

import dev.jellystructure.shared.tv.FINISHED_PERCENT
import dev.jellystructure.shared.tv.playbackFinished
import dev.jellystructure.util.epochTicksToIso
import dev.jellystructure.util.isoToEpochTicks
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * R343 / R347 — what one playback session needs to be told apart from an ordinary one, kept from its start to
 * its stop (the [PlaybackTracker] entry is rebuilt on every heartbeat, so this lives beside it, keyed the same).
 *
 * [startOverSeriesId] — the series' Jellyfin id when this start was a *Start over* (FR-R343-4), else null.
 * [durationMs] / [creditsStartMs] — for the 5 % trigger and R347's "finished" rule.
 * [shuffle] / [priorPositionMs] — a shuffled entry (FR-R343-5, dev review item 9): an unfinished stop puts the
 * position it had before the shuffle back, so a shuffled play neither leaves a resume point nor wipes one.
 */
internal data class SessionPlan(
    val startOverSeriesId: String? = null,
    val durationMs: Long = 0L,
    val creditsStartMs: Long? = null,
    val shuffle: Boolean = false,
    val priorPositionMs: Long = 0L,
    /** R375 (FR-R375-6) — an episode's `LastPlayedDate` before this play (null for a film, or never played). */
    val priorLastPlayed: String? = null,
    /** R375 — the episode was watched when this play started (a replay). */
    val watchedAtStart: Boolean = false,
    /** R375 — a shuffle's series anchor date (its last finished episode's `LastPlayedDate`) as the last Continue
     *  build saw it; null when the series had nothing finished or the start was not a shuffle. */
    val anchorLastPlayed: String? = null,
)

/**
 * R375 (FR-R375-6, owner decisions 2026-10-04) — the `LastPlayedDate` to write back once a stop has landed, or null
 * for none, so a shuffle or a replay that did not finish never becomes *the last episode played*:
 *
 * - **not finished**, a shuffled play or a replay of a watched episode: the prior date (none ⇒ nothing; the
 *   episode then stays unwatched with no position, so it can never be the anchor);
 * - **finished** and shuffled: `min(prior, anchor − 1 s)`, or `anchor − 1 s` with no prior, so it is never the
 *   newest finish; with no anchor, Jellyfin's date is left (accepted: it becomes the anchor);
 * - anything else (an ordinary play, a finished replay, a film): nothing — it moves the position, as it should.
 */
internal fun lastPlayedRestore(plan: SessionPlan?, finished: Boolean): String? {
    plan ?: return null
    if (!finished) return if (plan.shuffle || plan.watchedAtStart) plan.priorLastPlayed else null
    if (!plan.shuffle) return null
    val anchor = plan.anchorLastPlayed?.let { isoToEpochTicks(it) } ?: return null
    val cap = anchor - 10_000_000L
    val prior = plan.priorLastPlayed?.let { isoToEpochTicks(it) }
    return if (prior != null && prior <= cap) plan.priorLastPlayed else epochTicksToIso(cap)
}

/** FR-R343-4 — Start over clears the series once this much of the episode has played: 5 % of the file, or 60 s
 *  of playback when no duration is known (dev review item 3). */
internal fun startOverThresholdMs(durationMs: Long): Long = if (durationMs > 0) durationMs * 5 / 100 else 60_000L

/**
 * What a stop reports to Jellyfin, and whether the item must also be ticked through `mark`.
 *
 * - R347 (FR-R347-2) — finished under [playbackFinished] but below 90 %: reported at the end of the file, so
 *   Jellyfin's own rule ticks it and zeroes the position in the same write as the stop (a stop at 86 % landing
 *   after a separate mark would have left the 86 % resume point), and [markPlayed] for the explicit tick.
 * - FR-R343-5 — a shuffled entry that did not finish reports its position from before the shuffle.
 * - Anything else reports the playhead, as before.
 */
internal data class StopDecision(val reportMs: Long, val markPlayed: Boolean)

internal fun resolveStop(positionMs: Long, plan: SessionPlan?): StopDecision {
    val duration = plan?.durationMs ?: 0L
    val finished = playbackFinished(positionMs, duration, plan?.creditsStartMs)
    val belowNinety = duration > 0 && positionMs < duration * FINISHED_PERCENT / 100
    return when {
        finished && belowNinety -> StopDecision(maxOf(duration, positionMs), markPlayed = true)
        plan?.shuffle == true && !finished -> StopDecision(plan.priorPositionMs, markPlayed = false)
        else -> StopDecision(positionMs, markPlayed = false)
    }
}

/**
 * R343 (FR-R343-8, dev review item 11) — a shuffle (or a *Start over*) carried to a Ravilo screen. The screen
 * never decides what is next: the play push names it, and the screen posts `/api/remote/play` for that id. So the
 * order lives here, per screen, in memory only: the id playing now and the ids after it.
 *
 * - A play with a non-empty queue (from the phone) starts a context.
 * - A play for the queue's head with no queue (the screen's own *Next*) advances it.
 * - Any other play clears it (that leaves the shuffle, as a rail pick does on the phone).
 * - The watchdog's reap clears it too.
 */
internal class ScreenShuffles {
    data class Ctx(val currentId: String, val rest: List<String>, val shuffled: Boolean, val startOver: Boolean)

    private val mutex = Mutex()
    private val byDevice = HashMap<String, Ctx>()

    /** Records a `/remote/play` for [deviceId] and returns the context that now holds (null = none). */
    suspend fun onPlay(deviceId: String, itemId: String, queue: List<String>, startOver: Boolean): Ctx? = mutex.withLock {
        val now = when {
            queue.isNotEmpty() -> Ctx(itemId, queue.filter { it != itemId }, shuffled = true, startOver = startOver)
            startOver -> Ctx(itemId, emptyList(), shuffled = false, startOver = true)
            else -> byDevice[deviceId]?.takeIf { it.shuffled && it.rest.firstOrNull() == itemId }
                ?.let { Ctx(itemId, it.rest.drop(1), shuffled = true, startOver = false) }
        }
        if (now == null) byDevice.remove(deviceId) else byDevice[deviceId] = now
        now
    }

    /** The context whose current id is [itemId] on [deviceId] — how a screen's own start counts as shuffled. */
    suspend fun forStart(deviceId: String, itemId: String): Ctx? = mutex.withLock { byDevice[deviceId]?.takeIf { it.currentId == itemId } }

    suspend fun clear(deviceId: String) { mutex.withLock { byDevice.remove(deviceId) } }
}

internal val screenShuffles = ScreenShuffles()
