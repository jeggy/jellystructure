package dev.jellystructure.ravilo.ui.seams

import kotlin.concurrent.Volatile

/**
 * Phase 309 / 313 — what the player's own ABR bound needs to know about the stream it plays (set from each ticket
 * before it loads). Read by the Android track selection ([dev.jellystructure.shared.tv.LadderRules]).
 */
object PlayerLadderHints {
    /** The ticket is served by our own encoder (`encoder == "ours"`): every rung is already running, so a climb waits
     *  only the players' stock buffer. */
    @Volatile var oursEncoder: Boolean = false
}
