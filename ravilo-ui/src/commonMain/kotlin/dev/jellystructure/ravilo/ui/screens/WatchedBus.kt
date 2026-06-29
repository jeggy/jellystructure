package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.MediaCard
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * R147 — instant cross-screen watched-state propagation. When the viewer marks a movie / episode / season
 * played on a detail screen, the detail store broadcasts the **server-returned** [CardPlayState] map here;
 * the retained Home / Browse / Search stores collect it and patch their already-rendered tiles in place
 * (a fixed-size ✓ badge appears — no re-fetch, no flicker, no Back-and-wait).
 *
 * The values are server-authoritative (the `setPlayed` response straight from Jellyfin user-data), so this
 * is a propagation channel, not a client-invented store — it just decides *when* the visible tiles re-read,
 * the same way R49's `acquisition_changed` patches request-status pills in place.
 */
object WatchedBus {
    val patches = MutableSharedFlow<Map<String, CardPlayState>>(replay = 0, extraBufferCapacity = 16)
    fun publish(patch: Map<String, CardPlayState>) { if (patch.isNotEmpty()) patches.tryEmit(patch) }
}

/** Overlay a watched-state [patch] onto this card (keyed by id); cards not in the patch are returned as-is. */
fun MediaCard.applyWatchedPatch(patch: Map<String, CardPlayState>): MediaCard {
    val s = patch[id] ?: return this
    return copy(
        watched = s.played,
        // A played tile shows the ✓, not a sliver; an unplayed one keeps/takes its in-progress sliver.
        progressPct = if (s.played) null else (s.playedPct.takeIf { it > 0.001f && it < 0.999f } ?: progressPct),
    )
}
