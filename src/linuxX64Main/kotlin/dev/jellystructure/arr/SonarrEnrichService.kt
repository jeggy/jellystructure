package dev.jellystructure.arr

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import platform.posix.time

/**
 * R149 — Best-effort Sonarr enrichment: reads each series' status (continuing/ended) and the next
 * scheduled unaired episode from Sonarr, then stores the result on the [MediaItem] so the TV feed
 * can surface the "Airing soon" badge and the series-detail "Next episode" banner without a
 * request-time Sonarr call.
 *
 * FD_SETSIZE ceiling: outbound Sonarr episode-list fetches are bounded by [episodeGate] (≤8
 * concurrent, same ceiling rule as Phase 78).
 */
class SonarrEnrichService(
    private val mediaStore: MediaStore,
    private val arrClient: ArrClient,
    private val configStore: ConfigStore,
) {
    private val episodeGate = Semaphore(8)

    /** Enrich every TV show in the store in one batch. Called after a full library scan. */
    suspend fun enrichAll() {
        val sonarr = configStore.current.sonarr?.takeIf { it.enabled && it.url.isNotBlank() } ?: return
        val allSonarrSeries = runCatching {
            arrClient.getAllSeriesInfo(sonarr.url, sonarr.apiKey)
        }.getOrElse { e ->
            Logger.warn("sonarr-enrich: failed to fetch series list: ${e.message}")
            return
        }
        if (allSonarrSeries.isEmpty()) return

        val byPath: Map<String, ArrSeriesInfo> = allSonarrSeries.associateBy { it.path.trimEnd('/') }
        // Fallback: match by the last path component (folder name, lowercased) when mount points differ.
        val byFolderName: Map<String, ArrSeriesInfo> = allSonarrSeries.associateBy {
            it.path.trimEnd('/').substringAfterLast('/').lowercase()
        }
        val tvShows = mediaStore.allItems().filter { it.kind == MediaKind.TV_SHOW }
        var enriched = 0
        for (item in tvShows) {
            val itemPathNorm = item.path.trimEnd('/')
            val info = byPath[itemPathNorm]
                ?: byFolderName[itemPathNorm.substringAfterLast('/').lowercase()]
                ?: continue
            val updated = applyEnrichment(item, info, sonarr)
            if (updated !== item) {
                mediaStore.updateOne(updated)
                enriched++
            }
        }
        if (enriched > 0) Logger.info("sonarr-enrich: updated next-airing for $enriched series")
    }

    /** Enrich a single item. Called after a per-item sync/repull. Returns the enriched item. */
    suspend fun enrichOne(item: MediaItem): MediaItem {
        if (item.kind != MediaKind.TV_SHOW) return item
        val sonarr = configStore.current.sonarr?.takeIf { it.enabled && it.url.isNotBlank() } ?: return item
        val allSonarrSeries = runCatching {
            arrClient.getAllSeriesInfo(sonarr.url, sonarr.apiKey)
        }.getOrElse { return item }
        val itemPath = item.path.trimEnd('/')
        val itemFolder = itemPath.substringAfterLast('/').lowercase()
        val info = allSonarrSeries.firstOrNull { it.path.trimEnd('/') == itemPath }
            ?: allSonarrSeries.firstOrNull { it.path.trimEnd('/').substringAfterLast('/').lowercase() == itemFolder }
            ?: return item
        return applyEnrichment(item, info, sonarr)
    }

    private suspend fun applyEnrichment(
        item: MediaItem,
        info: ArrSeriesInfo,
        sonarr: dev.jellystructure.config.ArrConfig,
    ): MediaItem {
        if (info.status == "ended") {
            return if (item.sonarrStatus == "ended" && item.sonarrNextAiringDate == null) item
            else item.copy(sonarrStatus = "ended", sonarrNextAiringDate = null,
                sonarrNextAiringSeason = null, sonarrNextAiringEpisode = null, sonarrNextAiringTitle = null)
        }

        // Series is continuing — find the next unaired monitored episode.
        val episodes = episodeGate.withPermit {
            runCatching {
                arrClient.getSeriesEpisodes(sonarr.url, sonarr.apiKey, info.id)
            }.getOrElse { return item.copy(sonarrStatus = "continuing") }
        }

        val todayPrefix = todayUtcDateString()  // "yyyy-MM-dd"
        val next = episodes
            .filter { ep ->
                ep.monitored && !ep.hasFile &&
                    ep.airDateUtc != null && ep.airDateUtc.take(10) >= todayPrefix
            }
            .minByOrNull { it.airDateUtc!! }

        return item.copy(
            sonarrStatus = "continuing",
            sonarrNextAiringDate = next?.airDateUtc?.take(10),
            sonarrNextAiringSeason = next?.seasonNumber,
            sonarrNextAiringEpisode = next?.episodeNumber,
            sonarrNextAiringTitle = next?.title?.takeIf { it.isNotBlank() },
        )
    }
}

/** Current UTC date as "yyyy-MM-dd" derived from POSIX epoch. UTC-pinned (no timezone shift). */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun todayUtcDateString(): String {
    val epochSec = time(null)
    var d = (epochSec / 86400).toInt()  // days since 1970-01-01
    var y = 1970
    while (true) {
        val diy = if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 366 else 365
        if (d < diy) break
        d -= diy; y++
    }
    val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
    val monthDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var m = 1
    for (md in monthDays) {
        if (d < md) break
        d -= md; m++
    }
    return "${y}-${m.toString().padStart(2, '0')}-${(d + 1).toString().padStart(2, '0')}"
}
