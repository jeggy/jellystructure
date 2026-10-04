package dev.jellystructure.tv

import dev.jellystructure.media.NextEpisode
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem

/**
 * R309 (FR-R309-7) — which episodes one played Jellyfin item holds. A multi-episode file
 * (`S01E01E02E03.mkv`, Phase 149) is ONE Jellyfin item, so a Continue Watching tile or a Next Up label
 * naming it as `S1E1` contradicts the series page it opens, which shows *Episodes 1–3*.
 *
 * [episodeEnd] is the highest contained episode number, and only present when the file holds more
 * than one episode; [episode] stays the lowest.
 */
internal data class EpisodeSpan(val season: Int?, val episode: Int?, val episodeEnd: Int? = null) {
    /** `S01E01`, `S01E01–E03` (R346 FR-R346-5, the one spelling), or null when the season or episode is unknown. */
    val code: String?
        get() = if (season == null || episode == null) null
        else dev.jellystructure.shared.tv.episodeCode(season, episode, episodeEnd)
}

/**
 * R199 — Jellyfin's IndexNumber/ParentIndexNumber parse can fail on scene-release filenames even when
 * jellystructure's own scanner already resolved the episode via Phase 152's filename fallback. Each
 * field falls back independently to the matching scanned episode (by jellyfinId), so a badge isn't
 * suppressed just because Jellyfin's own metadata is incomplete for that one file.
 *
 * R309 — the range end comes from the same scanned episodes: every entry carrying the played item's id
 * in the SAME file (R309's one id, one file — a duplicate copy elsewhere never widens the range). One
 * helper for the resume tile, the Next Up label and R199's fallback, so they cannot disagree.
 */
internal fun resolvedEpisodeSpan(mediaItem: MediaItem, jellyfinItemId: String, season: Int?, episode: Int?): EpisodeSpan {
    val local = mediaItem.episodes.firstOrNull { it.jellyfinId == jellyfinItemId }
    val s = season ?: local?.seasonNumber
    val e = episode ?: local?.episodeNumber
    val end = local?.let { first ->
        mediaItem.episodes
            .filter { it.jellyfinId == jellyfinItemId && it.path == first.path }
            .mapNotNull { it.episodeNumber }
            .maxOrNull()
    }
    return EpisodeSpan(s, e, end?.takeIf { e != null && it > e })
}

/**
 * R264 (FR-R264-3), a top-level pure function since R375 (FR-R375-4) — the episode after [ep] in [series]: season
 * then episode order, specials (season 0) never, only one Jellyfin can play, and never [ep]'s own file again (a
 * multi-episode file, phase 149, is several catalog rows on one id). Null after the last — and for a special, which
 * has no "next". The cast / Ravilo-screen next-up and Continue Watching's next-in-order card use this one order.
 */
internal fun nextEpisodeAfter(series: MediaItem, ep: Episode): NextEpisode? {
    if ((ep.seasonNumber ?: 0) < 1) return null
    val ordered = countedEpisodesInOrder(series)
    val at = ordered.indexOfFirst { it.jellyfinId == ep.jellyfinId }
    if (at < 0) return null
    val next = ordered.drop(at + 1).firstOrNull { it.jellyfinId != ep.jellyfinId } ?: return null
    // R346 — S01E04, or S01E04–E06 for a multi-episode file: the span helper the Continue cards use.
    val kicker = resolvedEpisodeSpan(series, next.jellyfinId!!, next.seasonNumber, next.episodeNumber).code
    return NextEpisode(jellyfinId = next.jellyfinId!!, title = next.title?.takeIf { it.isNotBlank() }, kicker = kicker)
}

/** The series' playable, counted episodes (R346: season 1 or later, with a Jellyfin item) in season, episode, part
 *  order — the order [nextEpisodeAfter] walks and R375's anchor breaks a tie by. */
internal fun countedEpisodesInOrder(series: MediaItem): List<Episode> =
    series.episodes
        .filter { it.jellyfinId != null && (it.seasonNumber ?: 0) >= 1 }
        .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }, { it.partIndex }))
