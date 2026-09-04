package dev.jellystructure.seerr

import dev.jellystructure.arr.AcquisitionService
import dev.jellystructure.arr.AcquisitionStore
import dev.jellystructure.arr.ArrClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.nowEpochSec
import dev.jellystructure.shared.tv.AcquisitionStatus
import dev.jellystructure.shared.tv.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 186 — a request must be able to end. Two Discover-tab titles (Digger, Lokkeduerne) sat on the
 * "In progress" rail indefinitely because nothing anywhere reconciles a `request_intent` row against
 * the systems that actually own its truth (Seerr, Radarr/Sonarr) — the only exit this table had was
 * "the title became AVAILABLE" (see [SeerrDiscoverService.getMyRequests]'s prior single filter). This
 * runs its own slow sweep (own timer, not folded into [AcquisitionService.startReconciler]'s poll loop
 * or a pipeline run — FR-186-1 open question 1's resolution: this reconciles against Seerr and *arr ground
 * truth on a scale of hours, not the *arr download queue poll() already watches on a scale of seconds,
 * and staying out of the pipeline means it never has to reason about Phase 182's scan Gate classes),
 * classifying every row as **live** (never touched, however old — Digger's shape: unreleased and
 * monitored is a valid resting state), **fulfilled** (silently retired — the title showed up), **dead**
 * (retired after [AcquisitionConfig.deadSweepThreshold] consecutive confirmations, audited to Activity —
 * never on one bad observation, so a Seerr hiccup can't retire a real request), or **indeterminate**
 * (Seerr unreachable/unconfigured — never counts toward a dead streak either way).
 */
class RequestLifecycleService(
    private val configStore: ConfigStore,
    private val requestIntentStore: RequestIntentStore,
    private val acquisitionStore: AcquisitionStore,
    private val acquisitionService: AcquisitionService,
    private val seerrClient: SeerrClient,
    private val arrClient: ArrClient,
    private val mediaStore: MediaStore,
    private val scope: CoroutineScope,
) {
    private sealed class Verdict {
        data object Live : Verdict()
        data object Fulfilled : Verdict()
        data class Dead(val reason: String) : Verdict()
        data class Indeterminate(val reason: String) : Verdict()
    }

    fun startSweeper() {
        scope.launch {
            while (true) {
                val hours = (configStore.current.acquisition?.lifecycleSweepHours ?: 1).coerceIn(1, 24)
                runCatching { sweep() }.onFailure { Logger.warn("request-lifecycle sweep failed: ${it.message}", "acquisition") }
                delay(hours * 3_600_000L)
            }
        }
    }

    suspend fun sweep() {
        val acq = configStore.current.acquisition
        val threshold = (acq?.deadSweepThreshold ?: 3).coerceAtLeast(1)
        val retentionMs = (acq?.requestRetentionDays ?: 14).coerceAtLeast(1) * 86_400_000L
        val libByTmdb = mediaStore.allItems().mapNotNull { item -> item.tmdbId?.let { it to item } }.toMap()
        val nowSec = nowEpochSec()

        for (row in requestIntentStore.all()) {
            when (val v = classify(row, libByTmdb, retentionMs)) {
                is Verdict.Fulfilled -> {
                    // Fulfilled retirement is expected and stays quiet (FR-186-4) — the title showed up,
                    // exactly like today's plain AVAILABLE filter already did before this row even had a
                    // chance to go stale.
                    requestIntentStore.deleteOne(row.mediaKind, row.tmdbId)
                    acquisitionStore.get("tmdb:${row.tmdbId}")?.let { acquisitionStore.delete(it.itemKey) }
                }
                is Verdict.Dead -> {
                    requestIntentStore.bumpDeadStreak(row.mediaKind, row.tmdbId, nowSec)
                    val streak = row.deadStreak + 1
                    if (streak >= threshold) {
                        val detail = "Request retired — ${v.reason} ($streak consecutive sweeps)"
                        removeRequest(row.mediaKind, row.tmdbId, deleteFiles = false, addExclusion = false)
                        Logger.info(detail, "acquisition")
                    }
                }
                is Verdict.Live, is Verdict.Indeterminate -> requestIntentStore.resetDeadStreak(row.mediaKind, row.tmdbId, nowSec)
            }
        }
        sweepOrphanAcquisitions(retentionMs)
    }

    /**
     * FR-186-2's classification. Order matters: a library match always wins (checked first, cheapest),
     * then Seerr's own view of the title, then (only once Seerr hasn't already given a verdict) the
     * *arr entity's own existence — rule 3 is deliberately narrower than rules 1/2, since an *arr entity
     * vanishing while Seerr still shows an active request is a rarer, riskier signal to act on alone.
     */
    private suspend fun classify(row: RequestIntentRow, libByTmdb: Map<Int, dev.jellystructure.model.MediaItem>, retentionMs: Long): Verdict {
        if (libByTmdb.containsKey(row.tmdbId)) return Verdict.Fulfilled

        val seerr = configStore.current.seerr?.takeIf { it.enabled && it.url.isNotBlank() }
            ?: return Verdict.Indeterminate("Seerr not configured")

        // A null `details` means the CALL failed (network/timeout/Seerr down) — Indeterminate, never
        // Dead. A successful call whose own `mediaInfo` is null is Seerr positively saying "I have no
        // record of this title" — the Lokkeduerne shape (rule 1's "no request exists" + rule 2's
        // "media absent") — a completely different, and solid, signal. These must not be conflated.
        val mediaInfo: SeerrMediaInfo = if (row.mediaKind == MediaKind.SERIES) {
            val details = seerrClient.tvDetails(seerr.url, seerr.apiKey, row.tmdbId) ?: return Verdict.Indeterminate("Seerr unreachable")
            details.mediaInfo ?: return Verdict.Dead("no Seerr record for this title")
        } else {
            val details = seerrClient.movieDetails(seerr.url, seerr.apiKey, row.tmdbId) ?: return Verdict.Indeterminate("Seerr unreachable")
            details.mediaInfo ?: return Verdict.Dead("no Seerr record for this title")
        }

        when (mediaInfo.requests.maxByOrNull { it.id }?.status) {
            1, 2 -> return Verdict.Live // PENDING_APPROVAL / APPROVED — Digger's shape: correct to keep, any age
            3 -> return Verdict.Dead("declined in Seerr")
            // 5 = COMPLETED in Seerr but we already know (checked first, above) it's not in OUR
            // library yet — give our own scan/pipeline a chance to catch up rather than racing it.
            else -> {}
        }
        if (mediaInfo.status == 7) return Verdict.Dead("Seerr media row deleted")

        // Rule 3 — an *arr entity an admin removed by hand, outside jellystructure and outside Seerr's
        // own knowledge. Only evaluated once Seerr itself gave no dead/live verdict above.
        val handle = acquisitionStore.handle("tmdb:${row.tmdbId}")
        if (handle?.arrId != null) {
            val cfg = configStore.current
            val exists = when (row.mediaKind) {
                MediaKind.SERIES -> cfg.sonarr?.let { arrClient.seriesExists(it.url, it.apiKey, handle.arrId) } ?: true
                else -> cfg.radarr?.let { arrClient.movieExists(it.url, it.apiKey, handle.arrId) } ?: true
            }
            if (!exists) return Verdict.Dead("*arr entity no longer exists")
        }

        // Rule 4 — a FAILED acquisition stale past the retention window. Written without this
        // codebase's "non-retryable" clause: every FAILED record here is created with retryable=true
        // (AcquisitionService.fail()/reconcileMovie's FAILED branch both hardcode it) — nothing ever
        // sets it false, so the literal FR-186-2 wording would make this branch permanently dead code.
        // Age plus a viewer who hasn't retried is the real signal; open question 3 dropped the
        // per-viewer "haven't looked at it" half (no such signal exists), leaving age alone.
        val acqRec = acquisitionStore.get("tmdb:${row.tmdbId}")
        if (acqRec != null && acqRec.status == AcquisitionStatus.FAILED) {
            val updatedAt = acquisitionStore.updatedAtMs("tmdb:${row.tmdbId}")
            if (updatedAt != null && nowMs() - updatedAt > retentionMs) return Verdict.Dead("stale failed request")
        }

        return Verdict.Live
    }

    /**
     * FR-186-5 — the `acquisition` table's own orphans, independent of `request_intent` (an admin-only
     * acquisition never has a request_intent row at all — Phase 56 predates Phase 139's intent table).
     * Only ever prunes rows already at a terminal, non-useful status; never touches anything active.
     */
    private suspend fun sweepOrphanAcquisitions(retentionMs: Long) {
        val nowMs = nowMs()
        for (rec in acquisitionStore.all()) {
            if (rec.status != AcquisitionStatus.FAILED && rec.status != AcquisitionStatus.NOT_REQUESTED) continue
            val updatedAt = acquisitionStore.updatedAtMs(rec.itemKey) ?: continue
            if (nowMs - updatedAt <= retentionMs) continue
            val handle = acquisitionStore.handle(rec.itemKey) ?: continue
            val cfg = configStore.current
            val arrGone = handle.arrId == null || when (rec.mediaKind) {
                MediaKind.SERIES -> cfg.sonarr?.let { !arrClient.seriesExists(it.url, it.apiKey, handle.arrId) } ?: false
                else -> cfg.radarr?.let { !arrClient.movieExists(it.url, it.apiKey, handle.arrId) } ?: false
            }
            val tmdbId = rec.tmdbId
            val seerrGone = tmdbId == null || configStore.current.seerr?.takeIf { it.enabled }?.let { seerr ->
                val mi = if (rec.mediaKind == MediaKind.SERIES) seerrClient.tvDetails(seerr.url, seerr.apiKey, tmdbId)?.mediaInfo
                          else seerrClient.movieDetails(seerr.url, seerr.apiKey, tmdbId)?.mediaInfo
                mi == null || mi.status == 7
            } ?: true
            if (arrGone && seerrGone) {
                acquisitionStore.delete(rec.itemKey)
                Logger.info("acquisition: pruned orphaned ${rec.status} record for '${rec.title}' (tmdb=${rec.tmdbId}), stale past retention", "acquisition")
            }
        }
    }

    /**
     * FR-186-6 — the full cascade the 2026-09-04 manual cleanup needed three separate systems for.
     * `deleteFiles`/`addExclusion` default false: this is "stop wanting it", never "delete my library".
     * Every step is defensive — a title may have no acquisition row (Lokkeduerne: request_intent only),
     * no Seerr record (already cleaned up by hand), or no *arr entity (never got that far) — and the
     * cascade still completes the steps that do apply rather than bailing on the first missing piece.
     */
    suspend fun removeRequest(mediaKind: MediaKind, tmdbId: Int, deleteFiles: Boolean = false, addExclusion: Boolean = false) {
        val seerr = configStore.current.seerr?.takeIf { it.enabled && it.url.isNotBlank() }
        if (seerr != null) {
            val mediaInfo = if (mediaKind == MediaKind.SERIES) seerrClient.tvDetails(seerr.url, seerr.apiKey, tmdbId)?.mediaInfo
                             else seerrClient.movieDetails(seerr.url, seerr.apiKey, tmdbId)?.mediaInfo
            if (mediaInfo != null) {
                for (req in mediaInfo.requests) {
                    if (req.status != 3) seerrClient.declineRequest(seerr.url, seerr.apiKey, req.id)
                    seerrClient.deleteRequest(seerr.url, seerr.apiKey, req.id)
                }
                if (mediaInfo.id != 0) seerrClient.deleteMedia(seerr.url, seerr.apiKey, mediaInfo.id)
            }
        }
        val handle = acquisitionStore.handle("tmdb:$tmdbId")
        acquisitionService.teardownArr(mediaKind, handle?.arrId, deleteFiles, addExclusion)
        handle?.let { acquisitionStore.delete(it.itemKey) }
        requestIntentStore.deleteOne(mediaKind, tmdbId)
    }
}

/** Day-granularity is all the retention-window math above needs — no cinterop clock required. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowMs(): Long = platform.posix.time(null) * 1000L
