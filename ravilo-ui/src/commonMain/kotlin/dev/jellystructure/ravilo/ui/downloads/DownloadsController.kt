package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.DownloadOption
import dev.jellystructure.shared.tv.DownloadStartRequest
import dev.jellystructure.shared.tv.DownloadWaitReason
import dev.jellystructure.shared.tv.CardPlayState
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

/** DEB-4072 — what the app must do after [DownloadsController.onReachable]. */
enum class Reconnect { OK, REJECTED, UNREACHABLE }

/** DEB-4072 — what the reconnect refresh asks the server (FR-13, FR-15): the outbox's three calls plus a title's page, as raw JSON. */
interface DownloadSyncApi : OutboxSink {
    /** The title's page route body: a film's `GET /api/tv/movie/{id}`, an episode's `GET /api/tv/series/{seriesId}`. 404 / 403 = no longer offered. */
    suspend fun title(kind: String, itemId: String, seriesId: String?): String
}

/**
 * FR-16 — the episode that follows [cur], **only if it is on the phone**: the next number in its season. Nothing is offered across a season
 * boundary or over a gap: whether episode 5 exists is the server's to say, and offline the phone cannot know that this one was the season's last.
 */
fun nextEpisodeOf(cur: DownloadRecord, downloaded: List<DownloadRecord>): DownloadRecord? {
    val s = cur.season ?: return null
    val e = cur.episode ?: return null
    return downloaded.firstOrNull { it.season == s && it.episode == e + 1 }
}

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
 * asked for**, and a download takes its ticket only when its turn comes, because a phone has one download stream and a `start` from it
 * replaces the one it holds (FR-6, DEB-4256). Nothing here writes Jellyfin or invents catalog rows.
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
    /** DEB-4072 — what reaches the server after a spell offline (FR-13, FR-15). Null on a platform with no outbox. */
    private val sync: DownloadSyncApi? = null,
    private val outbox: ProgressOutbox? = null,
    /** DEB-4072 (FR-13) — writes a title's refreshed `item` payload verbatim beside its media: (user id, item id, JSON text). */
    private val saveItem: (String, String, String) -> Unit = { _, _, _ -> },
    /** DEB-4072 (FR-16) — what is saved beside each download, for an offline play. Null on a platform that cannot play one. */
    private val media: LocalMediaSource? = null,
) {
    private val _allowed = MutableStateFlow(true)
    /** False after a `403` (FR-2): the control hides and `download.not_allowed` shows. Reset by [resetAllowed] on the next config read. */
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    private val _phases = MutableStateFlow<Map<String, DownloadPhase>>(emptyMap())
    /** This viewer's downloads by item id. */
    val phases: StateFlow<Map<String, DownloadPhase>> = _phases.asStateFlow()

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    /** DEB-4071 — this viewer's records (what the Downloads page lists), re-read whenever [refresh] runs. */
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    private val _otherBytes = MutableStateFlow(0L)
    /** FR-12 — what every other profile on this phone holds on the disk: a number, never a title. */
    val otherBytes: StateFlow<Long> = _otherBytes.asStateFlow()

    private val _storageFreeBytes = MutableStateFlow(engine.freeBytes())
    /** Device-reported free space; refreshed after file removal, including Media3's asynchronous removal. */
    val storageFreeBytes: StateFlow<Long> = _storageFreeBytes.asStateFlow()

    private val _waits = MutableStateFlow<Map<String, WaitInfo>>(emptyMap())
    /** DEB-4258 (FR-7) — what each copy that waits for the server last heard from it, by item id. The line reads it through [waitLine]. */
    val waits: StateFlow<Map<String, WaitInfo>> = _waits.asStateFlow()

    private val _originalOffers = MutableStateFlow<Map<String, DownloadOption>>(emptyMap())
    /** DEB-4258 (FR-7) — a waiting copy's original, by item id, when it is listed for this phone and fits: the row offers `download.get_original`. */
    val originalOffers: StateFlow<Map<String, DownloadOption>> = _originalOffers.asStateFlow()

    private val pumpLock = Mutex()
    private val renewals = HashMap<String, Int>()
    private val lastPercent = HashMap<String, Int>()
    private val renewedAt = HashMap<String, Int>()
    private val startFailures = HashMap<String, Int>()
    /** Downloads whose own `start` was answered 503 (every card is full, or the copy cannot be made right now): they read *Waiting for the server* while they wait their turn. */
    private val startWaiting = HashSet<String>()
    /** Each wait's start, so the timer that ends the generic first 10 s belongs to its own wait and never to a later one. */
    private val waitEpochs = HashMap<String, Long>()
    /** The original `GET download/options` listed for a copy that waits, asked once when its wait began. */
    private val originals = HashMap<String, DownloadOption>()
    /** Copies whose last `start` answered `unavailable` since the queue last came round: the next turn asks for the next waiting copy (FR-7). */
    private val unavailableRound = LinkedHashSet<String>()
    private var tick = 0L
    private var waitCounter = 0L
    private var pumpRetry: kotlinx.coroutines.Job? = null
    private var inRetry = false
    /** The last thing a `start` threw that was neither an HTTP answer nor a cancel, so a stall that reads *Queued* has a reason a test or a log can see. */
    var lastStartError: Throwable? = null
        private set

    val wifiOnly: Boolean get() = prefs.wifiOnly
    fun setWifiOnly(on: Boolean) { prefs.wifiOnly = on }
    fun lastQuality(kind: String): String? = prefs.lastQuality(kind)
    fun freeBytes(): Long = engine.freeBytes()
    fun totalBytes(): Long = engine.totalBytes()
    /** What a delete of this title frees, by the size the sheet showed (FR-10's confirm names it). */
    fun recordBytes(itemId: String): Long = userId()?.let { library.get(it, itemId)?.bytes } ?: 0L
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

    /** FR-3 — queue [targets] at [option], in the order given; the last quality picked is remembered (FR-17). */
    fun download(targets: List<DownloadTarget>, option: DownloadOption) = downloadEach(targets.map { it to option })

    /** FR-5 — a season: each episode at the quality's own size for that episode, in the order given. */
    fun downloadEach(items: List<Pair<DownloadTarget, DownloadOption>>) {
        val u = userId() ?: return
        if (items.isEmpty()) return
        items.forEach { (t, option) ->
            library.upsert(
                u,
                DownloadRecord(
                    itemId = t.itemId, kind = t.kind, title = t.title, seriesId = t.seriesId, seriesTitle = t.seriesTitle,
                    season = t.season, episode = t.episode, mediaSourceId = t.mediaSourceId, quality = option.quality,
                    height = option.height, bytes = option.bytes, runtimeMinutes = t.runtimeMinutes,
                    queuedAt = now() * 1000 + (tick++ % 1000), state = RecordState.QUEUED,
                ),
            )
            startFailures.remove(downloadId(u, t.itemId))
        }
        prefs.setLastQuality(items.first().first.kind, items.first().second.quality)
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
        endWait(downloadId(u, itemId))
        refresh()
        pumpRetry?.cancel(); scope.launch { pump() }
    }

    fun pause(itemId: String) { userId()?.let { engine.pause(downloadId(it, itemId)) } }
    fun resume(itemId: String) { userId()?.let { engine.resume(downloadId(it, itemId)) } }

    /** Cancel or delete: the space comes back at once, and the record goes with the media. */
    fun delete(itemId: String) = deleteMany(listOf(itemId))

    // ─── DEB-4072: offline play, the outbox and the reconnect refresh ───

    /** FR-11 — whether this viewer has anything that plays with no connection: a download fully on the phone. */
    fun hasPlayableDownloads(): Boolean {
        val u = userId() ?: return false
        val phases = engine.phases.value
        return library.records(u).any { phases[downloadId(u, it.itemId)] == DownloadPhase.Downloaded }
    }

    /** FR-16 — the record of a download that is fully on this phone, or null (an offline play needs the media to be there). */
    fun playable(itemId: String): DownloadRecord? {
        val u = userId() ?: return null
        val r = library.get(u, itemId) ?: return null
        return r.takeIf { engine.phases.value[downloadId(u, itemId)] == DownloadPhase.Downloaded }
    }

    /** FR-16 — the episode after [itemId] **if it is also on the phone**: the next-episode offer appears only then. */
    fun nextDownloadedEpisode(itemId: String): DownloadRecord? {
        val u = userId() ?: return null
        val cur = library.get(u, itemId) ?: return null
        val sid = cur.seriesId ?: return null
        val phases = engine.phases.value
        val downloaded = library.records(u).filter { it.seriesId == sid && phases[downloadId(u, it.itemId)] == DownloadPhase.Downloaded }
        return nextEpisodeOf(cur, downloaded)
    }

    /**
     * FR-16 — the play of a download that is fully on the phone, or null. It plays in the normal player from local storage; its progress
     * and finish are queued writes (FR-15). The resume point is the position recorded offline, else the server's last word.
     */
    fun localPlay(itemId: String): LocalPlay? {
        val u = userId() ?: return null
        val source = media ?: return null
        if (playable(itemId) == null) return null
        return object : LocalPlay {
            override val itemId: String = itemId
            override suspend fun ticket(startPositionMs: Long?): dev.jellystructure.shared.tv.StreamTicket {
                val saved = source.read(u, itemId) ?: error("nothing saved for this download")
                val from = startPositionMs ?: offlineResumeMs(itemId) ?: library.get(u, itemId)?.serverResumeMs ?: 0L
                return buildLocalTicket(itemId, saved, from)
            }
            override fun recordProgress(positionMs: Long) = recordOfflineProgress(itemId, positionMs)
            override fun recordWatched() = recordOfflineWatched(itemId)
        }
    }

    /** FR-13, FR-16 — the saved picture of one of this viewer's downloads (`poster`, `backdrop`, `still`), for the page while offline. */
    fun savedArtwork(itemId: String, type: String): String? {
        val u = userId() ?: return null
        return media?.artwork(u, itemId, type)
    }

    /** FR-15 — what the server last said about this title, the base the outbox's conflict rule compares to. */
    private fun serverPlayOf(u: String, itemId: String): ServerPlay =
        library.get(u, itemId)?.let { ServerPlay(it.serverResumeMs, it.watched) } ?: ServerPlay()

    /** FR-15 — a play from a download records where it reached. Never the server's truth: it is a queued write. */
    fun recordOfflineProgress(itemId: String, positionMs: Long) {
        val u = userId() ?: return
        if (positionMs <= 0L) return   // a play that never moved leaves nothing to send, and must not reset the server's resume point
        outbox?.recordProgress(u, itemId, positionMs, now(), serverPlayOf(u, itemId))
    }

    /** FR-15 — a play from a download reached its finish. */
    fun recordOfflineWatched(itemId: String) {
        val u = userId() ?: return
        outbox?.recordWatched(u, itemId, now(), serverPlayOf(u, itemId))
    }

    /** FR-15 — the position recorded offline, for the downloaded copy while offline only; null online and for a finished title. */
    fun offlineResumeMs(itemId: String): Long? {
        val u = userId() ?: return null
        return outbox?.offlineResumeMs(u, itemId)
    }

    private val reachLock = Mutex()

    /**
     * DEB-4072 (FR-12, FR-13, FR-15) — the server answered after a spell offline. In this order: **send the outbox** (against the play
     * state the phone last knew, so a later play elsewhere wins), then **refresh every download** from the server. Returns what the app
     * must do: [Reconnect.REJECTED] on a 401 sends this profile to sign-in with its downloads kept; [Reconnect.UNREACHABLE] means the server
     * went away again part-way and nothing was lost.
     */
    suspend fun onReachable(): Reconnect = reachLock.withLock {
        val u = userId() ?: return@withLock Reconnect.OK
        val api = sync ?: return@withLock Reconnect.OK
        val drained = outbox?.drain(u, api)
        if (drained?.rejected == true) return@withLock Reconnect.REJECTED
        if (drained?.unreachable == true) return@withLock Reconnect.UNREACHABLE
        refreshFromServer(u, api)
    }

    private suspend fun refreshFromServer(u: String, api: DownloadSyncApi): Reconnect {
        val records = library.records(u)
        if (records.isEmpty()) return Reconnect.OK
        val play = try {
            api.playstate(records.map { it.itemId })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return if (e is TvApiError.Http && e.status == 401) Reconnect.REJECTED else Reconnect.UNREACHABLE
        }
        for (r in records) {
            val fresh = library.get(u, r.itemId) ?: continue   // deleted meanwhile
            val state = play[r.itemId].toServerPlay()
            var gone = fresh.gone
            try {
                val text = api.title(fresh.kind, fresh.itemId, fresh.seriesId)
                saveItem(u, fresh.itemId, text)   // verbatim, never merged (FR-13)
                gone = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is TvApiError.Http) {
                    when (e.status) {
                        401 -> return Reconnect.REJECTED
                        404, 403 -> gone = true   // FR-13: kept and playable until deleted, marked `download.gone`
                        else -> {}
                    }
                } else return Reconnect.UNREACHABLE
            }
            library.get(u, r.itemId)?.let { library.upsert(u, it.copy(watched = state.played, serverResumeMs = state.resumeMs, gone = gone)) }
        }
        refresh()
        return Reconnect.OK
    }

    /** Re-read this viewer's downloads: the app calls it when the signed-in profile changes. */
    fun refresh() {
        _storageFreeBytes.value = engine.freeBytes()
        val u = userId()
        if (u == null) { _phases.value = emptyMap(); _records.value = emptyList(); _otherBytes.value = 0L; return }
        _records.value = library.records(u)
        _otherBytes.value = otherHeldBytes(u)
        val out = HashMap<String, DownloadPhase>()
        library.records(u).filter { it.state == RecordState.QUEUED }.forEach { out[it.itemId] = DownloadPhase.Queued }
        engine.phases.value.forEach { (id, p) ->
            if (downloadUserId(id) == u && p != DownloadPhase.None) out[downloadItemId(id)] = p
        }
        // A `start` the server refused for good (the title is gone, the quality is not offered) is a failure the viewer can retry or delete.
        startFailures.forEach { (id, status) -> if (downloadUserId(id) == u) out[downloadItemId(id)] = DownloadPhase.Failed(status) }
        // FR-6: a `start` the server answered 503 waits, and says so, instead of reading as a plain queue place.
        startWaiting.forEach { id -> if (downloadUserId(id) == u && out[downloadItemId(id)] == DownloadPhase.Queued) out[downloadItemId(id)] = DownloadPhase.WaitingServer }
        _phases.value = out
        _originalOffers.value = originalOffersFor(u, out)
    }

    /**
     * FR-7 — a copy that waits for the server offers its original when `options` listed one for this phone and it fits by FR-3's rule, with
     * what the copy already holds counted as free. Not an original itself, not paused, not waiting for Wi-Fi or a connection (those are other phases).
     */
    private fun originalOffersFor(u: String, phases: Map<String, DownloadPhase>): Map<String, DownloadOption> {
        if (originals.isEmpty()) return emptyMap()
        val free = engine.freeBytes()
        val out = HashMap<String, DownloadOption>()
        for ((item, phase) in phases) {
            if (phase != DownloadPhase.WaitingServer) continue
            val option = originals[item] ?: continue
            val r = library.get(u, item) ?: continue
            if (r.quality == "original") continue
            val held = r.bytes * (lastPercent[downloadId(u, item)] ?: 0).coerceIn(0, 100) / 100
            if (fits(option.bytes, free + held)) out[item] = option
        }
        return out
    }

    /** What the other profiles hold on the disk now: a finished one's whole size, a running one's part. Counts; never names. */
    private fun otherHeldBytes(u: String): Long {
        val phases = engine.phases.value
        return library.users().filter { it != u }.sumOf { other ->
            library.records(other).sumOf { r ->
                heldBytes(r, phases[downloadId(other, r.itemId)] ?: DownloadPhase.None)
            }
        }
    }

    /**
     * FR-9, FR-10 — what the page can do with the viewer's own downloads. Every one of these frees the space at once: the engine drops the
     * media, the record goes, and [refresh] puts the new totals on the page before the call returns.
     */
    fun deleteMany(itemIds: Collection<String>) {
        val u = userId() ?: return
        val freeBefore = engine.freeBytes()
        itemIds.toSet().forEach { id ->
            engine.remove(downloadId(u, id))
            library.remove(u, id)
            val key = downloadId(u, id)
            renewals.remove(key); startFailures.remove(key); endWait(key); lastPercent.remove(key); renewedAt.remove(key)
        }
        refresh()
        if (itemIds.isNotEmpty()) watchFreedSpace(freeBefore)
        pumpRetry?.cancel(); scope.launch { pump() }
    }

    /** *Delete watched* (FR-10): every download on the phone marked watched. A running one is not "watched". */
    fun deleteWatched() = deleteMany(
        buildDownloadsPage(_records.value, _phases.value).downloaded.filter { it.watched }.map { it.itemId },
    )

    /** *Delete all* (FR-10): everything of this profile, running entries included. */
    fun deleteAll() = deleteMany(_records.value.map { it.itemId })

    /** FR-12 — *Other profiles on this phone · Delete*: their media and records go, and their titles are never shown. */
    fun deleteOtherProfiles() {
        val u = userId() ?: return
        val freeBefore = engine.freeBytes()
        engine.phases.value.keys.filter { downloadUserId(it) != u }.forEach { engine.remove(it) }
        library.users().filter { it != u }.forEach { library.removeAll(it) }
        refresh()
        watchFreedSpace(freeBefore)
    }

    private fun watchFreedSpace(freeBefore: Long) {
        if (_storageFreeBytes.value != freeBefore) return
        // Media3 removes cached bytes after sendRemoveDownload returns. Keep reading through a batch's final removal.
        scope.launch { repeat(50) { delay(100); _storageFreeBytes.value = engine.freeBytes() } }
    }

    /**
     * FR-9, FR-10 — the server's word on one title, read from a page the viewer opened (never a write): the page keeps *Watched* and *Delete
     * watched* true to it. Slice 5's reconnect refresh sets the same fields for every download at once. Unknown ids are ignored.
     */
    fun observe(itemId: String, watched: Boolean) {
        val u = userId() ?: return
        val r = library.get(u, itemId) ?: return
        if (r.watched == watched) return
        library.upsert(u, r.copy(watched = watched))
        refresh()
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
                // A segment's 503 (or a copy the server ended for room) carries no reason; the first renewal's answer gives it one.
                p == DownloadPhase.WaitingServer && _waits.value[downloadItemId(id)] == null -> {
                    library.get(u, downloadItemId(id))?.let { beginWait(id, it, DownloadWaitReason.Unknown) }
                    scope.launch { delay(waitingRetryMs); renewIfStillWaiting(id) }
                }
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
        try {
            start(u, record, fromSegment = if (record.quality == "original") null else fromSegmentFor(percent, record.runtimeMinutes))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: TvApiError.Http) {
            // FR-7: a copy that waits keeps asking every Retry-After, and its line follows each answer.
            if (e.status == 503 && from == DownloadPhase.WaitingServer) {
                noteWait(u, record, e)
                refresh()
                scope.launch { delay(e.retryAfterSeconds?.times(1000L) ?: waitingRetryMs); renewIfStillWaiting(id) }
            }
        } catch (_: Throwable) {
        }
    }

    /** FR-7 — the latest waiting answer of one copy: its reason (an `unavailable` one also sends the queue on to the next copy), and the start of the wait if it is new. */
    private fun noteWait(u: String, record: DownloadRecord, e: TvApiError.Http) {
        val id = downloadId(u, record.itemId)
        val reason = DownloadWaitReason.parse(e.message)
        if (reason == DownloadWaitReason.Unavailable) unavailableRound.add(id) else unavailableRound.remove(id)
        startWaiting.add(id)
        beginWait(id, record, reason)
    }

    private fun beginWait(id: String, record: DownloadRecord, reason: DownloadWaitReason) {
        val item = downloadItemId(id)
        val old = _waits.value[item]
        if (old == null) {
            val epoch = ++waitCounter
            waitEpochs[id] = epoch
            scope.launch {
                delay(WAIT_GENERIC_MS)
                if (waitEpochs[id] == epoch) _waits.value[item]?.let { _waits.value = _waits.value + (item to it.copy(long = true)) }
            }
            if (record.quality != "original") scope.launch { askForOriginal(id, item, record) }
        }
        _waits.value = _waits.value + (item to WaitInfo(reason, long = old?.long ?: false))
    }

    /** FR-7 — "The phone asks `options` when the wait begins": a failed ask just means no offer. */
    private suspend fun askForOriginal(id: String, item: String, record: DownloadRecord) {
        val original = try {
            api.options(item, record.mediaSourceId).options.firstOrNull { it.quality == "original" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) { null } ?: return
        if (_waits.value[item] == null) return   // the wait ended while the answer was on its way
        originals[item] = original
        refresh()
    }

    private fun endWait(id: String) {
        val item = downloadItemId(id)
        startWaiting.remove(id); waitEpochs.remove(id); unavailableRound.remove(id); originals.remove(item)
        if (item in _waits.value) _waits.value = _waits.value - item
    }

    /**
     * FR-7 — **Download the original instead**. The entry becomes the original and keeps its place in the queue; what the copy had saved is freed
     * at once, and it starts as any queued original does. The remembered quality ([prefs]) is not touched: this was not a pick in the sheet.
     */
    fun getOriginal(itemId: String) {
        val u = userId() ?: return
        val r = library.get(u, itemId) ?: return
        val option = _originalOffers.value[itemId] ?: return
        val id = downloadId(u, itemId)
        val freeBefore = engine.freeBytes()
        if ((engine.phases.value[id] ?: DownloadPhase.None) != DownloadPhase.None) engine.remove(id)
        library.upsert(u, r.copy(quality = "original", height = null, bytes = option.bytes, state = RecordState.QUEUED))
        renewals.remove(id); renewedAt.remove(id); lastPercent.remove(id); startFailures.remove(id)
        endWait(id)
        refresh()
        watchFreedSpace(freeBefore)
        pumpRetry?.cancel(); scope.launch { pump() }
    }

    private suspend fun start(u: String, record: DownloadRecord, fromSegment: Int?) {
        val ticket = guarded {
            api.start(
                DownloadStartRequest(
                    itemId = record.itemId, mediaSourceId = record.mediaSourceId, quality = record.quality, fromSegment = fromSegment,
                ),
            )
        }
        endWait(downloadId(u, record.itemId))
        engine.enqueue(DownloadJob(downloadId(u, record.itemId), record, ticket, baseUrl()))
        // FR-15 — we are online right now: take the server's play state as the base an offline play's queued write is compared to later.
        val base = sync?.let { runCatching { it.playstate(listOf(record.itemId))[record.itemId].toServerPlay() }.getOrNull() }
        library.upsert(u, (library.get(u, record.itemId) ?: record).let { r ->
            r.copy(state = RecordState.STARTED, watched = base?.played ?: r.watched, serverResumeMs = base?.resumeMs ?: r.serverResumeMs)
        })
        refresh()
    }

    /** Hand the next waiting download to the engine when it is idle: one at a time, in the order asked for. */
    suspend fun pump(): Unit = pumpLock.withLock {
        val u = userId() ?: return@withLock
        if (engine.busy()) return@withLock
        // A start the server answered 503 is asked again on its Retry-After, once: the engine's first emission, a queued sibling or the page
        // reaching here meanwhile must not ask a second copy in the same turn (FR-7: `encoder_full` asks the oldest only). A delete, a retry
        // and the switch to the original cancel the wait first, so they act at once.
        if (pumpRetry?.isActive == true && !inRetry) return@withLock
        // FR-7: `encoder_full` is true for every copy, so the oldest keeps being asked for; `unavailable` is about one copy, so the next waiting
        // copy gets the turn, and when every one has had it the queue comes round to the oldest again.
        val queued = library.queued(u)
        val next = queued.firstOrNull { downloadId(u, it.itemId) !in unavailableRound }
            ?: run { unavailableRound.clear(); queued.firstOrNull() }
            ?: return@withLock
        try {
            start(u, next, fromSegment = null)
        } catch (e: DownloadNotAllowed) {
            // FR-2: the operator switched this viewer off; what is queued will not run, and the control is gone.
            library.remove(u, next.itemId)
            refresh()
        } catch (e: TvApiError.Http) {
            if (e.status == 503) {
                // The server is busy or Jellyfin cannot be asked: stays queued and shows "waiting"; try again shortly.
                noteWait(u, next, e)
                refresh()
                // One retry at a time: the engine's first emission and the page's own call can both reach here, and two chains would ask twice per turn.
                pumpRetry?.cancel()
                pumpRetry = scope.launch { delay(e.retryAfterSeconds?.times(1000L) ?: waitingRetryMs); inRetry = true; try { pump() } finally { inRetry = false } }
            } else {
                // 404 / 400: the title is gone or the quality is not offered any more; a failure the viewer can retry or delete.
                library.upsert(u, next.copy(state = RecordState.STARTED))
                startFailures[downloadId(u, next.itemId)] = e.status
                refresh()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline right now (or a reply this phone could not read): stays queued, the next engine change or pump tries again.
            lastStartError = e
        }
    }
}
