package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastRoute
import dev.jellystructure.ravilo.ui.seams.platformAirPlay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * R360 (FR-R360-1, dev review item 5) — the Cast routes the sheet lists in this mode: displays only in video mode,
 * every route (speakers and groups too) in music mode (R324 FR-R324-1). One function, read by the sheet and by the
 * glyph's rule, so the two can never disagree.
 */
fun visibleCastRoutes(routes: List<CastRoute>, music: Boolean): List<CastRoute> =
    if (music) routes else routes.filter { it.kind == "display" }

/**
 * R360 (FR-R360-1, dev review item 6) — "the list is not empty": a paired screen (online or offline — the server's
 * `RaviloConfig.screens.paired`, or the open sheet's newer fetch), a Cast route the sheet would list in this mode, or
 * the AirPlay row. Explanatory lines (*speakers need Android*, the Mac's Local Network line) are not devices.
 *
 * R368 (amends FR-R360-1) — [sessionRows]: a row in *Playing everywhere* counts as part of the list too.
 */
fun hasCastDevices(
    screensPaired: Boolean, routes: List<CastRoute>, music: Boolean, airplayAvailable: Boolean, sessionRows: Int = 0,
): Boolean = screensPaired || visibleCastRoutes(routes, music).isNotEmpty() || airplayAvailable || sessionRows > 0

/** R360 (FR-R360-3) — the grace before the glyph hides once the list empties. */
const val CAST_ICON_GRACE_MS = 10_000L

/**
 * R360 (FR-R360-2/-3, dev review item 8) — the glyph's presence over time: shown the instant the list gains a device
 * or a cast is on ([casting] — connecting, reconnecting or connected, so *Stop casting* stays reachable), hidden only
 * after [graceMs] of an empty list. A device back inside the grace cancels the hide. One direction only: show at once,
 * hide late. The first value is emitted after the grace when the list starts empty, so a collector starts from
 * `false` (absent) and never flashes the glyph.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun castIconPresence(hasDevices: Flow<Boolean>, casting: Flow<Boolean>, graceMs: Long = CAST_ICON_GRACE_MS): Flow<Boolean> =
    combine(hasDevices, casting) { d, c -> d || c }
        .distinctUntilChanged()
        .transformLatest { present -> if (present) emit(true) else { delay(graceMs); emit(false) } }
        .distinctUntilChanged()

/**
 * R360 (FR-R360-6, dev review item 6) — the one gate every cast glyph calls: the phone's app bar, the wide bar, the
 * player's `castSlot`, Now playing, the desktop toolbar and the desktop music bar. Null [cast] (no capability on this
 * server — `castActive`) means no discovery and no glyph, unless *Playing everywhere* has a row (R368).
 */
@Composable
fun rememberCastIconShown(cast: CastController?, music: Boolean): Boolean {
    if (cast == null) return false
    val airplay = platformAirPlay
    val airplayAvailableFlow = remember(airplay) { airplay?.available ?: MutableStateFlow(false) }
    val airplayWirelessFlow = remember(airplay) { airplay?.wireless ?: MutableStateFlow(false) }
    val musicNow by rememberUpdatedState(music)
    val flow = remember(cast, airplayAvailableFlow, airplayWirelessFlow) {
        val devices = combine(
            snapshotFlow { cast.screensPaired to musicNow }, cast.routes, airplayAvailableFlow, cast.sessionRowCount,
        ) { (paired, m), routes, air, rows -> hasCastDevices(paired, routes, m, air, rows) }
        val casting = combine(cast.sender.link, airplayWirelessFlow, dev.jellystructure.ravilo.ui.music.MusicCast.linked) { link, wireless, musicLinked ->
            link != CastLinkState.NONE || wireless || musicLinked
        }
        castIconPresence(devices, casting)
    }
    val shown by flow.collectAsState(initial = false)
    return shown
}
