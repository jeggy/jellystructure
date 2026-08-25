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
) {
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(2))

    // Phase 165 amendment (2026-08-14, FR-165-7) — set by handleJellyfinWebhook the instant a request
    // passes the secret check, before any item-type filtering. This is the ONLY signal that the PRIMARY
    // webhook path (the Jellyfin plugin) itself ever reached jellystructure — the pre-existing
    // IngestStatus.lastEventAt only ever reflected the fallback JellyfinLibraryListener change-feed, so a
    // dead primary path could hide behind the fallback's own occasional traffic. See the live incident
    // this amendment documents in phase-165's spec.
    @Volatile var lastWebhookReceivedAt: Long? = null

    // Phase 145 — coalesce bursts: a season import fires one event per episode, each of which resolves
    // to (and re-scans) the same parent series. Skip a target whose full ingest ran within this window
    // so a 10-episode import runs the pipeline once for the series, not ten times.
    private val debounceMutex = kotlinx.coroutines.sync.Mutex()
    private val lastIngestMs = HashMap<String, Long>()
    private val debounceWindowMs = 8_000L

    /** Fire-and-forget: queues a targeted ingest for [jellyfinId]. Safe to call repeatedly — each call
     *  is an independent run (no dedup needed; a redundant re-scan of the same item is harmless). */
    fun enqueue(jellyfinId: String) {
        if (!configStore.current.ingest.realtime) return
        queueScope.launch {
            runTagged("realtime-ingest-$jellyfinId", "ingest", "library", null, "Realtime ingest: $jellyfinId") {
                if (!ingestOnce(jellyfinId)) {
                    delay(60_000L) // FR C.4 — one retry after 60s for TMDB hiccups etc.
                    if (!ingestOnce(jellyfinId)) {
                        Logger.warn("Realtime ingest failed twice for jellyfinId=$jellyfinId — leaving for the next scheduled scan", "ingest")
                    }
                }
            }
        }
    }

    private suspend fun ingestOnce(jellyfinId: String): Boolean =
        runCatching { ingestByJellyfinId(jellyfinId) }.getOrElse {
            Logger.warn("Realtime ingest error for jellyfinId=$jellyfinId: ${it.message}", "ingest")
            false
        }

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
        )
        runCatching {
            runPipeline(
                target = RunTarget.SingleItem(enriched),
                pipeline = effectivePipeline(configStore.current),
                jobId = "realtime-ingest-${enriched.id}",
                scanTracker = ScanTracker(db, persistToDb = false),
                deps = deps,
                signalCompletion = false,
            )
        }.onFailure { Logger.warn("Realtime ingest: downstream pipeline failed for ${enriched.id}: ${it.message}", "ingest") }
        return true
    }
}
