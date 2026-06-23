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
        }
    }
}
