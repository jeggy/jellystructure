package dev.jellystructure.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DEB-4075 (DEB-4032, FR-5..FR-7) — the admin's Jellyfin collections, as `/api/jellyfin-collections` answers them. Admin-only and new, so no
 * installed client reads them. The page renders these and computes nothing (CLAUDE.md: server-pushed state only).
 */
@Serializable
data class JellyfinCollectionMemberDto(
    /** The jellystructure media id (what every other admin route names a title by), never the Jellyfin id. */
    val id: String,
    val title: String,
    val year: Int? = null,
    val kind: MediaKind,
)

@Serializable
data class JellyfinCollectionDto(
    /** The collection's Jellyfin id: the only id a collection has, because Jellyfin is its only store. */
    val id: String,
    val name: String,
    /** The members jellystructure holds, in Jellyfin's order. */
    val members: List<JellyfinCollectionMemberDto> = emptyList(),
    /** Films, series and music videos in the collection that jellystructure does not hold (a library it skips, an unknown id). Never removed. */
    @SerialName("unmapped_count") val unmappedCount: Int = 0,
)

/** `GET /api/jellyfin-collections`. [configured] is false while no Jellyfin URL or key is set, so the page can say so instead of "none". */
@Serializable
data class JellyfinCollectionsPageDto(
    val configured: Boolean = true,
    val collections: List<JellyfinCollectionDto> = emptyList(),
)

/** FR-7 — one franchise that no collection covers yet: its display name and its films in release order. */
@Serializable
data class JellyfinCollectionSuggestionDto(
    @SerialName("franchise_id") val franchiseId: String,
    val name: String,
    val films: List<JellyfinCollectionMemberDto> = emptyList(),
)

/** `POST /api/jellyfin-collections`. [items] are jellystructure media ids. */
@Serializable
data class CreateJellyfinCollectionRequest(val name: String, val items: List<String> = emptyList())

/** `PATCH /api/jellyfin-collections/{id}`. */
@Serializable
data class RenameJellyfinCollectionRequest(val name: String)

/** `POST /api/jellyfin-collections/{id}/items`. [items] are jellystructure media ids. */
@Serializable
data class AddJellyfinCollectionItemsRequest(val items: List<String> = emptyList())
