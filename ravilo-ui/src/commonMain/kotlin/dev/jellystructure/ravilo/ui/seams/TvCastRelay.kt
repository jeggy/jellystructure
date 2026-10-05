package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastSeenDevice
import kotlinx.coroutines.flow.StateFlow

/**
 * R378 — an Android TV as a relay (amends R370 owner decision 1). Google's Cast SDK finds nothing on a TV: Play services
 * registers no media route provider there (`dumpsys media_router` on the BRAVIA: `<no providers>`), because to Google a
 * TV is a receiver. So a TV finds the household's Cast devices itself and launches the receiver on one with our own
 * Cast v2 sender (`:ravilo-castv2`), as the Mac does. Null everywhere but an Android TV.
 *
 * Nothing of it is ever drawn: a TV never casts in its interface (R360 amendment) — no glyph, no sheet, no toast.
 */
interface TvCastRelay {
    /** FR-R378-2 — the Cast devices this TV sees (never its own receiver, never a group), as the reach report carries them. */
    val seen: StateFlow<List<CastSeenDevice>>

    /** FR-R378-1 — browse while [on] (the app on screen, R293) and the server has a receiver [appId]; stop otherwise. */
    fun discover(appId: String?, on: Boolean)

    /**
     * FR-R378-3/-4 — launch the receiver on [castDeviceId] and LOAD [load], then leave it playing. False (nothing sent)
     * when the device is not seen, there is no [appId], or another relay is still in flight.
     */
    fun relay(castDeviceId: String, appId: String?, load: CastLoadData): Boolean
}

/** R378 — the TV's relay; null on a phone, a computer and the web (they relay through their Cast sender, R370). */
expect fun platformTvCastRelay(): TvCastRelay?
