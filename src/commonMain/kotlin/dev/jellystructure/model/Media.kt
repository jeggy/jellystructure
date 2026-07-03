package dev.jellystructure.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaKind { MOVIE, TV_SHOW }

/** Phase 130: one official trailer reference per title (YouTube/Vimeo) — a reference only, no
 *  hosting/download. `thumb` is Vimeo-only (resolved once at ingest via oEmbed); YouTube thumbnails
 *  are derived client-side from `key`. */
@Serializable
data class MediaTrailer(
    val site: String,   // "youtube" | "vimeo"
    val key: String,
    val name: String = "",
    val thumb: String? = null,
)

/** Phase 131: one stored IMDb rating (0-10 aggregate + raw vote count), refreshed by the scheduled
 *  sync pipeline step / manual Re-sync — never fetched at detail/feed-read time. */
@Serializable
data class ImdbRating(
    val aggregateRating: Double,
    val voteCount: Long,
    val syncedAt: Long,
)

@Serializable
enum class TrackKind { VIDEO, AUDIO, SUBTITLE, DATA }

@Serializable
data class Track(
    val streamIndex: Int,
    val specifier: String,
    val kind: TrackKind,
    val codec: String,
    val language: String?,
    val title: String?,
    val default: Boolean,
    val forced: Boolean,
)

@Serializable
data class Person(
    val tmdbId: Int,
    val name: String,
    val profilePath: String? = null,
    val character: String? = null,
    val role: String? = null,
    val job: String? = null,
    val department: String? = null,
    val order: Int = 0,
    val type: String = "Actor",
    /** Total episode count from TMDB aggregate_credits (Phase 76). */
    val episodeCount: Int = 0,
    /**
     * Phase 80: per-season episode counts for main cast, from TMDB season `aggregate_credits`.
     * Key = season number (as string), value = number of episodes this person appears in that season.
     * A season absent from the map = the actor is NOT in that season (TMDB returned no credit).
     * This is the accurate, TMDB-available granularity (recurring cast is season-level).
     */
    val seasonEpisodeCounts: Map<String, Int> = emptyMap(),
    /**
     * Phase 76/80: explicit per-episode presence OVERRIDES for main cast (operator-curated).
     * Key = season number (as string), value = list of episode numbers this person appears in.
     * TMDB cannot resolve recurring cast per episode, so this is populated only by manual toggles.
     * Empty = no override → fall back to season membership (`seasonEpisodeCounts`).
     * NOTE (Phase 80): empty no longer means "present in all episodes".
     */
    val episodePresence: Map<String, List<Int>> = emptyMap(),
)

@Serializable
data class Episode(
    val filename: String,
    val path: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val tracks: List<Track>,
    val issueCount: Int,
    val resolvedLanguage: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    /** Phase 121: whether a still image (TMDB download or screen-grab, either lands at the same disk
     *  path) exists on disk for this episode — the actual "does Ravilo have a picture to show" signal,
     *  distinct from [stillPath] which is just TMDB's metadata URL. Refreshed by [dev.jellystructure.media.ArtworkDownloader.stampHasStill]
     *  wherever a scan/rescan/sync (re)builds the episode list, so triage/Library filtering stay O(1). */
    val hasStill: Boolean = false,
    val tmdbEpisodeId: Int? = null,
    /** Phase 76: episode-specific guest stars (from TMDB episode credits). */
    val guestStars: List<Person> = emptyList(),
    /** Phase 76: episode-specific crew (director, writer, etc.). */
    val crew: List<Person> = emptyList(),
    /** R82: Jellyfin item id for this episode (the playable id). Null until re-scanned after R82. */
    val jellyfinId: String? = null,
    /** R82: Episode runtime in minutes from TMDB. Null until re-scanned after R82. */
    val runtime: Int? = null,
    /** R148: episode first-air date (ISO yyyy-MM-dd) from TMDB. Null until re-scanned after R148. */
    val airDate: String? = null,
    /** Phase 108: JS "first-seen" timestamp (epoch seconds) — stamped once when this episode is first
     *  scanned, preserved on every later re-scan. The sort key for a series' Newly Added placement is
     *  max(episode.createdAt) across its episodes — a new episode re-floats the series. */
    val createdAt: Long? = null,
    /** Phase 108: Jellyfin's own DateCreated for this episode, when the episode-list fetch provides it. Display only. */
    val jellyfinCreatedAt: Long? = null,
)

