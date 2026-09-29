package dev.jellystructure.ravilo.ui.seams

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R330 — what the sheet and the remote say that depends on the platform's own sender, set by that platform (the
 * Mac) and left alone everywhere else.
 */
object CastPlatform {
    private val _localNetworkDenied = MutableStateFlow(false)

    /** FR-R330-8 — discovery was refused Local Network access: the sheet says so once, under its rows. */
    val localNetworkDenied: StateFlow<Boolean> = _localNetworkDenied.asStateFlow()

    /** Opens the system's own place to allow it; null where there is none (then the line offers no button). */
    var openLocalNetworkSettings: (() -> Unit)? = null

    /** R330 open question 2 — *Play on this Mac* on the Mac; *Play on this phone* everywhere else. */
    var playHereKey: String = "cast.play_here"

    fun reportLocalNetworkDenied(denied: Boolean) { _localNetworkDenied.value = denied }
}
