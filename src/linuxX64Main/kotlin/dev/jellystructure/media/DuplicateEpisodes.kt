package dev.jellystructure.media

import dev.jellystructure.model.Episode

/**
 * Bug fix (Ravilo auto-play-next loop): two DIFFERENT files that parse to the same `(season, episode)`
 * used to produce two `Episode` entries carrying the SAME `jellyfinId` — the scanner maps ids with
 * `jfBySeasonEp[season to episode]`, which is a per-(season, episode) lookup, so every file that claims
 * S01E01 got episode S01E01's Jellyfin id.
 *
 * Verified in a real library: the whole `Teletubbies.S01…` release folder had been extracted a second
 * time INSIDE the S02 folder, so all ten season-1 episodes existed twice (`episode_count` 40 for a
 * 10 + 20 episode show). Ravilo's series rail groups by file path, so the two copies became two rail
 * entries with one id: finishing episode 1 announced "Up next · Episode 1", navigating to the id
 * already playing did nothing, and the credits card re-armed and re-fired forever. Two more shows hit
 * the same case through mislabelled release files (two different episodes both numbered `S03E08`).
 *
 * Duplicate FILES are a genuine library problem for the operator to resolve (delete the copy, or fix
 * the episode numbering), so nothing here deletes or hides them from the workbench: the extra entries
 * stay in the scanned item and are surfaced as the `duplicate_episode` triage type. What this object
 * guarantees is that exactly ONE entry per `(season, episode)` is treated as the real, identity-owning
 * episode — the one that keeps its `jellyfinId` at scan time and the only one the Ravilo series API
 * exposes — so no client can ever be handed two episodes with the same id again.
 *
 * A multi-episode file (Phase 149, `S01E01E02E03.mkv`) is NOT a duplicate: its entries share a path but
 * have distinct episode numbers, hence distinct keys.
 */
object DuplicateEpisodes {

    private fun key(ep: Episode): Pair<Int, Int>? {
        val season = ep.seasonNumber ?: return null
        val number = ep.episodeNumber ?: return null
        return season to number
    }

    /**
     * Which entry of a duplicate group owns the episode's identity, by a deterministic, explainable
     * rule: an entry that actually resolved a Jellyfin id wins (it is the playable one), then the
     * lexicographically smallest path, then the lowest part index. The path tiebreak is what makes a
     * nested re-extraction lose to the original — `…/Teletubbies.S01…/…` sorts before
     * `…/Teletubbies.S02…/Teletubbies.S01…/…`.
     */
    private val PRIMARY_ORDER: Comparator<Episode> = compareBy(
        { if (it.jellyfinId.isNullOrBlank()) 1 else 0 },
        { it.path },
        { it.partIndex },
    )

    /** Groups of two or more entries sharing one `(season, episode)`. Unparseable numbering is ignored. */
    fun groups(episodes: List<Episode>): Map<Pair<Int, Int>, List<Episode>> =
        episodes.mapNotNull { ep -> key(ep)?.let { it to ep } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }

    /** Indices (into [episodes]) of the redundant entries — every duplicate except its primary. */
    fun extraIndices(episodes: List<Episode>): Set<Int> {
        val byKey = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()
        episodes.forEachIndexed { idx, ep ->
            val k = key(ep) ?: return@forEachIndexed
            byKey.getOrPut(k) { mutableListOf() }.add(idx)
        }
        val extras = mutableSetOf<Int>()
        for ((_, indices) in byKey) {
            if (indices.size < 2) continue
            val primary = indices.minWithOrNull(compareBy(PRIMARY_ORDER) { episodes[it] }) ?: continue
            extras += indices.filterNot { it == primary }
        }
        return extras
    }

    /** How many redundant entries this episode list carries (0 for a healthy series). */
    fun extraCount(episodes: List<Episode>): Int = extraIndices(episodes).size

    /**
     * The episode list with every redundant duplicate removed — one entry per `(season, episode)`,
     * original order preserved. Entries with no parseable numbering are always kept (they have no key
     * to collide on, and dropping them would hide files the operator can still fix).
     */
    fun deduped(episodes: List<Episode>): List<Episode> {
        val extras = extraIndices(episodes)
        if (extras.isEmpty()) return episodes
        return episodes.filterIndexed { idx, _ -> idx !in extras }
    }

    /**
     * The same list with the redundant entries stripped of their `jellyfinId`, so a duplicate can never
     * masquerade as the episode it copies anywhere downstream (play-state hydration, resume, remote
     * `play_item` resolution). The entries themselves are kept for the workbench/triage.
     */
    fun withUniqueIds(episodes: List<Episode>): List<Episode> {
        val extras = extraIndices(episodes)
        if (extras.isEmpty()) return episodes
        return episodes.mapIndexed { idx, ep ->
            if (idx in extras && ep.jellyfinId != null) ep.copy(jellyfinId = null) else ep
        }
    }

    /** One human-readable line per duplicate group, for the scan log. */
    fun describe(episodes: List<Episode>): List<String> =
        groups(episodes).entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (k, eps) ->
                val (season, number) = k
                val code = "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}"
                "$code appears in ${eps.size} files: ${eps.map { it.path }.sorted().joinToString(", ")}"
            }
}
