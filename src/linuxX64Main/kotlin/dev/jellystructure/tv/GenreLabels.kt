package dev.jellystructure.tv

import dev.jellystructure.media.GenreCatalog
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard

/**
 * Phase 271 (FR-271-5) — Home's payloads are built and cached with every genre in English (label rule
 * step 3), and put into the viewer's app language here, after the cache read. So no cache is keyed by
 * language, and a card built outside any viewer's context (the shared Continue Watching list) reads the
 * same as every other card.
 */
internal fun MediaCard.withGenreLabels(lang: String?): MediaCard {
    if (skipRelabel(lang)) return this
    val k = modelKind()
    val g = genre?.let { GenreCatalog.relabel(it, lang, k) }
    val facts = focusDetail?.let { f ->
        if (f.genres.isEmpty()) f else f.copy(genres = f.genres.map { GenreCatalog.relabel(it, lang, k) })
    }
    return if (g == genre && facts == focusDetail) this else copy(genre = g, focusDetail = facts)
}

internal fun HomeFeed.withGenreLabels(lang: String?): HomeFeed {
    if (skipRelabel(lang)) return this
    return copy(
        heroes = heroes.map { h -> h.copy(item = h.item.withGenreLabels(lang)) },
        rows = rows.map { r -> r.copy(items = r.items.map { it.withGenreLabels(lang) }) },
    )
}

// English is what the payload already says.
private fun skipRelabel(lang: String?): Boolean = lang == null || GenreCatalog.normLang(lang) == GenreCatalog.ENGLISH

private fun MediaCard.modelKind(): MediaKind = when (kind) {
    dev.jellystructure.shared.tv.MediaKind.SERIES -> MediaKind.TV_SHOW
    dev.jellystructure.shared.tv.MediaKind.MUSIC_VIDEO -> MediaKind.MUSIC_VIDEO
    else -> MediaKind.MOVIE
}
