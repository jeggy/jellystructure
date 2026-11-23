package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.PrewarmRequest
import dev.jellystructure.shared.tv.RememberedChoice
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DEB-5396 (DEB-5394, FR-35, FR-36) — Android TV's `DetailPrewarm`, as one small helper for the two moments this app has: a title's
 * page (a 2 s dwell first) and the next episode (no dwell: the player decides when). It holds one item at a time.
 *
 * [warm] posts `prewarmPlayback` with the start's own capabilities and FR-23's remembered audio, and re-posts every 30 s while the
 * answer is `warm`; `direct`, `none` or a failure (a 404 from an older server too) ends the loop, and nothing is retried, shown or
 * waited on. [cancel] posts `prewarmCancel` at once, but only when an encode can be running: nothing is posted during the dwell, nor
 * after an answer that was not `warm`. [release] lets go without a cancel: the item is being played, and the server adopts the job.
 */
internal class ScreenPrewarm(
    private val api: TvApiClient,
    private val capabilities: () -> ClientCapabilities,
    private val remembered: (seriesId: String?) -> RememberedChoice?,
) {
    /** The item held now, or null. */
    var item: String? = null
        private set
    private var job: Job? = null
    private var mayBeWarm = false

    /** Hold [itemId] for [seriesId]'s remembered audio. [dwellMs] passes before the first post. Holding the same item again is a no-op. */
    fun warm(itemId: String, seriesId: String?, dwellMs: Long = 0L) {
        if (item == itemId) return
        cancel()
        item = itemId
        job = GlobalScope.launch {
            if (dwellMs > 0) delay(dwellMs)
            val audio = remembered(seriesId)
            val request = PrewarmRequest(itemId, capabilities(), audioLanguage = audio?.audioLanguage, audioVariant = audio?.audioVariant)
            while (true) {
                mayBeWarm = true   // from here on a cancel must reach the server, whether or not the answer has arrived
                val answer = api.prewarmPlayback(request)
                if (answer?.status != "warm") { mayBeWarm = false; return@launch }
                delay(REPOST_MS)
            }
        }
    }

    /** The viewer left the item without playing it: the loop ends and the server's job is stopped. */
    fun cancel() {
        val held = item ?: return
        val post = mayBeWarm
        release()
        if (post) GlobalScope.launch { api.cancelPrewarm(held) }
    }

    /** The item is being played (or has been dropped some other way): no more posts, and no cancel. */
    fun release() {
        job?.cancel()
        job = null
        item = null
        mayBeWarm = false
    }

    companion object {
        const val DWELL_MS = 2_000L
        const val REPOST_MS = 30_000L
    }
}
