package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.DownloadOption
import dev.jellystructure.shared.tv.DownloadStartRequest
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A title the phone is asked to download: the page's own facts, which the record keeps beside the media (FR-7). */
data class DownloadTarget(
    val itemId: String,
    /** `movie` or `episode`. */
    val kind: String,
    val title: String,
    val mediaSourceId: String? = null,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMinutes: Int = 0,
)

/** The server said no for this viewer (FR-2, `403`): the control hides and `download.not_allowed` shows. */
class DownloadNotAllowed : Exception("downloading is switched off for this viewer")

/**
 * FR-6, FR-7 — a smaller copy resumes where it stopped: the 2 s segment the phone reached, less a few segments of margin (an
 * already-cached segment is skipped by the downloader, so starting early costs nothing and starting late would leave a hole).
 */
fun fromSegmentFor(percent: Int, runtimeMinutes: Int): Int {
    if (percent <= 0 || runtimeMinutes <= 0) return 0
    val segments = runtimeMinutes * 60 / 2
    return (segments * percent / 100 - RESUME_MARGIN_SEGMENTS).coerceAtLeast(0)
}

private const val RESUME_MARGIN_SEGMENTS = 5

/** The ticket answers that mean "this URL is no good any more" (expired, or re-minted): ask `start` again (FR-4). */
fun isExpiredTicket(status: Int?): Boolean = status == 401 || status == 403 || status == 410

/**
 * DEB-4070 (DEB-3995 FR-2, FR-3, FR-5, FR-7) — what the pages talk to. It owns the queue: downloads run **one at a time in the order
 * asked for**, and a download takes its ticket only when its turn comes, because the server runs one download stream at a time and a
 * `start` from this phone replaces the one it holds (FR-6). Nothing here writes Jellyfin or invents catalog rows.
 */
