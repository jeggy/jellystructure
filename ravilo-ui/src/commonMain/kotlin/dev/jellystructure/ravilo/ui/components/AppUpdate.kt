package dev.jellystructure.ravilo.ui.components

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Commands from outside the composition: the Mac's menu bar, the desktop's keys (R337 FR-R337-10). `OPEN_SETTINGS` is
 * what the operating system asked from its own menu — *Settings…* (⌘,), R328 FR-R328-5.
 */
enum class AppCommand {
    OPEN_SETTINGS,
    /** R386 (FR-R386-9) — *Check for Updates…* in the Ravilo menu: opens Settings ▸ About and runs *Check now* there. */
    UPDATE_CHECK,
    // R337 — the desktop's View menu and keys
    MODE_VIDEO, MODE_MUSIC, SEARCH, TOGGLE_SIDEBAR, TOGGLE_QUEUE, SHOW_LYRICS, SHORTCUTS,
    NEXT_SONG, PREVIOUS_SONG, VOLUME_UP, VOLUME_DOWN, SIGN_OUT,
    /**
     * DEB-4133 (FR-R337-10, amendment 2026-11-09) — the desktop's Space: plays or pauses whatever has focus. A film's
     * player on screen takes it as the film's; otherwise it is what the bar's ⏯ does; with no bar it is nothing.
     */
    PLAY_PAUSE,
}

object AppCommands {
    private val _requests = MutableSharedFlow<AppCommand>(extraBufferCapacity = 4)
    val requests: SharedFlow<AppCommand> = _requests.asSharedFlow()
    fun send(command: AppCommand) { _requests.tryEmit(command) }
}
