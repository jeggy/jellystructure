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
    val cast: List<Person> = emptyList(),
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
