package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.StreamTicket
import kotlin.concurrent.Volatile

/**
 * Phase 309 / 313 — what the player's own ABR bound needs to know about the stream it plays (set from each ticket
 * before it loads). Read by the Android track selection ([dev.jellystructure.shared.tv.LadderRules]), the Mac's
 * AVPlayer peak and the store's restream stepping (FR-309-8/-9).
 */
object PlayerLadderHints {
    /** The ticket is served by our own encoder (`encoder == "ours"`): every rung is already running, so a climb waits
     *  only the players' stock buffer. */
    @Volatile var oursEncoder: Boolean = false

    /** 309 — the ticket is a ladder (`adaptive`) — the Mac bounds AVPlayer's climb only then. */
    @Volatile var adaptive: Boolean = false

    /** 309 (FR-309-2) — the rung the server started this play on (its `BANDWIDTH`), or null when it named none. */
    @Volatile var startVariantBps: Long? = null

    /** 309 (FR-309-9) — the desktop player's buffer ahead of the playhead (ms), refreshed about once a second; -1 = not
     *  known. Read by [dev.jellystructure.ravilo.ui.screens.PlayerStore]'s restream stepping. */
    @Volatile var bufferedAheadMs: Long = -1L

    /** 309 (FR-309-9) — this player cannot switch variants itself (mpv), so the store steps it by restreams. */
    @Volatile var restreamStepping: Boolean = false

    /**
     * 309 (FR-309-8) — a restream the store started on its own (a direct play that stalled, a stepping restream): when
     * its ticket loads, R181's track resolver runs again, as it does after the viewer's own restreams. Consumed by the
     * player screen's bookkeeping.
     */
    @Volatile var rearmTracks: Boolean = false

    /** The ticket a play is about to load (every Ready the store emits). */
    fun noteTicket(t: StreamTicket) {
        adaptive = t.adaptive
        startVariantBps = t.startVariantBps
    }
}
