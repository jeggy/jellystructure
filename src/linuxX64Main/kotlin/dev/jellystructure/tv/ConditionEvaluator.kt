package dev.jellystructure.tv

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.MatchMode

/**
 * Evaluates an R32 workbench condition stack against a [MediaItem]. Facets mirror the Phase-30 axes
 * (studio/network/genre/tag) plus audio-track facets (audio_language incl. an `untagged` value,
 * audio_codec, track_title contains) and the Ravilo-layout `hero_item` membership facet.
 *
 * Phase R86 — WS-I Tier 1: [matches] precomputes a lowercased [ItemFacets] per item before
 * iterating conditions, so [evalOne] uses allocation-free [Set.contains] instead of re-lowercasing
 * the item's facet lists on every condition call.
 */
object ConditionEvaluator {

    /** [heroIds] = item ids (jellyfinId and/or slug) currently in the viewer's hero carousel. */
    fun matches(item: MediaItem, match: MatchMode, conditions: List<Condition>, heroIds: Set<String>): Boolean {
        if (conditions.isEmpty()) return true
        val facets  = ItemFacets.of(item)
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
    ) {
        companion object {
            fun of(item: MediaItem): ItemFacets {
                val audio = item.tracks.filter { it.kind == TrackKind.AUDIO }
                return ItemFacets(
                    studio         = setOfNotNull(item.studio?.lowercase()),
                    network        = setOfNotNull(item.network?.lowercase()),
                    genres         = item.genres.mapTo(HashSet()) { it.lowercase() },
                    tags           = item.tags.mapTo(HashSet()) { it.lowercase() },
                    audioLanguages = audio.mapTo(HashSet()) { it.language?.lowercase() ?: "untagged" },
                    audioCodecs    = audio.mapTo(HashSet()) { it.codec.lowercase() },
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
            else -> true
        }
    }

    /** is_any_of / is_none_of over a pre-lowercased set; allocation-free Set.contains lookup. */
    private fun setMatch(have: Set<String>, vals: List<String>, op: String): Boolean {
        if (vals.isEmpty()) return op == "is_none_of"
        val any = vals.any { v -> have.contains(v) }
        return if (op == "is_none_of") !any else any
    }
}
