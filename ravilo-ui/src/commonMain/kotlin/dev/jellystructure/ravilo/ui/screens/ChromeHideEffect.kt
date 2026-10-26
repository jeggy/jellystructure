package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * R350 (FR-R350-7, amended 2026-10-25, DEB-2495) — the player's chrome hide follows the film, not the button. The timer
 * restarts on a wake (`chromeRevision`) and on every change of the film's play state, so a paused-to-playing edge re-arms
 * it whatever started it: the TV's own remote, a session command, a media key, or the phone's notification card by the
 * cast road, none of which bumps the revision. A pause restarts it to no effect, because [timer] holds while paused.
 */
@Composable
internal fun ChromeHideEffect(chromeRevision: Long, isPlaying: Boolean, timer: suspend () -> Unit) {
    LaunchedEffect(chromeRevision, isPlaying) { timer() }
}
