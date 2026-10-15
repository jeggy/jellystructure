package dev.jellystructure.tv

import dev.jellystructure.model.MediaItem
import dev.jellystructure.shared.tv.TvImdbRating
import dev.jellystructure.shared.tv.validImdbTitleId

/**
 * Phase 131 / R387 (FR-R387-1) — the one place a stored [MediaItem.imdbRating] becomes the wire
 * [TvImdbRating], so detail, browse and the focus facts mean the same thing. Catalog-only: never fetched
 * at read time. [TvImdbRating.imdbId] is the title's id when it is `tt` + digits, null otherwise.
 */
internal fun MediaItem.tvImdbRating(): TvImdbRating? =
    imdbRating?.let {
        TvImdbRating(aggregateRating = it.aggregateRating, voteCount = it.voteCount, imdbId = validImdbTitleId(imdbId))
    }
