package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus

/** FR-R245-9's states, plus R299's *Failed*. One of these is what the remote's state line says. */
enum class RemoteState { FAILED, UNREACHABLE, NO_SERVER, BUSY, ENDED, PLAYING, PAUSED }

/**
 * R299 (FR-R299-3) — a receiver that answered "failed" is reachable, so *Failed* is decided before
 * *Unreachable*; *Lost contact* is only ever a lost link. Order below is the order the remote used
 * to check them, with FAILED added in front.
 */
fun remoteState(s: CastRemoteStatus, unreachable: Boolean): RemoteState = when {
    s.failed && !unreachable -> RemoteState.FAILED
    unreachable -> RemoteState.UNREACHABLE
    s.noServer -> RemoteState.NO_SERVER
    s.busyRetryAfter != null -> RemoteState.BUSY
    s.ended -> RemoteState.ENDED
    s.playing -> RemoteState.PLAYING
    else -> RemoteState.PAUSED
}

/** The transport (play/pause, skips, seek) is live only while there is something it can act on. */
fun remoteTransportEnabled(s: CastRemoteStatus, unreachable: Boolean): Boolean =
    !unreachable && s.loaded && !s.ended && !s.failed && s.busyRetryAfter == null && !s.noServer

/** The mini bar shows while a cast has something to show: never after an end or a failure. */
fun castMiniBarVisible(link: CastLinkState, s: CastRemoteStatus?): Boolean =
    link == CastLinkState.CONNECTED && s != null && s.loaded && !s.ended && !s.failed

/**
 * R299 — the phone's `failed` flag after a receiver message: set by "failed", cleared by the next
 * load's "status"/"tracks", otherwise kept (a "nextup"/"busy" in between says nothing about it).
 */
fun failedAfter(event: String?, previous: Boolean): Boolean = when (event) {
    "failed" -> true
    "status", "tracks" -> false
    else -> previous
}
