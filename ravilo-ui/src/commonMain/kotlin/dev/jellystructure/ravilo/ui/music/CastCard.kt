package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus

/**
 * R356 (FR-R356-1/4) — what Ravilo's media card (the notification, the lock screen, the media keys) shows while the
 * phone is the remote for a cast. One per song or film: [key] changes with every song, so the platform reloads the
 * card's artwork for each one and never shows an image loaded for an earlier song on a later one.
 */
data class CastCard(
    /** The song's or film's id — the card's media item. */
    val key: String,
    val title: String,
    /** The credited artists (a song) or the kicker (a film). */
    val subtitle: String?,
    val album: String?,
    /** The cover as the app has it (a song: its album's cover, the Playing page's own); made absolute by the platform. */
    val artUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val music: Boolean,
    val hasNext: Boolean,
    /** The device's volume, 0.0–1.0; null when it does not say. */
    val volume: Double?,
)

/**
 * R356 — the card for a live cast, or null when there is none (not connected, nothing loaded, played out, failed).
 *
 * A song is built from [music] — the very state the Playing page draws ([MusicPlayback.state] while [MusicCast.linked]),
 * so the card's cover and the page's are the same image: the current song's album cover (`MusicTrackItem.imageUrl`).
 * The lock-screen card used to be the Cast SDK's own, which loaded the receiver's image by itself, per notification,
 * and could put an earlier song's cover beside a later song's title. A film is built from the receiver's report.
 */
fun castCard(link: CastLinkState, st: CastRemoteStatus?, volume: Double?, music: MusicPlayerState?): CastCard? {
    if (link != CastLinkState.CONNECTED || st == null || !st.loaded || st.ended || st.failed) return null
    if (st.music) {
        val m = music ?: return null
        val t = m.current ?: return null
        return CastCard(
            key = t.id, title = t.title, subtitle = t.artists.joinToString(", ") { it.name }.ifBlank { null }, album = t.album,
            artUrl = t.imageUrl, positionMs = st.positionMs, durationMs = st.durationMs.takeIf { it > 0 } ?: (t.durationMs ?: 0L),
            playing = st.playing, buffering = st.buffering, music = true, hasNext = m.hasNext, volume = volume,
        )
    }
    val title = st.title ?: return null
    return CastCard(
        key = st.itemId ?: title, title = title, subtitle = st.kicker, album = null, artUrl = st.artUrl,
        positionMs = st.positionMs, durationMs = st.durationMs, playing = st.playing, buffering = st.buffering,
        music = false, hasNext = st.hasNext, volume = volume,
    )
}
