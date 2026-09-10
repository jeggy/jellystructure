package dev.jellystructure.media

import dev.jellystructure.model.MediaItem

/**
 * Phase 199 (FR-199-2) — extracted out of `MediaStore`/`Scanner`, same shape as [preserveLockedArtwork]/
 * [preserveTmdbMatchLock]/[preserveMetadataLanguage]: a pure function taking the JS-tag name set as an
 * explicit parameter (rather than reaching for `jsTagStore` itself) so it can be unit-tested directly.
 * `preserveJsTags` and `mergeRepullTags` were the last two guards still inlined and unreachable from a
 * test — precisely why the FR-199-1 bug (keyed off `existing` instead of the predecessor `old`) survived
 * three phases of adjacent work that each got the parameter right for their own field.
 *
 * Constitution invariant #6, restated with the predecessor rule FR-199-6 adds: across a slug rename the
 * predecessor is not the row under the item's own id (`existing`) but the row the fresh item replaces
 * (`old` — the stale duplicate under the previous id, when one exists). A guard keyed on `existing` sees
 * `null` on exactly that rename and preserves nothing.
 */
internal fun preserveJsTags(fresh: MediaItem, existing: MediaItem?, jsTagNames: Set<String>): MediaItem {
    val keptJs = existing?.tags?.filter { it in jsTagNames } ?: return fresh
    if (keptJs.isEmpty()) return fresh
    return fresh.copy(tags = (fresh.tags + keptJs).distinct())
}

/**
 * TMDB re-pull tag rule (Phase 19 §15): TMDB keywords become the non-JS tags, and any Jellystructure-
 * defined tags on the item always survive. Jellyfin-sourced tags that are neither are dropped — TMDB is
 * authoritative for non-JS tags on a TMDB re-pull.
 */
internal fun mergeRepullTags(tmdbTags: List<String>, existingTags: List<String>, jsTagNames: Set<String>): List<String> =
    (tmdbTags + existingTags.filter { it in jsTagNames }).distinct()
