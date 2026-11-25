package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.PrewarmRequest
import dev.jellystructure.shared.tv.RememberedChoice
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    /** The item held now, or null. While a tile's hold has not resolved its item, the tile's media id. */
    var item: String? = null
        private set

    /** DEB-5648 (DEB-5640, FR-50) — the media id of the Home tile this hold came from, until its page takes it over ([settle]) or it is let go. */
    var holdFor: String? = null
        private set
    private var job: Job? = null
    private var mayBeWarm = false
    private var pending = false

    /** Hold [itemId] for [seriesId]'s remembered audio. [dwellMs] passes before the first post. Holding the same item again is a no-op. */
    fun warm(itemId: String, seriesId: String?, dwellMs: Long = 0L) {
        if (item == itemId) return
        start(itemId, seriesId, dwellMs, null, null)
    }

    /**
     * DEB-5648 (FR-50) — a Home tile that holds focus: hold what its page's Play would play, [dwellMs] after the focus came. A film's
     * item is [mediaId]; a series' is read by [resolve] once the dwell has passed (null warms nothing, and nothing is retried).
     * Holding the same tile again is a no-op.
     */
    fun warmTile(mediaId: String, seriesId: String?, dwellMs: Long, resolve: (suspend () -> String?)? = null) {
        if (holdFor == mediaId) return
        start(mediaId, seriesId, dwellMs, mediaId, resolve)
    }

    /**
     * DEB-5648 (FR-50) — the page of the tile held is ready and plays [itemId]: the hold carries on with no second dwell and no new
     * post when it is that item (or has not resolved yet, so it takes [itemId]); a hold that resolved to another one is re-targeted.
     */
    fun settle(itemId: String, seriesId: String?) {
        if (item == itemId) { holdFor = null; return }
        if (pending) { item = itemId; pending = false; holdFor = null; return }
        warm(itemId, seriesId, DWELL_MS)
    }

    private fun start(itemId: String, seriesId: String?, dwellMs: Long, tile: String?, resolve: (suspend () -> String?)?) {
        cancel()
        item = itemId
        holdFor = tile
        pending = resolve != null
        job = GlobalScope.launch {
            if (dwellMs > 0) delay(dwellMs)
            if (resolve != null && pending) {
                val resolved = resolve()
                ensureActive()   // the read's runCatching can swallow this job's cancellation; a newer hold must not be touched
                if (pending) {
                    if (resolved == null) { item = null; return@launch }   // nothing to warm; holdFor stays so no redraw reads again
                    item = resolved
                    pending = false
                }
            }
            val held = item ?: return@launch
            val audio = remembered(seriesId)
            val request = PrewarmRequest(held, capabilities(), audioLanguage = audio?.audioLanguage, audioVariant = audio?.audioVariant)
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
        val held = item
        val post = mayBeWarm
        release()
        if (post && held != null) GlobalScope.launch { api.cancelPrewarm(held) }
    }

    /** The item is being played (or has been dropped some other way): no more posts, and no cancel. */
    fun release() {
        job?.cancel()
        job = null
        item = null
        holdFor = null
        mayBeWarm = false
        pending = false
    }

    companion object {
        const val DWELL_MS = 2_000L
        const val REPOST_MS = 30_000L
    }
}
