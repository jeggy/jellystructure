package dev.jellystructure.media

import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.arr.SonarrEnrichService
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.runTagged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.withLock

/**
 * Phase 114 — targeted single-item ingest: given a Jellyfin item id that jellystructure may or may not
 * already hold, fetch it, run the standard per-item pipeline (ffprobe/TMDB), write it through, and
 * push a targeted Jellyfin refresh — all within seconds instead of waiting for the next scheduled scan.
 * Fed by [dev.jellystructure.server.routes.webhookRoutes] (*arr imports) and
 * [JellyfinLibraryListener] (Jellyfin's own LibraryChanged events).
 *
 * Runs on a small dedicated 2-worker dispatcher (spec FR C.2) so a burst of imports doesn't compete
 * with the scheduled scan's own dispatcher, and each run is `runTagged` so it shows up in Activity
 * exactly like a scan.
 */
class RealtimeIngestService(
    private val scanner: Scanner,
    private val store: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
    private val artwork: ArtworkDownloader,
    private val appScope: CoroutineScope,
    private val broadcaster: WsBroadcaster,
    private val mediaHistory: MediaHistory,
    private val arrRescan: ArrRescanService? = null,
    private val sonarrEnrich: SonarrEnrichService? = null,
    private val imdbClient: dev.jellystructure.imdb.ImdbClient? = null,   // Phase 145
    // Phase 164 — detect_segments enqueues here instead of calling PipelineStepOps.detectSegments
    // inline; this class no longer needs its own FingerprintService/MediaSegmentStore references
    // (MediaJobQueue's segments-lane job runner has its own).
    private val mediaJobQueue: MediaJobQueue? = null,
    // Phase 175 — needed to construct a fresh, non-DB-persisting ScanTracker per ingest (see
    // runConfiguredSteps' doc comment) so this service can run every item through the same
    // runPipeline() step loop the scheduled/manual pipeline uses, instead of its own hand-written copy.
    private val db: dev.jellystructure.db.JellystructureDb,
    private val mediaSegmentStore: MediaSegmentStore,
    // Phase 181 (FR-181-5) — the persistent "needs work" set. Written when [enqueue]'s retry is
    // exhausted, cleared the instant that jellyfinId ingests successfully.
    private val dirtyItemStore: DirtyItemStore,
) {
    // Phase 182 (FR-182-6, open question #5) — tagged BACKGROUND: a webhook-triggered ingest is an
    // internal reaction to an already-acknowledged request, not a live inbound one a viewer is
    // waiting on, and it runs the same TMDB/Jellyfin outbound work a scan does. Baked into the scope's
    // own context (not passed per-launch) so the single call site below inherits it automatically.
    private val queueScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(2) + dev.jellystructure.ops.GateClass.BACKGROUND
    )

    // Phase 165 amendment (2026-08-14, FR-165-7) — set by handleJellyfinWebhook the instant a request
    // passes the secret check, before any item-type filtering. This is the ONLY signal that the PRIMARY
    // webhook path (the Jellyfin plugin) itself ever reached jellystructure — the pre-existing
    // IngestStatus.lastEventAt only ever reflected the fallback JellyfinLibraryListener change-feed, so a
    // dead primary path could hide behind the fallback's own occasional traffic. See the live incident
    // this amendment documents in phase-165's spec.
    @Volatile var lastWebhookReceivedAt: Long? = null

    // Phase 181 (FR-181-4.2) — distinct from lastWebhookReceivedAt (which only ever proves a webhook
    // ARRIVED, not that ingest actually did anything with it). Set on every path that reaches this
    // service and completes without error: the primary webhook, the library sweep (FR-181-1), and the
    // dirty-set retry (FR-181-5) all funnel through [enqueue], so this one field is the health signal for
    // "realtime ingest, however it got triggered, is actually working" — surfaced on the ingest-status
    // route so an abnormal silence is visible without a human noticing a missing episode.
    @Volatile var lastSuccessfulIngestAt: Long? = null

    // Phase 145 — coalesce bursts: a season import fires one event per episode, each of which resolves
    // to (and re-scans) the same parent series. Skip a target whose full ingest ran within this window
    // so a 10-episode import runs the pipeline once for the series, not ten times.
    private val debounceMutex = kotlinx.coroutines.sync.Mutex()
    private val lastIngestMs = HashMap<String, Long>()
    private val debounceWindowMs = 8_000L

    /**
     * Fire-and-forget: queues a targeted ingest for [jellyfinId]. Safe to call repeatedly — each call is
     * an independent run (no dedup needed beyond the debounce window below).
     *
     * Phase 181 — deliberately **not** gated on `[ingest] realtime` here. That setting only ever meant
     * "react to events within seconds instead of waiting for the next scan" for the two genuinely
     * event-driven callers ([dev.jellystructure.server.routes.handleJellyfinWebhook], which checks it
     * itself before calling this); the library sweep (FR-181-1) and the dirty-set retry (FR-181-5) are
     * the *correctness* backstop, not an optimization, and must keep working even when an admin has
     * turned the "instant" path off.
     */
    fun enqueue(jellyfinId: String) {
        queueScope.launch {
            runTagged("realtime-ingest-$jellyfinId", "ingest", "library", null, "Realtime ingest: $jellyfinId") {
                when (ingestOnce(jellyfinId)) {
                    IngestOutcome.OK -> {
                        dirtyItemStore.clear(jellyfinId)
                        lastSuccessfulIngestAt = dev.jellystructure.nowEpochSec()
                    }
                    // Phase 195 (FR-195-3) — the server was busy; nothing about this id is wrong. Burning
                    // an attempt on it, and then recording it as a failure, is what made the dirty set
                    // self-sustaining: a saturated gate marked ~117 healthy items dirty, the next cycle
                    // replayed all 117 at once, saturated the gate again, and re-marked them. Defer
                    // instead — the item keeps its attempts and is retried on the normal cadence.
                    IngestOutcome.BUSY -> Logger.info(
                        "Realtime ingest deferred for jellyfinId=$jellyfinId — server busy, not counted as a failure",
                        "ingest",
                    )
                    IngestOutcome.FAILED -> {
                        delay(60_000L) // FR C.4 — one retry after 60s for TMDB hiccups etc.
                        when (ingestOnce(jellyfinId)) {
                            IngestOutcome.OK -> {
                                dirtyItemStore.clear(jellyfinId)
                                lastSuccessfulIngestAt = dev.jellystructure.nowEpochSec()
                            }
                            IngestOutcome.BUSY -> Logger.info(
                                "Realtime ingest deferred for jellyfinId=$jellyfinId — server busy, not counted as a failure",
                                "ingest",
                            )
                            IngestOutcome.FAILED -> {
                                // FR-181-5 — remembered instead of forgotten: the next Library-scoped pipeline run
                                // (scheduled scan, manual click, or SCAN_ON_START) retries this id automatically.
                                dirtyItemStore.markDirty(jellyfinId, "ingest failed twice", store.nowMs())
                                Logger.warn("Realtime ingest failed twice for jellyfinId=$jellyfinId — recorded for retry on the next scan cycle", "ingest")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Phase 195 (FR-195-3) — three outcomes, not two. [BUSY] means a gate refused us within its
     * deadline: an availability fact about the server, saying nothing about whether this id can be
     * ingested. Collapsing it into [FAILED] (which is what `Boolean` forced) is the same mistake
     * Phase 194 fixes in `isTokenValid` — an unavailable dependency and a bad input are not the same
     * fact, and a retry policy that cannot tell them apart cannot converge.
     */
    private enum class IngestOutcome { OK, BUSY, FAILED }

    private suspend fun ingestOnce(jellyfinId: String): IngestOutcome =
        runCatching { ingestByJellyfinId(jellyfinId) }
            .fold(
                onSuccess = { if (it) IngestOutcome.OK else IngestOutcome.FAILED },
                onFailure = { e ->
                    val busy = e is dev.jellystructure.ops.ProcessGate.GateTimeoutException ||
                        e is dev.jellystructure.OutboundHttp.GateTimeoutException
                    if (busy) {
                        IngestOutcome.BUSY
                    } else {
                        Logger.warn("Realtime ingest error for jellyfinId=$jellyfinId: ${e.message}", "ingest")
                        IngestOutcome.FAILED
                    }
                },
            )

    private suspend fun ingestByJellyfinId(jellyfinId: String): Boolean {
        val cfg = configStore.current
        val baseUrl = cfg.apiKeys.jellyfinUrl
        val token = cfg.apiKeys.jellyfinToken
        if (baseUrl.isBlank() || token.isBlank()) return false

        var jItem = jellyfinClient.getItem(baseUrl, token, jellyfinId) ?: run {
            Logger.warn("Realtime ingest: Jellyfin has no item $jellyfinId", "ingest")
            return false
        }

        // FR C.1 — a new episode has no standalone ingest path (Scanner.scanItem only knows Movie/
        // Series); it lands as a re-scan of its parent series, which walks the on-disk episode files
        // and re-derives the whole episode list (including the new one) from there.
        if (jItem.type == "Episode") {
            val seriesId = jItem.seriesId
            if (seriesId.isNullOrBlank()) {
                Logger.warn("Realtime ingest: episode $jellyfinId has no SeriesId — skipping", "ingest")
                return false
            }
            if (store.resolveByJellyfinId(seriesId) == null) {
                // The series itself isn't in the store yet — it'll arrive as its own Series-type event
                // (LibraryChanged fires for the series too on a first import); not a failure to retry.
                Logger.info("Realtime ingest: new episode of an unseen series (seriesId=$seriesId) — leaving for a scheduled scan", "ingest")
                return true
            }
            jItem = jellyfinClient.getItem(baseUrl, token, seriesId) ?: return false
        }

        if (jItem.type != "Movie" && jItem.type != "Series") return true // nothing to ingest — not a failure

        // Phase 145 — coalesce bursts: skip a target whose full ingest ran within the debounce window.
        val now = store.nowMs()
        val coalesced = debounceMutex.withLock {
            val last = lastIngestMs[jItem.id]
            if (last != null && now - last < debounceWindowMs) true
            else { lastIngestMs[jItem.id] = now; false }
        }
        if (coalesced) {
            Logger.info("Realtime ingest: coalesced a burst event for ${jItem.id} (within ${debounceWindowMs}ms)", "ingest")
            return true
        }

        val existing = store.resolveByJellyfinId(jItem.id)
        val fresh = scanner.scanItem(jItem) ?: return false
        // Same additive tag-union as Scanner.rescanFromJellyfin — a re-scan must not drop tags the item
        // already had (TMDB-sourced + jellystructure-defined tags all survive).
        val merged = if (existing != null) fresh.copy(tags = (fresh.tags + existing.tags).distinct()) else fresh
        val enriched = artwork.stampHasStill(sonarrEnrich?.enrichOne(merged) ?: merged)
        store.addOrUpdate(enriched)
        broadcaster.broadcast(JobEvent.ItemScanned("realtime-ingest-${enriched.id}", enriched))
        mediaHistory.record(enriched.id, "realtime_ingest", "jellyfinId=${jItem.id}")
        // Phase 145 (unified onto the shared engine, Phase 175) — run the operator's *configured*
        // [[scan.pipeline]] downstream steps for this one item (scan_files + pull_tmdb already covered
        // by scanItem above — runPipeline skips pull_tmdb for a SingleItem target for exactly that
        // reason) through the SAME step loop the scheduled/manual pipeline uses, instead of this
        // service's own hand-written, independently-drifting copy of "which steps exist and what they
        // do". A fresh, non-DB-persisting ScanTracker (see its own doc comment) drives the run's
        // StepStarted/StepProgress/StepFinished events without touching the global scan_state row a
        // concurrent bulk scan might be using.
        val queue = mediaJobQueue
        if (queue == null) {
            Logger.warn("Realtime ingest: no MediaJobQueue configured — downstream steps skipped for ${enriched.id}", "ingest")
            return true
        }
        val deps = PipelineDeps(
            store = store, scanner = scanner, broadcaster = broadcaster, configStore = configStore,
            jellyfinClient = jellyfinClient, scanDispatcher = kotlinx.coroutines.Dispatchers.Default,
            artworkDownloader = artwork, arrRescan = arrRescan, sonarrEnrich = sonarrEnrich,
            imdbClient = imdbClient, mediaSegmentStore = mediaSegmentStore, mediaJobQueue = queue,
            // This target is always RunTarget.SingleItem, which never reaches the sweep/dirty-set branch
            // (see runPipeline's RunTarget.Library case) — `this` is passed for realtimeIngest simply
            // because PipelineDeps requires a value, not because a SingleItem run recurses through it.
            realtimeIngest = this, mediaHistory = mediaHistory, dirtyItemStore = dirtyItemStore,
        )
        runCatching {
            runPipeline(
                target = RunTarget.SingleItem(enriched),
                pipeline = effectivePipeline(configStore.current),
                jobId = "realtime-ingest-${enriched.id}",
                scanTracker = ScanTracker(db, persistToDb = false),
                deps = deps,
                signalCompletion = false,
                // Phase 178 §FR-178-2 — realtime ingest is the "event-driven" trigger the spec names
                // alongside "scheduled"; a webhook/library-changed event is never an operator sitting at
                // the admin UI clicking a button, so it defers the same way.
                deferEligible = configStore.current.scan.deferWhilePlaying,
            )
        }.onFailure { Logger.warn("Realtime ingest: downstream pipeline failed for ${enriched.id}: ${it.message}", "ingest") }
        return true
    }
}
