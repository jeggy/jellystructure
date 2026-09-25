package dev.jellystructure.ravilo.ui.seams

import kotlinx.coroutines.flow.StateFlow

/**
 * R265 (FR-R265-4/-8) — AirPlay, where the browser has it (Safari). The web player's `<video>` is the
 * only thing that can know: WebKit reports a reachable AirPlay target *to a media element*, and moves
 * that element's picture to it. Everything else — the sheet's footnote row, the glyph's connected form,
 * R270's one-time notice — reads it from here. Null on every other platform: never on Android, never on
 * a TV (FR-R265-4).
 *
 * There is no remote and no mini bar for AirPlay: the phone's own player IS the remote, driving the
 * `<video>` whose picture the TV is showing. WebKit does not say which TV that is, so nothing here names
 * one; R270's bar says the whole sentence instead (see [dev.jellystructure.ravilo.ui.components.AirPlayNoticeBar]).
 */
interface AirPlay {
    /** WebKit said an AirPlay target is reachable from the player's `<video>`. False with no player open. */
    val available: StateFlow<Boolean>
    /** The picture is on an AirPlay target right now. */
    val wireless: StateFlow<Boolean>
    /** Apple's own picker. Must run inside the tap that asked for it (WebKit's user-gesture rule). */
    fun showPicker()
}

expect val platformAirPlay: AirPlay?
