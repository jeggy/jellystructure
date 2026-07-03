package dev.jellystructure.media

import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.arr.SonarrEnrichService
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.runTagged
import dev.jellystructure.server.routes.pushToJellyfin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
) {
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(2))

    /** Fire-and-forget: queues a targeted ingest for [jellyfinId]. Safe to call repeatedly — each call
     *  is an independent run (no dedup needed; a redundant re-scan of the same item is harmless). */
    fun enqueue(jellyfinId: String) {
        if (!configStore.current.ingest.realtime) return
        queueScope.launch {
            runTagged("realtime-ingest-$jellyfinId", "ingest", "Realtime ingest: $jellyfinId") {
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

        val existing = store.resolveByJellyfinId(jItem.id)
        val fresh = scanner.scanItem(jItem) ?: return false
        // Same additive tag-union as Scanner.rescanFromJellyfin — a re-scan must not drop tags the item
        // already had (TMDB-sourced + jellystructure-defined tags all survive).
        val merged = if (existing != null) fresh.copy(tags = (fresh.tags + existing.tags).distinct()) else fresh
        val enriched = artwork.stampHasStill(sonarrEnrich?.enrichOne(merged) ?: merged)
        store.addOrUpdate(enriched)
        broadcaster.broadcast(JobEvent.ItemScanned("realtime-ingest-${enriched.id}", enriched))
        mediaHistory.record(enriched.id, "realtime_ingest", "jellyfinId=${jItem.id}")
        pushToJellyfin(enriched, artwork, configStore, jellyfinClient, appScope, store, arrRescan)
        return true
    }
}
