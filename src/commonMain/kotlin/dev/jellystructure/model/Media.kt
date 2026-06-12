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
data class MediaItem(
    val id: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int?,
    val kind: MediaKind,
    val path: String,
    val tmdbId: Int?,
    val originalLanguage: String?,
    val resolvedLanguage: String? = null,
    val posterPath: String?,
    val backdropPath: String? = null,
    val overview: String?,
    val genres: List<String> = emptyList(),
    val tracks: List<Track>,
    val issueCount: Int,
    val scannedAt: Long,
)

@Serializable
data class MediaPage(
    val items: List<MediaItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)
