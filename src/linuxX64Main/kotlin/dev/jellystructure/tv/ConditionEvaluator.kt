package dev.jellystructure.tv

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.CertificationResolver
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.MatchMode
import dev.jellystructure.shared.tv.QueryJoin
import dev.jellystructure.shared.tv.QueryNode
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.effectiveQuery
import dev.jellystructure.shared.tv.isLive
import dev.jellystructure.shared.tv.migrateFlatQuery

/**
 * Evaluates a Phase 140 workbench query tree against a [MediaItem] (recursive AND/OR blocks with
 * per-block NOT and nestable sub-blocks — see `:shared`'s `QueryTree.kt`). Facets mirror the
 * Phase-30 axes (studio/network/genre/tag) plus audio-track facets (audio_language incl. an
 * `untagged` value, audio_codec, track_title contains), the Ravilo-layout `hero_item` membership
 * facet, and the Phase-106 `age_rating` facet (the region-cascade-resolved certification code).
 *
 * Phase R86 — WS-I Tier 1: [matches] precomputes a lowercased [ItemFacets] per item before
 * recursing the tree, so [evalOne] uses allocation-free [Set.contains] instead of re-lowercasing
 * the item's facet lists on every condition call. The precompute happens exactly once per item
 * regardless of how deep/wide the tree is.
 *
 * Phase 140 — one evaluator, no parallel code path: the legacy flat `(match, conditions)` overload
 * is a thin wrapper that migrates to a tree ([migrateFlatQuery]) and recurses the same way.
 */
object ConditionEvaluator {

    /** [heroIds] = item ids (jellyfinId and/or slug) currently in the viewer's hero carousel.
     *  [ageRatingCascade] = the admin-configured region cascade (Phase 106); age_rating conditions
     *  match against the resolved certification code for that cascade. */
    fun matches(item: MediaItem, match: MatchMode, conditions: List<Condition>, heroIds: Set<String>, ageRatingCascade: List<String> = emptyList()): Boolean {
        if (conditions.isEmpty()) return true
        return matches(item, migrateFlatQuery(match, conditions), heroIds, ageRatingCascade)
    }

    /** Phase 140 — the tree-native entry point; the flat overload above is a thin wrapper over this. */
    fun matches(item: MediaItem, root: ConditionGroup, heroIds: Set<String>, ageRatingCascade: List<String> = emptyList()): Boolean {
        val facets = ItemFacets.of(item, ageRatingCascade)
        return evalGroup(item, facets, root, heroIds)
    }

    // Precomputed lowercased sets for one MediaItem. Built once per matches() call so every node
    // in the tree for a given item shares the same sets rather than re-lowercasing per condition.
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
                    studio         = (listOfNotNull(item.studio) + item.secondaryStudios).mapTo(HashSet()) { it.lowercase() },
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

    /** Recursive group eval: filters to *live* children first (a not-yet-filled-in condition or an
     *  empty sub-block is skipped, never contributes `false` to an AND block — this deliberately
     *  replaces the old flat evaluator's quirk where an empty-values `is_any_of` condition evaluated
     *  to `false`; see [Condition]/`isLive`'s docs in QueryTree.kt). No live children = neutral
     *  (matches everything), regardless of [ConditionGroup.not] — an exclusion over nothing excludes
     *  nothing (deliberately diverges from the design mockup's literal `matchNode`). */
    private fun evalGroup(item: MediaItem, facets: ItemFacets, g: ConditionGroup, heroIds: Set<String>): Boolean {
        val live = g.children.filter { it.isLive() }
        if (live.isEmpty()) return true
        val hit = if (g.join == QueryJoin.OR) live.any { evalNode(item, facets, it, heroIds) }
                  else live.all { evalNode(item, facets, it, heroIds) }
        return if (g.not) !hit else hit
    }

    private fun evalNode(item: MediaItem, facets: ItemFacets, n: QueryNode, heroIds: Set<String>): Boolean = when (n) {
        is Condition -> evalOne(item, facets, n, heroIds)
        is ConditionGroup -> evalGroup(item, facets, n, heroIds)
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
     *  query tree, reusing the precomputed [facets] for this item. [RowConfig.effectiveQuery] migrates
     *  the row's legacy `match`/`conditions` on the fly if it hasn't been migrated yet, so an embedded
     *  row from an old saved filter still evaluates correctly. */
    private fun rowMatches(item: MediaItem, facets: ItemFacets, row: RowConfig, heroIds: Set<String>): Boolean {
        when (row.mediaKind) {
            "MOVIE"  -> if (item.kind != MediaKind.MOVIE) return false
            "SERIES" -> if (item.kind != MediaKind.TV_SHOW) return false
        }
        return evalGroup(item, facets, row.effectiveQuery(), heroIds)
    }

    /** is_any_of / is_none_of over a pre-lowercased set; allocation-free Set.contains lookup. Note:
     *  the `vals.isEmpty()` branch below is now unreachable via the tree path (a condition with no
     *  values is filtered out by [evalGroup]'s live-children check before evalOne ever runs) — kept
     *  only as a defensive fallback for any future direct caller. */
    private fun setMatch(have: Set<String>, vals: List<String>, op: String): Boolean {
        if (vals.isEmpty()) return op == "is_none_of"
        val any = vals.any { v -> have.contains(v) }
        return if (op == "is_none_of") !any else any
    }
}
