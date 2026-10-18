package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * R376 (FR-R376-4b) — the browser tab's full screen, as the player chrome sees it. Snapshot state, not a call, so the
 * chrome follows the browser's own Esc and `PlayerScreen` (at R258's register limit) takes no new locals for it.
 * [PlayerImmersiveEffect]'s web actual fills it while the player is up and clears it on the way out; everywhere else
 * [available] stays false and the control is absent, never greyed.
 */
object PlayerFullscreen {
    /** A browser tab whose page can go full screen (not the installed app, not iPhone Safari). */
    var available by mutableStateOf(false)
    /** The page is full screen right now. */
    var active by mutableStateOf(false)
}

/** R376 (FR-R376-4b) — enters full screen, or leaves it; called from the viewer's own click or key. No-op off the web. */
expect fun togglePlayerFullscreen()
