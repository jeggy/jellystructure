package dev.jellystructure.shared.tv

/**
 * DEB-4832 — the episode a series' tile plays when the viewer presses OK on a TV that has no series page yet (the
 * Samsung app's stand-in, until DEB-4660). A series id is not playable, so the TV has to name an episode first.
 *
 * [season] is the episode's season index (0 = Specials). [lastPart] is the episode number of the last part of a
 * multi-episode file that shares [episode]'s id, so the kicker can read `S01E01–E03`. [next] is the episode after it,
 * in order, with its own season; null on a newest-first series, after a series' last episode, and after a special.
 */
data class SeriesPlayPick(val episode: Episode, val season: Int, val lastPart: Int, val next: Episode?, val nextSeason: Int)

/** An episode that is only a catalog row (a path id, or a `#` suffix) never plays. Mirrors ravilo-ui's `isCatalogOnly`. */
private fun Episode.playable() = !id.startsWith("/") && !id.contains('#')

/**
 * Which episode OK plays, in the order the series' own page uses with nothing known about the viewer but what the
 * server names: a newest-first series' newest counted episode; the episode the series' Continue Watching entry names
 * ([continueEpisodeId], from `GET /api/tv/playstate`); the first counted episode. *Counted* is season 1 or later with a
 * Jellyfin item. A series with only Specials plays its first playable one. Null = nothing in it can play.
 */
fun pickSeriesPlay(detail: SeriesDetail, continueEpisodeId: String?): SeriesPlayPick? {
    val counted = detail.seasons.filter { it.index >= 1 }.sortedBy { it.index }
        .flatMap { s -> s.episodes.filter { it.playable() }.sortedBy { it.episodeNumber }.map { s.index to it } }
    val all = detail.seasons.flatMap { s -> s.episodes.filter { it.playable() }.map { s.index to it } }
    val chosen = when {
        detail.newestFirst -> counted.lastOrNull()?.let { last -> counted.first { it.second.id == last.second.id } }
        else -> continueEpisodeId?.let { id -> all.firstOrNull { it.second.id == id } }
            ?: counted.firstOrNull()
            ?: all.firstOrNull()
    } ?: return null
    val (season, episode) = chosen
    val last = counted.lastOrNull { it.second.id == episode.id }
    val next = if (detail.newestFirst || last == null) null else counted.drop(counted.indexOf(last) + 1).firstOrNull()
    return SeriesPlayPick(
        episode = episode, season = season,
        lastPart = last?.second?.episodeNumber ?: episode.episodeNumber,
        next = next?.second, nextSeason = next?.first ?: 0,
    )
}

/** The `S01E02` line the player shows for [pick], a file of several episodes as `S01E01–E03`. */
fun SeriesPlayPick.kicker(): String = episodeCode(season, episode.episodeNumber, lastPart)
