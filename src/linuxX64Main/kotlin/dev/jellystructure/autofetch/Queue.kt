package dev.jellystructure.autofetch

import dev.jellystructure.filefix.FileFixShell
import dev.jellystructure.filefix.PosixFileFixShell
import dev.jellystructure.log.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * DEB-2477 (DEB-2474, FR-8, FR-9) — *Not on TMDB yet*. A fetched episode TMDB has no such `SxxEyy` for is filed here, carrying
 * the page's own words (which live on the fetcher's item row, FR-8.3) and three stills cut on this server (FR-9.4). Every day the
 * season of each queued episode is asked about on TMDB explicitly (FR-9.3); when TMDB returns the episode the row clears itself and
 * the episode is ordinary, except for a field TMDB leaves blank, which keeps the page's text (FR-8.4).
 *
 * A page-only fetcher's episodes never enter the queue and nothing here re-checks them (FR-15.6): the caller never files one, and
 * [recheckDue] skips a row whose fetcher is page-only.
 */
class NotOnTmdbQueue(
    private val store: AutofetchStore,
    private val tools: MediaTools,
    private val world: AutofetchWorld,
    private val shell: FileFixShell = PosixFileFixShell,
) {
    /**
     * FR-9.1 — files [item], just placed at [path], when its fetcher is one the queue is for. Returns true when a row was filed.
     * Only a TMDB fetcher whose pattern is proven (Q1): a page-only fetcher never files (FR-15.6), and an unproven one does not place an
     * episode TMDB lacks at all (FR-4.3).
     */
    suspend fun file(f: Fetcher, item: FetchItem, path: String, durationSec: Double): Boolean {
        if (f.pageOnly || !f.proven || f.tmdbId == null) return false
        val season = item.season ?: return false
        val episode = item.episode ?: return false
        // The marker is field by field: a field the page left empty has no page text for a blank TMDB field to protect.
        store.fileQueue(f.id, item.itemId, season, episode, world.nowSec(), !item.title.isNullOrBlank(), !item.description.isNullOrBlank(), emptyList())
        val row = store.queueRow(f.id, item.itemId) ?: return false
        try { cutStills(row, path, durationSec) } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException && !currentCoroutineContext().isActive) throw e
            Logger.warn("auto-fetch: stills for ${item.itemId} failed: ${e.message}", "autofetch")
        }
        return true
    }

    private suspend fun cutStills(row: QueueRow, path: String, durationSec: Double) {
        val dir = "${world.stillsDir()}/${row.id}"
        shell.run("mkdir -p ${q(dir)}")
        val moments = tools.stills(path, durationSec, (0 until STILLS).map { stillFile(row.id, it) })
        store.queueStills(row.id, moments)
    }

    fun stillFile(rowId: Long, n: Int) = "${world.stillsDir()}/$rowId/$n.jpg"

    /** FR-9.4 — *Pick a different moment*: slot [n] is cut again at [atSec]. False when there is no such row, slot or file. */
    suspend fun cutAgain(rowId: Long, n: Int, atSec: Int): Boolean {
        val row = store.queueRow(rowId) ?: return false
        if (n !in 0 until STILLS || atSec < 0) return false
        val item = store.item(row.fetcherId, row.itemId) ?: return false
        val path = item.placedPath?.takeIf { shell.exists(it) } ?: return false
        shell.run("mkdir -p ${q("${world.stillsDir()}/${row.id}")}")
        if (!tools.frameAt(path, atSec, stillFile(row.id, n))) return false
        val moments = row.stills.toMutableList()
        while (moments.size <= n) moments += 0
        moments[n] = atSec
        store.queueStills(row.id, moments)
        return true
    }

    /** FR-9.3 — *Mark as submitted* is the admin's note that they entered it on TMDB; nothing is ever sent (Non-goals). The row stays, marked. */
    fun markSubmitted(rowId: Long): Boolean {
        val row = store.queueRow(rowId) ?: return false
        if (row.state == "cleared") return false
        store.queueSubmitted(rowId, world.nowSec())
        return true
    }

    /** An episode that left the library (retention) or a deleted fetcher: its row and its stills go. */
    suspend fun forget(fetcherId: String, itemId: String) {
        val row = store.queueRow(fetcherId, itemId) ?: return
        shell.run("rm -rf ${q("${world.stillsDir()}/${row.id}")}")
        store.dropQueue(fetcherId, itemId)
    }

    suspend fun forgetFetcher(fetcherId: String) {
        for (row in store.queueOf(fetcherId)) shell.run("rm -rf ${q("${world.stillsDir()}/${row.id}")}")
    }

    /**
     * FR-9.3 — the daily check. A row is due when TMDB lacked its episode at the last look (or has it but leaves a field blank that the
     * page fills) and a day has passed. One explicit re-sync per fetcher and season. Returns how many rows it asked about.
     */
    suspend fun recheckDue(now: Long): Int {
        var asked = 0
        val due = store.queue().filter { it.needsCheck && (it.lastCheckAt ?: 0L) + DAY_SEC <= now }
        for ((key, rows) in due.groupBy { it.fetcherId to it.season }) {
            val f = store.fetcher(key.first)
            if (f == null || f.pageOnly || f.tmdbId == null) continue
            val items = rows.associateWith { store.item(it.fetcherId, it.itemId) }
            // A file moved or deleted by hand: nothing to carry the page's words on, and nothing to ask TMDB about.
            val paths = items.values.mapNotNull { it?.placedPath }.filter { shell.exists(it) }
            if (paths.isEmpty()) { for (r in rows) store.queueChecked(r.id, now); continue }
            val answer = world.resyncSeason(f.seriesPath, f.tmdbId, key.second, paths)
            for ((row, item) in items) {
                store.queueChecked(row.id, now)
                asked++
                val after = item?.placedPath?.let { answer?.get(it) } ?: continue
                apply(row, item, after, now)
            }
        }
        return asked
    }

    /** Applies what the re-sync found to one row. */
    private fun apply(row: QueueRow, item: FetchItem, after: EpisodeAfterSync, now: Long) {
        if (!after.tmdbHasEpisode) return
        if (row.state != "cleared") store.queueCleared(row.id, now)
        // TMDB wins field by field: a field that no longer reads as the page's own words is TMDB's, and its marker goes.
        val title = row.titleFromPage && after.title.orEmpty() == item.title.orEmpty()
        val overview = row.overviewFromPage && after.overview.orEmpty() == item.description.orEmpty()
        if (title != row.titleFromPage || overview != row.overviewFromPage) store.queueMarkers(row.id, title, overview)
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        const val STILLS = 3
        const val DAY_SEC = 86_400L
    }
}
