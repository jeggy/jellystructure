package dev.jellystructure.tv

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.CertificationResolver
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.MatchMode
import dev.jellystructure.shared.tv.RowConfig

/**
 * Evaluates an R32 workbench condition stack against a [MediaItem]. Facets mirror the Phase-30 axes
 * (studio/network/genre/tag) plus audio-track facets (audio_language incl. an `untagged` value,
 * audio_codec, track_title contains), the Ravilo-layout `hero_item` membership facet, and the
 * Phase-106 `age_rating` facet (the region-cascade-resolved certification code).
 *
 * Phase R86 — WS-I Tier 1: [matches] precomputes a lowercased [ItemFacets] per item before
 * iterating conditions, so [evalOne] uses allocation-free [Set.contains] instead of re-lowercasing
 * the item's facet lists on every condition call.
 */
object ConditionEvaluator {

    /** [heroIds] = item ids (jellyfinId and/or slug) currently in the viewer's hero carousel.
     *  [ageRatingCascade] = the admin-configured region cascade (Phase 106); age_rating conditions
     *  match against the resolved certification code for that cascade. */
    fun matches(item: MediaItem, match: MatchMode, conditions: List<Condition>, heroIds: Set<String>, ageRatingCascade: List<String> = emptyList()): Boolean {
        if (conditions.isEmpty()) return true
        val facets  = ItemFacets.of(item, ageRatingCascade)
        val results = conditions.map { evalOne(item, facets, it, heroIds) }
        return if (match == MatchMode.ANY) results.any { it } else results.all { it }
    }

    // Precomputed lowercased sets for one MediaItem. Built once per matches() call so all
    // conditions for a given item share the same sets rather than re-lowercasing per condition.
    private class ItemFacets(
        val studio: Set<String>,
        val network: Set<String>,
        val genres: Set<String>,
        val tags: Set<String>,
        val audioLanguages: Set<String>,
        val audioCodecs: Set<String>,
        val ageRating: Set<String>,
    ) {
        companion object {
            fun of(item: MediaItem, ageRatingCascade: List<String>): ItemFacets {
                val audio = item.tracks.filter { it.kind == TrackKind.AUDIO }
                val resolvedCode = CertificationResolver.resolve(ageRatingCascade, item.certifications)?.code
                return ItemFacets(
                    studio         = setOfNotNull(item.studio?.lowercase()),
                    network        = setOfNotNull(item.network?.lowercase()),
                    genres         = item.genres.mapTo(HashSet()) { it.lowercase() },
                    tags           = item.tags.mapTo(HashSet()) { it.lowercase() },
                    audioLanguages = audio.mapTo(HashSet()) { it.language?.lowercase() ?: "untagged" },
                    audioCodecs    = audio.mapTo(HashSet()) { it.codec.lowercase() },
                    ageRating      = setOfNotNull(resolvedCode?.lowercase()),
                )
            }
        }
    }

    private fun evalOne(item: MediaItem, facets: ItemFacets, c: Condition, heroIds: Set<String>): Boolean {
        val vals = c.values.map { it.lowercase() }
        return when (c.facet) {
            "studio"         -> setMatch(facets.studio, vals, c.op)
            "network"        -> setMatch(facets.network, vals, c.op)
            "genre"          -> setMatch(facets.genres, vals, c.op)
            "tag"            -> setMatch(facets.tags, vals, c.op)
            "audio_language" -> setMatch(facets.audioLanguages, vals, c.op)
            "audio_codec"    -> setMatch(facets.audioCodecs, vals, c.op)
            "age_rating"     -> setMatch(facets.ageRating, vals, c.op)
            "track_title" -> {
                val needle = vals.firstOrNull() ?: return c.op == "not_contains"
                val has = item.tracks.any { it.title?.lowercase()?.contains(needle) == true }
                if (c.op == "not_contains") !has else has
            }
            "hero_item" -> {
                val featured = (item.jellyfinId != null && heroIds.contains(item.jellyfinId)) || heroIds.contains(item.id)
                val wantFeatured = vals.contains("featured")
                val wantNot = vals.contains("not_featured")
                when {
                    wantFeatured && !wantNot -> featured
                    wantNot && !wantFeatured -> !featured
                    else -> true
                }
            }
            // R87: membership in any of the referenced content rows — evaluated by the same per-title
            // matcher (recursively), so "is_none_of <a channel's rows>" yields the row-coverage gap.
            "content_row" -> {
                if (c.rows.isEmpty()) return c.op == "is_none_of"
                val hit = c.rows.any { row -> rowMatches(item, facets, row, heroIds) }
                if (c.op == "is_none_of") !hit else hit
            }
            else -> true
        }
    }

    /** R87: does [item] fall inside one content row's filter — its include (mediaKind) + its own
     *  condition stack (ALL/ANY), reusing the precomputed [facets] for this item. Mirrors the
     *  mockup's `matchesState`. */
    private fun rowMatches(item: MediaItem, facets: ItemFacets, row: RowConfig, heroIds: Set<String>): Boolean {
        when (row.mediaKind) {
            "MOVIE"  -> if (item.kind != MediaKind.MOVIE) return false
            "SERIES" -> if (item.kind != MediaKind.TV_SHOW) return false
        }
        if (row.conditions.isEmpty()) return true
        val results = row.conditions.map { evalOne(item, facets, it, heroIds) }
        return if (row.match == MatchMode.ANY) results.any { it } else results.all { it }
    }

    /** is_any_of / is_none_of over a pre-lowercased set; allocation-free Set.contains lookup. */
    private fun setMatch(have: Set<String>, vals: List<String>, op: String): Boolean {
        if (vals.isEmpty()) return op == "is_none_of"
        val any = vals.any { v -> have.contains(v) }
        return if (op == "is_none_of") !any else any
    }
}
