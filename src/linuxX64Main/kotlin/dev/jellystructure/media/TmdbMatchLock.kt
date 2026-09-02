package dev.jellystructure.media

import dev.jellystructure.model.MediaItem

/**
 * Phase 174 — the single definition of "what a TMDB match owns on a [MediaItem]".
 *
 * Both the explicit `POST /{id}/tmdb-match/clear` route and the store-write guard below run through
 * this, so the two can never drift apart — the guard exists precisely because a full scan rebuilds the
 * item from scratch and would otherwise re-apply a match the operator has already rejected.
 *
 * Deliberately NOT cleared (see the phase spec): `title` and `year` — neither is TMDB-exclusive (the
 * scanner falls back to the parsed filename and Jellyfin's `ProductionYear` respectively) and both have
 * their own manual-edit path; `tags` — Jellyfin-sourced; `titlesByLang` — mixes TMDB translations with
 * manual `metadata_edit` titles and is union-merged on every write.
 */
internal fun clearTmdbMatch(item: MediaItem): MediaItem = item.copy(
    tmdbId = null,
    posterPath = null,
    backdropPath = null,
    overview = null,
    genres = emptyList(),
    tmdbGenres = emptyList(),
    studio = null,
    studioTmdbId = null,
    studioLogoPath = null,
    secondaryStudios = emptyList(),
    originalTitle = null,
    originalLanguage = null,
    cast = emptyList(),
    crew = emptyList(),
    imdbId = null,
    runtime = null,
    certifications = emptyMap(),
    trailer = null,
    imdbRating = null,
    tmdbMatchLocked = true,
    // Phase 184 (FR-184-3): a cleared match invalidates any manually-chosen fetch language too — there
    // is no TMDB entry left for it to have been a language OF.
    metadataLanguage = null,
    metadataLanguageSetAt = null,
)

/**
 * Phase 174 — the [MediaItem.tmdbMatchLocked] equivalent of [preserveLockedArtwork], applied at both
 * store write choke points.
 *
 * `Scanner.rescanMetadata` refuses to touch TMDB for a locked item, but the from-scratch paths
 * (`scanItem` → `scanMovie`/`scanMusicVideo` → `addOrUpdate`, and `syncMovie`/`rescanFromJellyfin` →
 * `updateOne`) build a `MediaItem` with no store access at all: they carry the flag's `false` default
 * and will have re-run the very search that produced the wrong match. Carrying the flag forward alone
 * isn't enough — the freshly-matched fields have to go too, or the match returns on every scan.
 *
 * `title`/`year` ARE carried forward here, unlike [clearTmdbMatch]: this path knows [fresh]'s values came
 * out of a re-search it is discarding, whereas the explicit clear route can't tell an operator-edited
 * title from a TMDB one.
 */
internal fun preserveTmdbMatchLock(fresh: MediaItem, existing: MediaItem?): MediaItem {
    if (existing?.tmdbMatchLocked != true) return fresh
    // `tmdbId == null` ⇒ nothing re-matched, so there's no residue to strip and — crucially — no reason
    // to touch title/year: this is the ordinary store-derived write (a metadata edit, a track change,
    // a stamped timestamp), and carrying the stored title/year forward here would silently revert an
    // operator's own edit on every locked item. Only the flag needs to survive.
    if (fresh.tmdbId == null) return fresh.copy(tmdbMatchLocked = true)
    return clearTmdbMatch(fresh).copy(title = existing.title, year = existing.year)
}
