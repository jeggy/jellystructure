package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.shared.tv.SessionQueueEntry
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * R370 (FR-R370-7, amendment 2026-10-12) — the video queue a `session_load` handed this device, held for the play
 * that opens right behind it.
 *
 * Found on DEB-781 (Stue TV, a `.debug` build, reproduced twice): the `session_load` handler collapsed the envelope
 * to a single id, so a two-episode queue handed over by the server played item 1 and then showed the *last item*
 * credits card — *End of film*, no countdown, nothing after it. The player was right: it had been told it was
 * playing the only item there was.
 *
 * Why a holder and not a field on the push: a `session_load` of a film or an episode opens the player by emitting a
 * [dev.jellystructure.shared.tv.PlayItemEnvelope] on `livePlayItem` (R293's on-screen gate and 309's probe both sit
 * on that road), and that envelope is the WIRE type — an app-internal hop must not grow a field on it, and
 * FR-R370-7 keeps the wire as it is. The hand-over is claimed by item id and expires after [WINDOW_MS], so a play
 * of the same episode later, from the app's own UI, cannot pick up a queue that is no longer anyone's.
 */
internal object ServerQueueHandover {
    /** A hand-over is claimed by the play that follows it within this long; it never opens later than that. */
    private const val WINDOW_MS = 60_000L

    /** The queue [sessionId] handed over, in play order, and [at] — where the claiming item sits in it. */
    data class Claim(val sessionId: String, val queue: List<SessionQueueEntry>, val at: Int)

    private var sessionId: String? = null
    private var queue: List<SessionQueueEntry> = emptyList()
    private var offeredAt: Long = 0L

    /** A `session_load` of kind `film`/`episode`: its queue, for the play about to open. */
    fun offer(sessionId: String, queue: List<SessionQueueEntry>, now: Long = nowMs()) {
        this.sessionId = sessionId
        this.queue = queue
        this.offeredAt = now
    }

    /** The hand-over when [itemId] is an entry of one still inside the window; null for any other play. */
    fun claim(itemId: String, now: Long = nowMs()): Claim? {
        val id = sessionId ?: return null
        if (now - offeredAt > WINDOW_MS) return null
        val at = queue.indexOfFirst { it.id == itemId }
        if (at < 0) return null
        return Claim(id, queue, at)
    }

    fun clear() {
        sessionId = null
        queue = emptyList()
        offeredAt = 0L
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}

/**
 * R370 (FR-R370-7) — the hand-over's [items] with the titles the SERVER holds for them: `GET
 * /api/tv/playback/sessions/{id}` already answers a window of the queue with each entry's title and subtitle
 * (R369's own resolution — an episode's is the series' title and its `S01E02` code). The app derives nothing; an
 * id the window does not reach keeps its bare entry, and so does every id when the detail is slow or refused — the
 * next-up card then has an id to count down to and no label, which is the old behaviour of a card with no labels.
 */
internal suspend fun handoverQueue(api: TvApiClient, sessionId: String, items: List<String>): List<SessionQueueEntry> {
    val bare = items.map { SessionQueueEntry(it) }
    // Bounded: the play must not wait on the detail. 1.5 s is far past a LAN round trip to our own backend, and the
    // timeout is the inner call's own — `withTimeoutOrNull` answers null for it, `runCatching` for a refusal or a
    // dropped connection. Either way the hand-over goes on with bare ids rather than not going on.
    val detail = runCatching { withTimeoutOrNull(1_500) { api.playbackSessionDetail(sessionId) } }.getOrNull() ?: return bare
    val titled = detail.queue.associateBy { it.id }
    return items.map { titled[it] ?: SessionQueueEntry(it) }
}
