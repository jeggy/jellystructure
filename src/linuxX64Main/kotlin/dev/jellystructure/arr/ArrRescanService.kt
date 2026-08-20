package dev.jellystructure.arr

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phase 54 — after Jellystructure writes NFOs/artwork or edits tracks on disk, nudge the matching
 * *arr to rescan that one title so its own library view (notably MediaInfo: audio/subtitle languages)
 * stays in sync with what we changed.
 *
 * Strictly **best-effort and non-blocking**: it launches on the app scope, never throws, and never
 * blocks or fails the Jellystructure write. An unreachable *arr is logged, not fatal — only the
 * qBittorrent seeding guard is fail-closed.
 */
class ArrRescanService(
    private val configStore: ConfigStore,
    private val client: ArrClient,
    private val scope: CoroutineScope,
) {
    fun nudge(item: MediaItem) {
        val cfg = configStore.current
        when (item.kind) {
            MediaKind.MOVIE -> {
                val r = cfg.radarr ?: return
                if (!r.enabled || !r.rescanAfterWrite || r.url.isBlank()) return
                val tmdbId = item.tmdbId ?: return  // no TMDB id ⇒ can't resolve a Radarr movie; skip silently
                scope.launch {
                    runCatching {
                        val id = client.findMovieId(r.url, r.apiKey, tmdbId)
                        if (id == null) { Logger.warn("arr: no Radarr movie for tmdbId=$tmdbId ('${item.id}')"); return@launch }
                        if (client.rescanMovie(r.url, r.apiKey, id)) Logger.info("arr: RescanMovie $id for '${item.id}'")
                        else Logger.warn("arr: RescanMovie failed for '${item.id}'")
                    }.onFailure { Logger.warn("arr: Radarr rescan nudge failed for '${item.id}': ${it.message}") }
                }
            }
            MediaKind.TV_SHOW -> {
                val s = cfg.sonarr ?: return
                if (!s.enabled || !s.rescanAfterWrite || s.url.isBlank()) return
                scope.launch {
                    runCatching {
                        val id = client.findSeriesIdByPath(s.url, s.apiKey, item.path)
                        if (id == null) { Logger.warn("arr: no Sonarr series for path='${item.path}' ('${item.id}')"); return@launch }
                        if (client.rescanSeries(s.url, s.apiKey, id)) Logger.info("arr: RescanSeries $id for '${item.id}'")
                        else Logger.warn("arr: RescanSeries failed for '${item.id}'")
                    }.onFailure { Logger.warn("arr: Sonarr rescan nudge failed for '${item.id}': ${it.message}") }
                }
            }
            // Phase 168: a music video is never Radarr/Sonarr-managed (no tmdbId, filename-only).
            MediaKind.MUSIC_VIDEO -> {}
        }
    }

    private suspend fun findArrId(item: MediaItem): Int? {
        val cfg = configStore.current
        return when (item.kind) {
            MediaKind.MOVIE -> {
                val r = cfg.radarr ?: return null
                if (!r.enabled || r.url.isBlank()) return null
                val tmdbId = item.tmdbId ?: return null
                runCatching { client.findMovieId(r.url, r.apiKey, tmdbId) }.getOrNull()
            }
            MediaKind.TV_SHOW -> {
                val s = cfg.sonarr ?: return null
                if (!s.enabled || s.url.isBlank()) return null
                runCatching { client.findSeriesIdByPath(s.url, s.apiKey, item.path) }.getOrNull()
            }
            MediaKind.MUSIC_VIDEO -> null
        }
    }

    /** Phase 128 — read-only "is this item findable in a configured Sonarr/Radarr" check, for the
     *  diagnose endpoint's UI decision (show Re-acquire vs. manual-repair guidance) without triggering
     *  anything. Distinct from [nudge], which is fire-and-forget. */
    suspend fun isManaged(item: MediaItem): Boolean = findArrId(item) != null

    /** Phase 128 — managed-only, synchronous rescan trigger for the "Re-acquire" repair button (an
     *  operator has replaced the broken file and wants *arr to pick it up), reusing the same
     *  find/rescan calls as [nudge] but reporting back so the route can respond honestly instead of a
     *  fire-and-forget "queued". Returns (managed, ok, detail). */
    suspend fun reacquire(item: MediaItem): Triple<Boolean, Boolean, String> {
        val id = findArrId(item) ?: return Triple(false, false, "Not managed by a configured Sonarr/Radarr")
        val cfg = configStore.current
        val ok = runCatching {
            when (item.kind) {
                MediaKind.MOVIE -> cfg.radarr?.let { client.rescanMovie(it.url, it.apiKey, id) } ?: false
                MediaKind.TV_SHOW -> cfg.sonarr?.let { client.rescanSeries(it.url, it.apiKey, id) } ?: false
                MediaKind.MUSIC_VIDEO -> false
            }
        }.getOrDefault(false)
        return Triple(true, ok, if (ok) "Rescan triggered" else "Rescan request failed")
    }
}