class DownloadsController(
    private val userId: () -> String?,
    private val api: DownloadApi,
    private val engine: DownloadEngine,
    private val library: DownloadLibrary,
    private val prefs: DownloadPrefs,
    private val baseUrl: () -> String,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val waitingRetryMs: Long = 5_000L,
    private val maxRenewals: Int = 3,
) {
    private val _allowed = MutableStateFlow(true)
    /** False after a `403` (FR-2): the control hides and `download.not_allowed` shows. Reset by [resetAllowed] on the next config read. */
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    private val _phases = MutableStateFlow<Map<String, DownloadPhase>>(emptyMap())
    /** This viewer's downloads by item id. */
    val phases: StateFlow<Map<String, DownloadPhase>> = _phases.asStateFlow()

    private val pumpLock = Mutex()
    private val renewals = HashMap<String, Int>()
    private val lastPercent = HashMap<String, Int>()
    private val renewedAt = HashMap<String, Int>()
    private val startFailures = HashMap<String, Int>()
    private var tick = 0L

    val wifiOnly: Boolean get() = prefs.wifiOnly
    fun setWifiOnly(on: Boolean) { prefs.wifiOnly = on }
    fun lastQuality(kind: String): String? = prefs.lastQuality(kind)
    fun freeBytes(): Long = engine.freeBytes()
    fun resetAllowed() { _allowed.value = true }

    init {
        scope.launch {
            engine.phases.collect { onEngineChanged(it) }
        }
    }

    /** FR-4 — the choices for one title. A `403` hides the control (FR-2) and throws [DownloadNotAllowed]. */
    suspend fun options(itemId: String, mediaSourceId: String? = null): List<DownloadOption> = guarded {
        api.options(itemId, mediaSourceId).options
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: TvApiError.Http) {
        if (e.status == 403) { _allowed.value = false; throw DownloadNotAllowed() }
        throw e
    }

    /** FR-3, FR-5 — queue [targets] at [option], in the order given; the last quality picked is remembered (FR-17). */
    fun download(targets: List<DownloadTarget>, option: DownloadOption) {
        val u = userId() ?: return
        if (targets.isEmpty()) return
        targets.forEach { t ->
            library.upsert(
                u,
                DownloadRecord(
                    itemId = t.itemId, kind = t.kind, title = t.title, seriesId = t.seriesId, seriesTitle = t.seriesTitle,
                    season = t.season, episode = t.episode, mediaSourceId = t.mediaSourceId, quality = option.quality,
                    height = option.height, bytes = option.bytes, runtimeMinutes = t.runtimeMinutes,
                    queuedAt = now() * 1000 + (tick++ % 1000), state = RecordState.QUEUED,
                ),
            )
        }
        prefs.setLastQuality(targets.first().kind, option.quality)
        refresh()
        scope.launch { pump() }
    }

    /** *Retry* on a failure: back to the end of the queue, then a fresh ticket. */
    fun retry(itemId: String) {
        val u = userId() ?: return
        val r = library.get(u, itemId) ?: return
        library.upsert(u, r.copy(state = RecordState.QUEUED, queuedAt = now() * 1000 + (tick++ % 1000)))
        renewals.remove(downloadId(u, itemId))
        renewedAt.remove(downloadId(u, itemId))
        startFailures.remove(downloadId(u, itemId))
        refresh()
        scope.launch { pump() }
    }

    fun pause(itemId: String) { userId()?.let { engine.pause(downloadId(it, itemId)) } }
    fun resume(itemId: String) { userId()?.let { engine.resume(downloadId(it, itemId)) } }

    /** Cancel or delete: the space comes back at once, and the record goes with the media. */
    fun delete(itemId: String) {
        val u = userId() ?: return
        engine.remove(downloadId(u, itemId))
        library.remove(u, itemId)
        renewals.remove(downloadId(u, itemId))
        startFailures.remove(downloadId(u, itemId))
        lastPercent.remove(downloadId(u, itemId))
        refresh()
        scope.launch { pump() }
    }

    /** Re-read this viewer's downloads: the app calls it when the signed-in profile changes. */
    fun refresh() {
        val u = userId()
        if (u == null) { _phases.value = emptyMap(); return }
        val out = HashMap<String, DownloadPhase>()
        library.records(u).filter { it.state == RecordState.QUEUED }.forEach { out[it.itemId] = DownloadPhase.Queued }
        engine.phases.value.forEach { (id, p) ->
            if (downloadUserId(id) == u && p != DownloadPhase.None) out[downloadItemId(id)] = p
        }
        // A `start` the server refused for good (the title is gone, the quality is not offered) is a failure the viewer can retry or delete.
        startFailures.forEach { (id, status) -> if (downloadUserId(id) == u) out[downloadItemId(id)] = DownloadPhase.Failed(status) }
        _phases.value = out
    }

    private suspend fun onEngineChanged(all: Map<String, DownloadPhase>) {
        refresh()
        val u = userId() ?: return
        all.forEach { (id, p) ->
            if (downloadUserId(id) != u) return@forEach
            when (p) {
                is DownloadPhase.Running -> lastPercent[id] = p.percent
                is DownloadPhase.Paused -> lastPercent[id] = p.percent
                else -> {}
            }
            when {
                // A fresh ticket that gets nowhere (Running at the same percent, then rejected again) must not renew for ever:
                // the count resets only when the download has made progress since the last renewal, or has finished.
                p == DownloadPhase.Downloaded -> { renewals.remove(id); renewedAt.remove(id) }
                p is DownloadPhase.Running && p.percent > (renewedAt[id] ?: -1) && renewedAt.containsKey(id) -> { renewals.remove(id); renewedAt.remove(id) }
                p is DownloadPhase.Failed && isExpiredTicket(p.httpStatus) -> renew(id, p)
                p == DownloadPhase.WaitingServer -> scope.launch { delay(waitingRetryMs); renewIfStillWaiting(id) }
            }
        }
        pump()
    }

    private suspend fun renewIfStillWaiting(id: String) {
        if (engine.phases.value[id] == DownloadPhase.WaitingServer) renew(id, DownloadPhase.WaitingServer)
    }

    /** A paused or expired download asks `start` again and resumes where it stopped (FR-4, FR-7); a copy passes the segment it reached (FR-6). */
    private suspend fun renew(id: String, from: DownloadPhase) {
        val u = userId() ?: return
        val record = library.get(u, downloadItemId(id)) ?: return
        val n = (renewals[id] ?: 0) + 1
        if (from is DownloadPhase.Failed && n > maxRenewals) return
        renewals[id] = n
        val percent = lastPercent[id] ?: 0
        renewedAt[id] = percent
        runCatching { start(u, record, fromSegment = if (record.quality == "original") null else fromSegmentFor(percent, record.runtimeMinutes)) }
    }

    private suspend fun start(u: String, record: DownloadRecord, fromSegment: Int?) {
        val ticket = guarded {
            api.start(
                DownloadStartRequest(
                    itemId = record.itemId, mediaSourceId = record.mediaSourceId, quality = record.quality, fromSegment = fromSegment,
                ),
            )
        }
        engine.enqueue(DownloadJob(downloadId(u, record.itemId), record, ticket, baseUrl()))
        library.upsert(u, record.copy(state = RecordState.STARTED))
        refresh()
    }

    /** Hand the next waiting download to the engine when it is idle: one at a time, in the order asked for. */
    suspend fun pump(): Unit = pumpLock.withLock {
        val u = userId() ?: return@withLock
        if (engine.busy()) return@withLock
        val next = library.nextQueued(u) ?: return@withLock
        try {
            start(u, next, fromSegment = null)
        } catch (e: DownloadNotAllowed) {
            // FR-2: the operator switched this viewer off; what is queued will not run, and the control is gone.
            library.remove(u, next.itemId)
            refresh()
        } catch (e: TvApiError.Http) {
            if (e.status == 503) {
                // The server is busy or Jellyfin cannot be asked: stays queued and shows "waiting"; try again shortly.
                scope.launch { delay(e.retryAfterSeconds?.times(1000L) ?: waitingRetryMs); pump() }
            } else {
                // 404 / 400: the title is gone or the quality is not offered any more; a failure the viewer can retry or delete.
                library.upsert(u, next.copy(state = RecordState.STARTED))
                startFailures[downloadId(u, next.itemId)] = e.status
                refresh()
            }
        } catch (e: Exception) {
            // Offline right now: stays queued, the next engine change or pump tries again.
        }
    }
}
