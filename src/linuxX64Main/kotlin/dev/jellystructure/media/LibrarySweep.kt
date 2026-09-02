package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger

/**
 * Phase 181 (FR-181-1 / FR-181-1a) — what a set-difference sweep of Jellyfin's actual catalog found.
 * [missingIds] is everything Jellyfin has that jellystructure's own item set does not (an id-level diff,
 * not a count or a timestamp comparison — see the spec §2.4 for why a timestamp watermark is unsound on
 * this library). [staleTopLevelIds] is the reverse: Movie/Series ids jellystructure holds that Jellyfin
 * no longer reports at all, surfaced for review per Phase 95's non-destructive invariant — never deleted
 * here.
 */
data class LibrarySweepResult(
    val missingIds: List<String>,
    val staleTopLevelIds: List<String>,
    val scannedCount: Int,
)

/**
 * Pure id-diff (Phase 181 FR-181-1/FR-181-1a): given every Jellyfin Movie/Series/Episode item already
 * scoped to jellystructure's configured libraries, and the Jellyfin ids jellystructure itself already
 * holds, compute what's missing and what's gone stale. Separated from [sweepJellyfinLibrary]'s network/DB
 * I/O so the diff logic itself is unit-testable, matching [isDueForRecheck]/[computeFreshnessFilter]'s
 * own split in this package.
 *
 * A missing `Episode` resolves to its **parent series id** (`SeriesId`) — jellystructure has no
 * standalone episode ingest path; a new episode is always processed as a re-scan of its series, exactly
 * as [RealtimeIngestService.enqueue] already does for the webhook/plugin path. A missing `Movie` or
 * `Series` id is returned as-is. Both land in the same [LibrarySweepResult.missingIds] list because the
 * caller feeds every entry through the identical [RealtimeIngestService.enqueue] call regardless of which
 * kind of id it started life as.
 *
 * [LibrarySweepResult.staleTopLevelIds] (FR-181-1a) is Movie/Series only: an episode-level reverse diff
 * would fire on every ordinary removed/re-imported episode inside a show jellystructure otherwise still
 * holds correctly, which is noise, not a signal.
 */
fun computeLibraryDiff(
    jfItems: List<JellyfinItem>,
    ourTopLevelIds: Set<String>,
    ourEpisodeIds: Set<String>,
): LibrarySweepResult {
    val missing = LinkedHashSet<String>()
    val jfTopLevelIds = HashSet<String>()
    for (jf in jfItems) {
        when (jf.type) {
            "Movie", "Series" -> {
                jfTopLevelIds += jf.id
                if (jf.id !in ourTopLevelIds) missing += jf.id
            }
            "Episode" -> {
                if (jf.id !in ourEpisodeIds) {
                    val seriesId = jf.seriesId
                    if (seriesId != null) missing += seriesId
                    // else: an episode with no SeriesId is unusable to us either way — RealtimeIngestService
                    // hits the identical case and skips it (see its own log line).
                }
            }
        }
    }
    val stale = ourTopLevelIds.filterNot { it in jfTopLevelIds }
    return LibrarySweepResult(missing.toList(), stale, jfItems.size)
}

/**
 * Phase 181 — the backstop that replaces predicting which items need attention with actually comparing
 * jellystructure's held Jellyfin ids against Jellyfin's own catalog. Correctness must come from
 * converging on Jellyfin's actual state; event delivery (webhooks, the old change-feed listener) is only
 * ever a latency optimization on top of this, never the only thing standing between a new file and the
 * catalog (see the spec's design principle, §3).
 *
 * No-ops (returns `null`) when Jellyfin isn't configured — matches every other Jellyfin-dependent call in
 * this codebase rather than throwing.
 */
suspend fun sweepJellyfinLibrary(
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    store: MediaStore,
): LibrarySweepResult? {
    val cfg = configStore.current
    val baseUrl = cfg.apiKeys.jellyfinUrl
    val token = cfg.apiKeys.jellyfinToken
    if (baseUrl.isBlank() || token.isBlank()) return null

    val libraryPrefixes = cfg.libraries.filter { !it.skip }.map { it.jellyfinPath.ifBlank { it.localPath } }
    fun inScope(item: JellyfinItem): Boolean {
        if (libraryPrefixes.isEmpty()) return true
        val path = item.path ?: return true // no path to filter on — don't silently drop it
        return libraryPrefixes.any { it.isNotBlank() && path.startsWith(it) }
    }

    val jfItems = jellyfinClient.getAllLibraryItemIds(baseUrl, token).filter(::inScope)
    if (jfItems.isEmpty()) return LibrarySweepResult(emptyList(), emptyList(), 0)

    val ourTopLevelIds = HashSet<String>()
    val ourEpisodeIds = HashSet<String>()
    for (item in store.allItems()) {
        item.jellyfinId?.let { ourTopLevelIds += it }
        for (ep in item.episodes) ep.jellyfinId?.let { ourEpisodeIds += it }
    }

    return computeLibraryDiff(jfItems, ourTopLevelIds, ourEpisodeIds)
}
