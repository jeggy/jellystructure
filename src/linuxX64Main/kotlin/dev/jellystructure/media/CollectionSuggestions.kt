package dev.jellystructure.media

import dev.jellystructure.model.Franchise
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.franchisesOf

/**
 * DEB-4075 (DEB-4032, FR-7) — the franchises worth offering as a Jellyfin collection: the ones `franchisesOf` serves (two or more held
 * films, a display name, the most recently added first) that no collection covers yet. A franchise is covered when a collection has its
 * display name (case-insensitive) or holds every one of its films.
 *
 * A film that has no Jellyfin id cannot be put in a collection, so it is not offered; a franchise left with fewer than two films that
 * can be is not suggested. Nothing here writes anything: *Create collection* only opens the editor, filled in.
 */
fun collectionSuggestions(titles: List<MediaItem>, snapshot: JellyfinCollectionsSnapshot): List<Franchise> {
    val names = snapshot.collections.map { it.name.trim().lowercase() }.toSet()
    val held = snapshot.collections.map { it.titleIds.toSet() }
    return franchisesOf(titles).mapNotNull { f ->
        val films = f.films.filter { !it.jellyfinId.isNullOrBlank() }
        if (films.size < 2) return@mapNotNull null
        val covered = f.name.trim().lowercase() in names || held.any { h -> films.all { it.id in h } }
        if (covered) null else Franchise(f.id, f.name, films)
    }
}
