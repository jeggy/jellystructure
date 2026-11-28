package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.MediaCard

/** DEB-5841 (FR-3) — most titles the after-film page offers. */
internal const val POST_FILM_PICKS_MAX = 6

/**
 * DEB-5841 (FR-3) — what the after-film page offers: the finished film's related titles that are not watched,
 * in the server's order, at most [POST_FILM_PICKS_MAX], never the film just played. Empty means no page.
 */
internal fun postFilmPicks(related: List<MediaCard>, playedId: String): List<MediaCard> =
    related.filter { !it.watched && it.id != playedId }.take(POST_FILM_PICKS_MAX)
