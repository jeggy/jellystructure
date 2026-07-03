package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * R160 — an upcoming-calendar item's server-resolved state. The UI never sees "sonarr"/"radarr" —
 * only this. `MONITORED` = scheduled, not due yet; `DOWNLOADING` = actively grabbing (with
 * [UpcomingItem.progress]); `AVAILABLE` = already imported (routes to the real detail page);
 * `MISSING` = released in the past, monitored, never imported.
 */
enum class UpcomingStatus { MONITORED, DOWNLOADING, AVAILABLE, MISSING }

/**
 * R160 — one row in the Upcoming calendar: a Sonarr-monitored episode or a Radarr-monitored movie
 * release, resolved server-side (no source attribution ever reaches the client). `itemId` is set
 * when the title (its series, for an episode) already exists in the jellystructure catalogue —
 * the client routes there instead of the lightweight [UpcomingDetail] and renders its artwork via
 * the normal `/tv/image/{itemId}/…` proxy; null renders the gradient placeholder (no external-image
 * proxying this phase).
 */
@Serializable
data class UpcomingItem(
    val id: String,
    val kind: MediaKind,               // MOVIE | SERIES — client shows "Movie" / "Episode"
    val title: String,
    val year: Int? = null,
    val genre: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    /** Set alongside [itemId] (on-disk artwork via the normal image proxy); null → gradient placeholder. */
    @SerialName("poster_url") val posterUrl: String? = null,
    /** yyyy-MM-dd, date-only — the calendar groups/sorts by this. */
    val date: String,
    /** Series air time, "HH:mm" local-to-the-episode; null for movies. */
    val time: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    /** Movies only: "digital" | "physical" | "cinema". */
    @SerialName("release_type") val releaseType: String? = null,
    /** Series only. */
    val network: String? = null,
    val status: UpcomingStatus,
    /** 0-100, only meaningful when [status] == DOWNLOADING. */
    val progress: Int? = null,
    val synopsis: String? = null,
)

/**
 * R160 — the whole Upcoming payload. `enabled` gates the nav tab (server-derived from `[sonarr]`/
 * `[radarr]` presence — `RaviloConfig` itself carries no *arr config). `items` is today-and-future
 * (any status); `missing` is the bounded (~6 months) overdue set for the "Missing from your library"
 * section — disjoint from `items`, sorted most-recent-first.
 */
@Serializable
data class UpcomingFeed(
    val enabled: Boolean,
    val items: List<UpcomingItem> = emptyList(),
    val missing: List<UpcomingItem> = emptyList(),
)