@Serializable
data class MediaItem(
    val id: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int?,
    val kind: MediaKind,
    val path: String,
    val jellyfinId: String? = null,
    val tmdbId: Int?,
    val originalLanguage: String?,
    val resolvedLanguage: String? = null,
    val posterPath: String?,
    val backdropPath: String? = null,
    val overview: String?,
    val genres: List<String> = emptyList(),
    val tmdbGenres: List<String> = emptyList(),  // Phase 94: TMDB's raw genre baseline (written only by scan/sync). User provenance is derived: added = genres − tmdbGenres, removed = tmdbGenres − genres.
    val missingFromSource: Boolean = false,  // Phase 95: set when a scan no longer finds this item in Jellyfin. The scanner never deletes — it flags so the item surfaces in Triage for the admin to act on.
    val missingSince: Long? = null,          // Phase 95: epoch-seconds when first detected missing (for the triage subline).
    val tags: List<String> = emptyList(),
    val director: String? = null,
    val studio: String? = null,
    val studioTmdbId: Int? = null,
    val studioLogoPath: String? = null,
    val network: String? = null,
    val networkTmdbId: Int? = null,
    val networkLogoPath: String? = null,
    val tracks: List<Track>,
    val episodes: List<Episode> = emptyList(),
    val issueCount: Int,
    val languageMix: Boolean = false,
    val scannedAt: Long,
    // Jellyfin's DateCreated (epoch seconds) = the real library "date added". Null until a scan
    // populates it; the "recently added" sort uses `addedAt ?: scannedAt`. (scannedAt is only the
    // scan timestamp, so it sorts by scan order, not add order.)
    val addedAt: Long? = null,
    val jellyfinLockData: Boolean = false,
    val jellyfinLockedFields: List<String> = emptyList(),
    val titlesByLang: Map<String, String> = emptyMap(),
    val cast: List<Person> = emptyList(),
    val crew: List<Person> = emptyList(),
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    /** R82: Runtime in minutes (movies only; series episodes carry their own runtime). Null until re-scanned. */
    val runtime: Int? = null,
    /** R82: Season display names keyed by season number. Absent seasons fall back to "Season N". */
    val seasonNames: Map<Int, String> = emptyMap(),
    // R149: Sonarr-sourced series status + next-airing (null = not yet enriched or Sonarr not configured).
    val sonarrStatus: String? = null,         // "continuing" | "ended"
    val sonarrNextAiringDate: String? = null, // yyyy-MM-dd (UTC-pinned date from Sonarr)
    val sonarrNextAiringSeason: Int? = null,
    val sonarrNextAiringEpisode: Int? = null,
    val sonarrNextAiringTitle: String? = null,
    // Phase 106: raw per-country TMDB certifications (uppercase ISO-3166-1 → code), e.g. {"DK":"15","US":"PG-13"}.
    // The SHOWN rating is never stored — it's resolved on read from this map + the configured region
    // cascade (dev.jellystructure.resolver.CertificationResolver), so re-ordering the cascade changes
    // what's displayed everywhere with no re-scan.
    val certifications: Map<String, String> = emptyMap(),
    // Phase 108: JS-owned timestamps — createdAt is stamped once (first insert) and never moves;
    // updatedAt bumps only when the stored content actually changed (MediaStore.stampTimestamps).
    // Distinct from `addedAt` (Jellyfin's DateCreated) and `scannedAt` (scan-order, not add-order).
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    /** Phase 108: Jellyfin's own DateLastSaved, when available. Display only. */
    val jellyfinUpdatedAt: Long? = null,
    // Phase 115: sync-chain state for the drift banner. nfoWrittenAt/nfoHash are stamped by
    // NfoWriter.write() on every successful write (button + pipeline); jfSyncedAt is stamped whenever a
    // Jellyfin refresh is triggered for this item. Together they let the drift check distinguish "we
    // haven't written the NFO yet" from "Jellyfin hasn't re-read it yet" from real external drift.
    val nfoWrittenAt: Long? = null,
    val nfoHash: String? = null,
    val jfSyncedAt: Long? = null,
    // Phase 133: which of "poster"/"backdrop" were manually picked/uploaded by an operator, as opposed
    // to the TMDB default — preserved across every automatic scan/sync/re-pull at the MediaStore.addOrUpdate
    // choke point (mirrors preserveJsTags/mergeUserGenres); only an explicit re-pick/upload changes it.
    val lockedArtwork: List<String> = emptyList(),
    // Phase 130: one official trailer from TMDB /videos, refreshed on every scan/sync/re-pull (no usable
    // video ⇒ null, clearing a stale one). Manual Clear/Re-fetch via the admin routes bypass ingest.
    val trailer: MediaTrailer? = null,
    // Phase 131: IMDb rating from imdbapi.dev, refreshed only by the scheduled sync step or manual
    // Re-sync — a failed/absent lookup leaves the previous value intact (never blanks a good rating).
    val imdbRating: ImdbRating? = null,
)

/** Phase 108: the sort key every "recently added" surface uses (Ravilo's Newly Added, Browse default,
 *  the admin Library default sort, related-by-genre). Movies sort by their own createdAt; a series
 *  sorts by its most-recently-added EPISODE (max(episode.createdAt)) so a new episode re-floats it —
 *  not by the series record's own createdAt. Falls back to scannedAt for pre-backfill legacy rows. */
fun MediaItem.recencyKey(): Long = when (kind) {
    MediaKind.MOVIE -> createdAt ?: scannedAt
    MediaKind.TV_SHOW -> episodes.mapNotNull { it.createdAt }.maxOrNull() ?: createdAt ?: scannedAt
}

@Serializable
data class MediaPage(
    val items: List<MediaItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

/** One NFO file an item could have on disk (Phase 44 NFO raw viewer tree). */
@Serializable
data class NfoFileNode(
    val label: String,        // "movie.nfo" / "tvshow.nfo" / "S01E03 — Title"
    val readUrl: String,      // server-built read route; the client fetches this verbatim
    val exists: Boolean,
    val path: String,         // on-disk path, display only
    val season: Int? = null,
    val episode: Int? = null,
)

/** The full tree of NFO files for an item; series episodes are ordered by season/episode. */
@Serializable
data class NfoFileTree(
    val kind: MediaKind,
    val files: List<NfoFileNode>,
)
