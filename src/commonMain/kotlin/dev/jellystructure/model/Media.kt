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

/** Phase 150: a mid/post-credits scene TMDB flags via the `duringcreditsstinger`/`aftercreditsstinger`
 *  keywords (ingested as ordinary tags per Phase 51 — see `MediaStore`/`Scanner`'s TMDB re-pull merge).
 *  [kind] is `"during"` or `"after"`; [atMs] is null until an admin (or a future detector) pins the
 *  exact moment — its mere presence is enough for the player to never auto-skip past it. */
@Serializable
data class Stinger(
    val atMs: Long? = null,
    val kind: String,
)

/** Phase 150 (FR-SEG1-1): where this title's intro and credits segments actually start, so Ravilo can
 *  offer Skip Intro / Skip Credits instead of a fixed "N seconds before the file ends" guess. Embedded
 *  on both [Episode] and [MediaItem] (a movie has no episodes but still has its own credits/intro) —
 *  nullable fields, JSON-blob only, exactly like Phase 149's `chapterStartMs`/`hasChapters`: no schema
 *  migration needed, a plain default-valued field addition.
 *  [source] — `"chapter"` (title pattern match) | `"heuristic"` (ffmpeg black/silence/position) |
 *  `"fingerprint"` (cross-episode audio match) | `"manual"` (admin-entered/edited). [confidence] is
 *  `0..1` for the two automatic-and-uncertain sources (`heuristic`/`fingerprint`) and null for
 *  `chapter`/`manual` (an exact marker or an admin's own eyes need no confidence score) — resolution
 *  precedence is manual > chapter > numeric confidence, never a null-vs-number comparison (dev-review
 *  addendum §4). [manuallyConfirmed] is set the moment an admin edits any field via the segment
 *  scrubber; a scan must never silently overwrite a manual correction — only an explicit Re-scan clears it.
 */
@Serializable
data class SegmentMarkers(
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val creditsStartMs: Long? = null,
    val stinger: Stinger? = null,
    val source: String? = null,
    val confidence: Double? = null,
    val manuallyConfirmed: Boolean = false,
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
    /** R187 (Quality facet) — only set for kind == VIDEO. [videoRange] is a simple "SDR"/"HDR" flag
     *  (from ffprobe's color_transfer: smpte2084/arib-std-b67 = HDR) — not a full HDR10/HDR10+/DV
     *  profile breakdown; R183's separate playback-negotiation code already owns that finer distinction
     *  for streaming decisions. This is display/filtering only. */
    val width: Int? = null,
    val height: Int? = null,
    val videoRange: String? = null,
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
    /** Phase 149: multi-episode files (e.g. `S01E01E02E03.mkv`) model as N Episodes sharing one `path`/
     *  `filename` — a "file group". [partIndex] is this episode's 0-based position within the group
     *  (ordered by episode number); [partCount] is the group size (1 for a normal single-episode file).
     *  Both default to the single-episode case so no migration/backfill is needed. */
    val partIndex: Int = 0,
    val partCount: Int = 1,
    /** Phase 149: this episode's start/end offset (ms) within the shared file, when the container has
     *  chapter markers whose count matches the contained-episode count. Null when the file has no usable
     *  chapters — the group still plays as one continuous unit, never an error. */
    val chapterStartMs: Long? = null,
    val chapterEndMs: Long? = null,
    /** Phase 149: whether [chapterStartMs]/[chapterEndMs] are populated for every episode in this file's
     *  group (all-or-nothing per file — either the chapter count matched and every episode got an offset,
     *  or none did). */
    val hasChapters: Boolean = false,
    /** Phase 150: this episode's own intro/credits segments (see [SegmentMarkers]). */
    val segments: SegmentMarkers = SegmentMarkers(),
    /** Phase 153 (FR-SCAN2-5): Jellyfin has an item for this file but no `IndexNumber` for it — its
     *  own scan-time resolve failed and Jellyfin never retries one, so the episode silently drops out
     *  of Jellyfin's NextUp/Resume (and therefore Ravilo's Continue Watching) forever. Set from the
     *  matched `JellyfinEpisodeItem` on every scan, so it clears itself once a repair lands. Only ever
     *  true when jellystructure DID parse a real episode number from the filename — an episode we can't
     *  number either is not repairable and is never flagged. */
    val jellyfinIndexMissing: Boolean = false,
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
    // Every OTHER TMDB production company beyond the primary one (e.g. Netflix/Apple credited as
    // a secondary company, not lead studio) — Studio-facet filters match against this too, so a
    // streaming platform's originals are findable even when it's never index-0. Display/NFO/the
    // Metadata screen's Studios tab still use `studio` alone; this is filter-matching only.
    val secondaryStudios: List<String> = emptyList(),
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
    // Phase 142: the owning Jellyfin library's ItemId (= config LibraryMapping.jellyfinId), captured at
    // scan time. Gates Ravilo visibility for restricted (non-EnableAllFolders) Jellyfin users — see
    // MediaStore.visibleTo. Null on pre-142 rows until the one-time path-prefix backfill runs.
    val libraryId: String? = null,
    /** Phase 150: this item's own intro/credits segments (movies only in practice — a TV_SHOW's
     *  episodes each carry their own via [Episode.segments]; see [SegmentMarkers]). */
    val segments: SegmentMarkers = SegmentMarkers(),
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
