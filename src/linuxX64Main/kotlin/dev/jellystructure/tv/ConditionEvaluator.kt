package dev.jellystructure.tv

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.MatchMode

/**
 * Evaluates an R32 workbench condition stack against a [MediaItem]. Facets mirror the Phase-30 axes
 * (studio/network/genre/tag) plus audio-track facets (audio_language incl. an `untagged` value,
 * audio_codec, track_title contains) and the Ravilo-layout `hero_item` membership facet.
 */
object ConditionEvaluator {

    /** [heroIds] = item ids (jellyfinId and/or slug) currently in the viewer's hero carousel. */
    fun matches(item: MediaItem, match: MatchMode, conditions: List<Condition>, heroIds: Set<String>): Boolean {
        if (conditions.isEmpty()) return true
        val results = conditions.map { evalOne(item, it, heroIds) }
        return if (match == MatchMode.ANY) results.any { it } else results.all { it }
    }

    private fun evalOne(item: MediaItem, c: Condition, heroIds: Set<String>): Boolean {
        val vals = c.values.map { it.lowercase() }
        return when (c.facet) {
            "studio" -> listMatch(listOfNotNull(item.studio), vals, c.op)
            "network" -> listMatch(listOfNotNull(item.network), vals, c.op)
            "genre" -> listMatch(item.genres, vals, c.op)
            "tag" -> listMatch(item.tags, vals, c.op)
            "audio_language" -> {
                val langs = audioTracks(item).map { it.language?.lowercase() ?: "untagged" }
                listMatch(langs, vals, c.op)
            }
            "audio_codec" -> listMatch(audioTracks(item).map { it.codec.lowercase() }, vals, c.op)
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

    private fun audioTracks(item: MediaItem) = item.tracks.filter { it.kind == TrackKind.AUDIO }

    /** is_any_of / is_none_of over a list of values; case-insensitive exact membership. */
    private fun listMatch(have: List<String>, vals: List<String>, op: String): Boolean {
        if (vals.isEmpty()) return op == "is_none_of"
        val haveLc = have.map { it.lowercase() }
        val any = vals.any { v -> haveLc.contains(v) }
        return if (op == "is_none_of") !any else any
    }
}
