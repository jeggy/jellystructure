package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.MediaCard
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
): Map<String, CardPlayState> {
    val realIds = ids.filterNot { it.startsWith('/') }.distinct()
    if (realIds.isEmpty()) return emptyMap()
    val out = mutableMapOf<String, CardPlayState>()
    for (chunk in realIds.chunked(HYDRATE_CHUNK)) {
        hydrateGate.withPermit {
            jellyfinClient.getUserDataBulk(base, token, userId, chunk).forEach { jf ->
                val ud = jf.userData ?: return@forEach
                out[jf.id] = CardPlayState(
                    resumeMs  = ud.playbackPositionTicks / HYDRATE_TICKS_PER_MS,
                    played    = ud.played,
                    playedPct = (ud.playedPercentage?.toFloat() ?: 0f) / 100f,
                )
            }
        }
    }
    return out
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
