package dev.jellystructure.tv

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
    /** `S1E1`, `S1E1–3`, or null when the season or episode is unknown. */
    val code: String?
        get() = if (season == null || episode == null) null
        else "S${season}E$episode" + (episodeEnd?.let { "–$it" } ?: "")
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
