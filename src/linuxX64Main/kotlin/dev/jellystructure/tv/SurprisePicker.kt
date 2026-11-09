package dev.jellystructure.tv

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.SurpriseAnswer
import kotlin.random.Random

/**
 * DEB-3984 (FR-12) — Surprise me's pick. One title, uniformly at random, from what [items] holds (the caller passes only
 * what the viewer can see): a movie the viewer has never played, or a series none of whose episodes they have played.
 * A title counts as played once it is marked played or carries a resume position, so a film left half-watched is not
 * offered as a surprise. [exclude] holds the ids this run has already shown, as the card id the app knows them by (the
 * Jellyfin id, else ours). `null` when nothing is left, which the route answers as a `204`.
 */
internal object SurprisePicker {
    fun pick(items: List<MediaItem>, playstate: Map<String, CardPlayState>, exclude: Set<String>, random: Random = Random.Default): SurpriseAnswer? {
        val pool = pool(items, playstate, exclude)
        if (pool.isEmpty()) return null
        return pool[random.nextInt(pool.size)]
    }

    internal fun pool(items: List<MediaItem>, playstate: Map<String, CardPlayState>, exclude: Set<String>): List<SurpriseAnswer> =
        items.mapNotNull { item ->
            val id = item.jellyfinId ?: item.id
            if (id in exclude) return@mapNotNull null
            when (item.kind) {
                MediaKind.MOVIE -> if (touched(playstate[id])) null else SurpriseAnswer(id, dev.jellystructure.shared.tv.MediaKind.MOVIE)
                MediaKind.TV_SHOW ->
                    if (touched(playstate[id]) || item.episodes.any { ep -> ep.jellyfinId?.let { touched(playstate[it]) } == true }) null
                    else SurpriseAnswer(id, dev.jellystructure.shared.tv.MediaKind.SERIES)
                MediaKind.MUSIC_VIDEO -> null
            }
        }

    private fun touched(s: CardPlayState?): Boolean = s != null && (s.played || s.resumeMs > 0 || s.continueEpisodeId != null)
}
