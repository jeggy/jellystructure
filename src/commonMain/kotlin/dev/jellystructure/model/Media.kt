package dev.jellystructure.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaKind { MOVIE, TV_SHOW }

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
    val tmdbEpisodeId: Int? = null,
    /** Phase 76: episode-specific guest stars (from TMDB episode credits). */
    val guestStars: List<Person> = emptyList(),
    /** Phase 76: episode-specific crew (director, writer, etc.). */
    val crew: List<Person> = emptyList(),
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
    val jellyfinLockData: Boolean = false,
    val jellyfinLockedFields: List<String> = emptyList(),
    val titlesByLang: Map<String, String> = emptyMap(),
    val cast: List<Person> = emptyList(),
    val crew: List<Person> = emptyList(),
    val imdbId: String? = null,
    val tvdbId: Int? = null,
)

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
