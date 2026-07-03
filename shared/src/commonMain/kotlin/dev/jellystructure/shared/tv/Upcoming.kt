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
    /** R167 — not-held-only fallback art, loaded by the client directly from the external *arr/
     *  TMDB CDN (no jellystructure proxy, no on-disk caching). The client prefers [posterUrl] when
     *  present, else this, else the gradient placeholder. */
    @SerialName("poster_remote_url") val posterRemoteUrl: String? = null,
    @SerialName("backdrop_remote_url") val backdropRemoteUrl: String? = null,
)

/**
 * R167 — the enriched detail payload for one Upcoming item (Discover-detail parity: genres/runtime/
 * cast from a live TMDB lookup). Modeled on [DiscoverDetail]; `genres`/`runtime`/`cast` are best-effort
 * (empty/null when the item has no resolvable tmdbId or the TMDB call fails) — the client still shows
 * [item] alone in that case, never a blank page.
 */
@Serializable
data class UpcomingDetail(
    val item: UpcomingItem,
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,
    val cast: List<Person> = emptyList(),
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
