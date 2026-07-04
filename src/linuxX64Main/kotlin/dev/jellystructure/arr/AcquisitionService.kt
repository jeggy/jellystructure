package dev.jellystructure.arr

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.tv.TvEventBus
import dev.jellystructure.shared.tv.AcquisitionEpisodeRec
import dev.jellystructure.shared.tv.AcquisitionFlags
import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.AcquisitionStatus
import dev.jellystructure.shared.tv.MediaKind
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.gmtime_r
import platform.posix.strftime
import platform.posix.time
import platform.posix.time_tVar
import platform.posix.timespec
import platform.posix.tm

/**
 * Phase 56 — the acquisition engine. Asks Radarr/Sonarr to *get* a title we don't have, then a
 * reconciler merges the *arr queue (source of truth) into one [AcquisitionRecord] per title and pushes
 * status changes live. Request + track + cancel only; never auto-grabs/upgrades, never deletes library
 * files. Series roll up their monitored episodes (a missing episode never fails the series).
 */
class AcquisitionService(
    private val configStore: ConfigStore,
    private val client: ArrClient,
    private val tmdb: TmdbClient,
    private val store: AcquisitionStore,
    private val mediaStore: MediaStore,
    private val eventBus: TvEventBus,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }
    private val lastEmit = mutableMapOf<String, Pair<Int, Long>>() // itemKey -> (progress, tsMs)

    // ---- read API ----
    fun snapshot(keys: List<String>): List<AcquisitionRecord> = store.getMany(keys)
    fun get(itemKey: String): AcquisitionRecord? = store.get(itemKey)

    /**
     * Feature: bridges a Seerr-originated request (Phase 137/171/139 — [dev.jellystructure.seerr.SeerrDiscoverService.request])
     * into this same reconciler, so the Request tab gets live-updating progress instead of a static
     * badge until the page is reloaded. That flow tells Seerr to add+search the title directly — it
     * never went through [request] above — so [poll] never learned it existed. Best-effort: if the
     * *arr internal id can't be resolved yet (should be immediate, since Seerr's own request call
     * synchronously adds the movie/series to Radarr/Sonarr before returning), this is a silent no-op —
     * the reconciler simply won't have progress for this title until a later registration succeeds.
     */
    suspend fun trackSeerrRequest(
        mediaKind: MediaKind,
        tmdbId: Int,
        title: String,
        requestedBy: String?,
        language: String? = null,
        languageStrictWaiting: Boolean = false,
    ) = mutex.withLock {
        val itemKey = "tmdb:$tmdbId"
        val now = nowMs()
        val rec = AcquisitionRecord(
            itemKey, mediaKind, AcquisitionStatus.REQUESTED, tmdbId, title,
            requestedBy = requestedBy, language = language, languageStrictWaiting = languageStrictWaiting,
        )
        when (mediaKind) {
            MediaKind.MOVIE -> {
                val r = configStore.current.radarr?.takeIf { it.enabled } ?: return@withLock
                val movieId = client.findMovieId(r.url, r.apiKey, tmdbId) ?: return@withLock
                store.save(rec, "radarr", movieId, now)
            }
            MediaKind.SERIES -> {
                val s = configStore.current.sonarr?.takeIf { it.enabled } ?: return@withLock
                val seriesId = client.findSeriesIdByTmdbId(s.url, s.apiKey, tmdbId) ?: return@withLock
                store.save(rec, "sonarr", seriesId, now)
            }
        }
    }

    // ---- request ----
    suspend fun request(mediaKind: MediaKind, tmdbId: Int, title: String, requestedBy: String?): AcquisitionRecord =
        mutex.withLock {
            val itemKey = "tmdb:$tmdbId"
            val now = nowMs()
            val existing = store.get(itemKey)
            // Idempotent: a live request returns as-is; only FAILED/NOT_REQUESTED fall through to (re)request.
            if (existing != null && !existing.status.terminal) return existing

            val libItem = mediaStore.allItems().firstOrNull { it.tmdbId == tmdbId }
            if (libItem != null) {
                val rec = AcquisitionRecord(itemKey, mediaKind, AcquisitionStatus.AVAILABLE, tmdbId, title, progress = 100, itemId = libItem.id, requestedBy = requestedBy)
                store.save(rec, "", null, now); return rec
            }

            val acq = configStore.current.acquisition
            if (acq == null || !acq.enabled) return fail(itemKey, mediaKind, tmdbId, title, "acquisition is disabled", requestedBy, now)

            return when (mediaKind) {
                MediaKind.MOVIE -> {
                    val r = configStore.current.radarr
                    if (r == null || !r.enabled || r.url.isBlank()) return fail(itemKey, mediaKind, tmdbId, title, "Radarr not configured", requestedBy, now)
                    val qp = client.resolveQualityProfileId(r.url, r.apiKey, acq.radarrQualityProfile)
                        ?: return fail(itemKey, mediaKind, tmdbId, title, "no Radarr quality profile", requestedBy, now)
                    val root = client.resolveRootFolder(r.url, r.apiKey, acq.radarrRootFolder)
                        ?: return fail(itemKey, mediaKind, tmdbId, title, "root folder ambiguous — set acquisition.radarr_root_folder", requestedBy, now)
                    val id = client.addMovie(r.url, r.apiKey, tmdbId, root, qp)
                        ?: return fail(itemKey, mediaKind, tmdbId, title, "Radarr add failed", requestedBy, now)
                    val rec = AcquisitionRecord(itemKey, MediaKind.MOVIE, AcquisitionStatus.REQUESTED, tmdbId, title, requestedBy = requestedBy)
                    store.save(rec, "radarr", id, now); emit(rec)
                    Logger.info("acquisition: requested movie tmdb=$tmdbId '$title' (radarr movieId=$id)")
                    rec
                }
                MediaKind.SERIES -> {
                    val s = configStore.current.sonarr
                    if (s == null || !s.enabled || s.url.isBlank()) return fail(itemKey, mediaKind, tmdbId, title, "Sonarr not configured", requestedBy, now)
                    val tvdb = tmdb.getTvTvdbId(tmdbId)
                        ?: return fail(itemKey, mediaKind, tmdbId, title, "no TheTVDB id for this title", requestedBy, now)
                    val qp = client.resolveQualityProfileId(s.url, s.apiKey, acq.sonarrQualityProfile)
                        ?: return fail(itemKey, mediaKind, tmdbId, title, "no Sonarr quality profile", requestedBy, now)
                    val root = client.resolveRootFolder(s.url, s.apiKey, acq.sonarrRootFolder)
                        ?: return fail(itemKey, mediaKind, tmdbId, title, "root folder ambiguous — set acquisition.sonarr_root_folder", requestedBy, now)
                    val id = client.addSeries(s.url, s.apiKey, tvdb, root, qp, acq.sonarrMonitor, acq.sonarrSeasonFolder)
                        ?: return fail(itemKey, mediaKind, tmdbId, title, "Sonarr add failed", requestedBy, now)
                    val rec = AcquisitionRecord(itemKey, MediaKind.SERIES, AcquisitionStatus.REQUESTED, tmdbId, title, requestedBy = requestedBy)
                    store.save(rec, "sonarr", id, now); emit(rec)
                    Logger.info("acquisition: requested series tmdb=$tmdbId tvdb=$tvdb '$title' (sonarr seriesId=$id)")
                    rec
                }
            }
        }

    private suspend fun fail(itemKey: String, mediaKind: MediaKind, tmdbId: Int, title: String, reason: String, requestedBy: String?, now: Long): AcquisitionRecord {
        val rec = AcquisitionRecord(itemKey, mediaKind, AcquisitionStatus.FAILED, tmdbId, title, reason = reason, retryable = true, requestedBy = requestedBy)
        store.save(rec, "", null, now); emit(rec)
        Logger.warn("acquisition: request failed tmdb=$tmdbId '$title' — $reason")
        return rec
    }

    // ---- cancel (admin only) ----
    /** Stop an in-flight request: remove/unmonitor the *arr entity (so it won't re-grab) + its queue
     *  items, and drop the record. Never deletes library files — already-imported episodes stay. */
    suspend fun cancel(itemKey: String): Boolean = mutex.withLock {
        val h = store.handle(itemKey) ?: return false
        val cfg = configStore.current
        when (h.arrKind) {
            "radarr" -> cfg.radarr?.let { r ->
                if (h.arrId != null) {
                    client.getQueue(r.url, r.apiKey).filter { it.refId == h.arrId }.forEach { client.deleteQueueItem(r.url, r.apiKey, it.id) }
                    client.deleteMovie(r.url, r.apiKey, h.arrId)
                }
            }
            "sonarr" -> cfg.sonarr?.let { s ->
                if (h.arrId != null) {
                    client.getQueue(s.url, s.apiKey).filter { it.refId == h.arrId }.forEach { client.deleteQueueItem(s.url, s.apiKey, it.id) }
                    client.deleteSeries(s.url, s.apiKey, h.arrId)
                }
            }
        }
        store.delete(itemKey)
        emit(AcquisitionRecord(itemKey, h.mediaKind, AcquisitionStatus.NOT_REQUESTED, h.tmdbId))
        Logger.info("acquisition: cancelled $itemKey")
        true
    }

    // ---- reconciler ----
    fun startReconciler() {
        scope.launch {
            while (true) {
                val interval = (configStore.current.acquisition?.pollSeconds ?: 10).coerceIn(3, 300)
                runCatching { poll() }.onFailure { Logger.warn("acquisition poll failed: ${it.message}") }
                delay(interval * 1000L)
            }
        }
    }

    private suspend fun poll() {
        val actives = store.active()
        if (actives.isEmpty()) return
        val cfg = configStore.current
        val radarrQueue = cfg.radarr?.takeIf { it.enabled }?.let { client.getQueue(it.url, it.apiKey) } ?: emptyList()
        val sonarrQueue = cfg.sonarr?.takeIf { it.enabled }?.let { client.getQueue(it.url, it.apiKey) } ?: emptyList()
        val libByTmdb = mediaStore.allItems().mapNotNull { item -> item.tmdbId?.let { it to item } }.toMap()
        val now = nowMs()
        for (a in actives) {
            val prior = store.get(a.itemKey) ?: continue
            val libId = a.tmdbId?.let { libByTmdb[it]?.id }
            val next = when (a.mediaKind) {
                MediaKind.MOVIE -> reconcileMovie(prior, a.arrId, radarrQueue, libId)
                MediaKind.SERIES -> {
                    val s = cfg.sonarr
                    val eps = if (s != null && a.arrId != null) client.getSeriesEpisodes(s.url, s.apiKey, a.arrId) else emptyList()
                    reconcileSeries(prior, sonarrQueue.filter { it.refId == a.arrId }, eps, libId)
                }
            }
            store.save(next, a.arrKind, a.arrId, now)
            if (shouldEmit(prior, next, now)) emit(next)
        }
    }

    private fun reconcileMovie(prior: AcquisitionRecord, movieId: Int?, queue: List<ArrQueueItem>, libId: String?): AcquisitionRecord {
        if (libId != null) return prior.copy(status = AcquisitionStatus.AVAILABLE, itemId = libId, progress = 100, flags = AcquisitionFlags())
        val qi = movieId?.let { id -> queue.firstOrNull { it.refId == id } }
        if (qi == null) {
            // gone from the queue but not yet in our library → it likely imported and awaits our scan.
            val s = if (prior.status == AcquisitionStatus.DOWNLOADING || prior.status == AcquisitionStatus.IMPORTING)
                AcquisitionStatus.IMPORTING else AcquisitionStatus.REQUESTED
            return prior.copy(status = s)
        }
        val (st, prog, flags) = mapQueue(qi)
        return if (st == AcquisitionStatus.FAILED)
            prior.copy(status = AcquisitionStatus.FAILED, reason = qi.errorMessage ?: "grab/import failed", retryable = true)
        else prior.copy(
            status = st,
            progress = if (st == AcquisitionStatus.DOWNLOADING) prog else if (st == AcquisitionStatus.IMPORTING) 100 else 0,
            flags = flags, eta = qi.timeLeft,
        )
    }

    private fun reconcileSeries(prior: AcquisitionRecord, queueForSeries: List<ArrQueueItem>, eps: List<ArrEpisode>, libId: String?): AcquisitionRecord {
        val monitoredAired = eps.filter { it.monitored && aired(it.airDateUtc) }
        val total = monitoredAired.size
        val done = monitoredAired.count { it.hasFile }
        val epRecs = queueForSeries.mapNotNull { qi ->
            val se = qi.season; val ep = qi.episode
            if (se != null && ep != null) AcquisitionEpisodeRec(se, ep, mapQueue(qi).first, mapQueue(qi).second) else null
        }
        val mapped = queueForSeries.map { mapQueue(it).first }
        val haveInLib = libId != null
        val status = when {
            haveInLib && total > 0 && done >= total -> AcquisitionStatus.AVAILABLE
            mapped.any { it == AcquisitionStatus.DOWNLOADING } -> AcquisitionStatus.DOWNLOADING
            mapped.any { it == AcquisitionStatus.IMPORTING } -> AcquisitionStatus.IMPORTING
            mapped.any { it == AcquisitionStatus.QUEUED } -> AcquisitionStatus.QUEUED
            done >= 1 && haveInLib -> AcquisitionStatus.AVAILABLE        // ≥1 available, missing eps don't block
            done >= 1 -> AcquisitionStatus.IMPORTING                      // downloaded but our scan hasn't surfaced it
            queueForSeries.isEmpty() -> AcquisitionStatus.REQUESTED       // still searching
            else -> prior.status
        }
        val progress = if (total > 0) (done * 100 / total).coerceIn(0, 100) else if (status == AcquisitionStatus.DOWNLOADING) 50 else 0
        return prior.copy(
            status = status,
            progress = progress,
            episodesTotal = total,
            episodesDone = done,
            firstAvailable = haveInLib && done >= 1,
            itemId = if (status == AcquisitionStatus.AVAILABLE) libId else null,
            flags = AcquisitionFlags(stalled = queueForSeries.any { it.trackedStatus.equals("warning", true) }),
            episodes = epRecs,
        )
    }

    /** Map one *arr queue item to (status, progress%, flags). */
    private fun mapQueue(qi: ArrQueueItem): Triple<AcquisitionStatus, Int, AcquisitionFlags> {
        val progress = if (qi.size > 0) (((qi.size - qi.sizeLeft) / qi.size) * 100).toInt().coerceIn(0, 100) else 0
        val ts = qi.trackedState.lowercase()
        val st = qi.status.lowercase()
        val error = qi.trackedStatus.equals("error", true) || ts.contains("failed")
        return when {
            error -> Triple(AcquisitionStatus.FAILED, 0, AcquisitionFlags())
            ts.contains("import") -> Triple(AcquisitionStatus.IMPORTING, 100, AcquisitionFlags())
            st == "downloading" || ts == "downloading" ->
                Triple(AcquisitionStatus.DOWNLOADING, progress, AcquisitionFlags(stalled = qi.trackedStatus.equals("warning", true) || st == "warning"))
            st == "queued" || st == "delay" || st == "paused" || st == "downloadclientunavailable" ->
                Triple(AcquisitionStatus.QUEUED, 0, AcquisitionFlags())
            else -> Triple(AcquisitionStatus.QUEUED, progress, AcquisitionFlags())
        }
    }

    private fun shouldEmit(prior: AcquisitionRecord, next: AcquisitionRecord, now: Long): Boolean {
        val structural = next.status != prior.status || next.flags != prior.flags ||
            next.episodesDone != prior.episodesDone || next.episodesTotal != prior.episodesTotal ||
            next.firstAvailable != prior.firstAvailable || next.itemId != prior.itemId
        if (structural) { lastEmit[next.itemKey] = next.progress to now; return true }
        if (next.progress != prior.progress) {
            val (lp, lt) = lastEmit[next.itemKey] ?: (next.progress to 0L)
            if (kotlin.math.abs(next.progress - lp) >= 5 || now - lt >= 3000) {
                lastEmit[next.itemKey] = next.progress to now; return true
            }
        }
        return false
    }

    private fun emit(rec: AcquisitionRecord) = eventBus.notifyAcquisitionChanged(json.encodeToString(AcquisitionRecord.serializer(), rec))
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

/** UTC "YYYY-MM-DDTHH:MM:SS" for lexical comparison against Sonarr airDateUtc. */
@OptIn(ExperimentalForeignApi::class)
private fun nowIso(): String = memScoped {
    val t = alloc<time_tVar>(); time(t.ptr)
    val tmv = alloc<tm>(); gmtime_r(t.ptr, tmv.ptr)
    val buf = allocArray<ByteVar>(32)
    strftime(buf, 32.convert(), "%Y-%m-%dT%H:%M:%S", tmv.ptr)
    buf.toKString()
}

private fun aired(airDateUtc: String?): Boolean {
    if (airDateUtc.isNullOrBlank()) return false
    return airDateUtc.removeSuffix("Z").take(19) <= nowIso()
}
