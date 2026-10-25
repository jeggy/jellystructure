package dev.jellystructure.ravilo.ui.seams

/**
 * DEB-2456 (DEB-2252, FR-R330-7b) — where the Mac's cast bar takes a song's place from.
 *
 * After the receiver loads the next song itself, CAF's media channel can go quiet for it (DEB-2314: 0–1 `MEDIA_STATUS`, then
 * none), while our own channel keeps delivering. So for the song our channel names, the place and the play state come from
 * whichever report is newer by arrival: the receiver's last status that carries `position_ms`, or CAF's last `MEDIA_STATUS`
 * for that song. Pure, so the rule is tested without a socket.
 */
data class CastPlaceReport(
    /** The song's place when the receiver built the status, in ms. */
    val positionMs: Long,
    /** True while the song plays; false while paused or buffering. */
    val playing: Boolean,
    /** When it arrived (the caller's wall clock, the same as `nowMs`). */
    val atMs: Long,
)

enum class CastPlaceSource(val log: String) { CAF("caf"), RAVILO("ravilo") }

/** The place the receiver's report gives now, moved on while it plays. [buffering]: the song is not playing yet. */
data class CastPlace(val positionMs: Long, val playing: Boolean, val buffering: Boolean)

/** A song that has not played for this long, and is not playing, is still starting; after it a not-playing report is a pause. */
const val CAST_PLACE_STARTED_MS = 1_000L

/**
 * The receiver's place for the song, or null when CAF's report stands: no receiver report (an older receiver, or none for
 * this song yet), or CAF's [cafAtMs] (null: CAF has said nothing for this song) is the newer by arrival. A tie goes to the
 * receiver. The place is moved on by the time since the report arrived while it is playing, as `CastMediaStatus.positionMs()`
 * does for CAF's, and held at [durationMs] when that is known.
 */
fun castPlaceNow(receiver: CastPlaceReport?, cafAtMs: Long?, durationMs: Long, nowMs: Long): CastPlace? {
    receiver ?: return null
    if (cafAtMs != null && cafAtMs > receiver.atMs) return null
    val moved = if (receiver.playing) (nowMs - receiver.atMs).coerceAtLeast(0L) else 0L
    val pos = (receiver.positionMs + moved).coerceAtLeast(0L).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
    return CastPlace(pos, receiver.playing, buffering = !receiver.playing && receiver.positionMs < CAST_PLACE_STARTED_MS)
}

/** CAF's snapshot with the receiver's [place] laid over it: its place and play state, nothing else. */
fun CastMediaSnapshot.withPlace(place: CastPlace): CastMediaSnapshot = copy(
    playerState = if (place.playing) "PLAYING" else if (place.buffering) "BUFFERING" else "PAUSED",
    idleFinished = false,
    positionMs = place.positionMs,
)
