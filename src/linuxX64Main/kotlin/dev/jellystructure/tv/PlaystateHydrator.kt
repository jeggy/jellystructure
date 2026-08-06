package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.MediaCard
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val HYDRATE_TICKS_PER_MS = 10_000L
private const val HYDRATE_CHUNK = 100

// R142: bound the UserData read fan-out (Kotlin/Native CIO select() crashes on FD ≥ 1024).
private val hydrateGate = Semaphore(4)

/**
 * R142 — fetch Jellyfin played/in-progress state for [ids] so poster tiles can show a ✓ / progress sliver.
 * Mirrors DetailService.getPlaystate but lives here so the feed / browse / search / related services can
 * hydrate their cards without depending on DetailService. Path-based ids (no Jellyfin id at scan time) are
 * skipped. Returns an empty map on any failure — callers treat absent entries as "unwatched".
 */
internal suspend fun fetchPlaystate(
    jellyfinClient: JellyfinClient,
    base: String,
    token: String,
    userId: String,
    ids: List<String>,
): Map<String, CardPlayState> = coroutineScope {
    val realIds = ids.filterNot { it.startsWith('/') }.distinct()
    if (realIds.isEmpty()) return@coroutineScope emptyMap()
    // Bug fix: chunks used to be fetched one at a time in a plain for-loop, so N chunks cost N sequential
    // Jellyfin round trips even though hydrateGate has room for 4 concurrent holders — the semaphore's
    // capacity was going unused by this, its own primary caller. Fan the chunks out concurrently (still
    // gated by the same semaphore, so overall fan-out across every fetchPlaystate caller stays capped).
    val out = mutableMapOf<String, CardPlayState>()
    realIds.chunked(HYDRATE_CHUNK).map { chunk ->
        async {
            hydrateGate.withPermit { jellyfinClient.getUserDataBulk(base, token, userId, chunk) }
        }
    }.awaitAll().forEach { results ->
        results.forEach { jf ->
            val ud = jf.userData ?: return@forEach
            out[jf.id] = CardPlayState(
                resumeMs  = ud.playbackPositionTicks / HYDRATE_TICKS_PER_MS,
                // A series whose Jellyfin child rollup is empty (RecursiveItemCount == 0) reports
                // Played=true vacuously (0 unplayed of 0) — even when episodes exist but the series
                // aggregation is stale (verified: Pluribus). Don't paint a false ✓ on the tile.
                played    = ud.played && !(jf.type == "Series" && jf.recursiveItemCount == 0),
                playedPct = (ud.playedPercentage?.toFloat() ?: 0f) / 100f,
                favorite  = ud.isFavorite,
            )
        }
    }
    out
}

/**
 * R142 — overlay played / in-progress state onto a card. A card that already carries [progressPct] (the
 * episode-level Continue Watching progress) keeps it; otherwise an in-progress (0 < pct < 100, unplayed)
 * item gets the resume sliver. `watched` is set from the authoritative played flag.
 */
internal fun MediaCard.withPlaystate(ps: Map<String, CardPlayState>): MediaCard {
    val s = ps[id] ?: return this
    return copy(
        watched = s.played,
        progressPct = progressPct ?: s.playedPct.takeIf { it > 0.001f && it < 0.999f && !s.played },
    )
}
