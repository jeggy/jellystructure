package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.SubTrack

data class PlayerAudioTrack(
    val index: Int,
    val label: String,
    val language: String?,
    /** R180 — channel count, for the picker's Surround 5.1 / Stereo badge. Null when unknown. */
    val channels: Int? = null,
    /** R180 — the source's own "pick this automatically" track, for the Default badge. */
    val isDefault: Boolean = false,
)
data class PlayerSubtitleTrack(
    val index: Int,
    val label: String,
    val language: String?,
    val forced: Boolean = false,
    val isDefault: Boolean = false,
    /** R56 — "external" (sideloaded VTT), "embed" (native in-container), "encode" (burn-in transcode). */
    val deliveryMethod: String = "external",
    /** R56 — Jellyfin stream index; only meaningful when deliveryMethod == "encode". */
    val jellyfinStreamIndex: Int = -1,
)

/**
 * Platform-specific video player seam.
 *
 * Android actual: ExoPlayer/Media3 (stub for R14; full forked jellyfin-androidtv engine in R14 final).
 * Web actual: browser-native <video> + hls.js.
 *
 * Control plane (start/stop/progress) stays in PlayerStore/TvApiClient; only the byte stream is here.
 */
expect class RaviloPlayer() {
    /**
     * Load a stream URL starting at [startPositionMs]. Subtitle tracks may be added as external
     * tracks. [audio] carries server-derived per-track metadata (R46) so the picker can show the
     * Jellyfin DisplayTitle (e.g. "Synstolkning") rather than a bare code.
     *
     * R192 — [title]/[subtitle]/[artworkUrl] feed the OS-level media session's metadata (e.g. what a
     * TV's household member sees on their phone's system Cast/media card): [title] is the movie or
     * episode title, [subtitle] the existing "S1 · E3"-style kicker text (already computed by
     * callers for on-screen chrome — reused as-is, since Media3 has no separate numeric
     * season/episode fields), and [artworkUrl] a poster/still image URL. All three are cosmetic —
     * never required for playback to work.
     */
    fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>, audio: List<AudioTrack>, title: String, subtitle: String? = null, artworkUrl: String? = null)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** Select an audio track by its index in [audioTracks]. */
    fun selectAudioTrack(index: Int)

    /** Select a subtitle track by its index in [subtitleTracks], or -1 to disable subtitles. */
    fun selectSubtitleTrack(index: Int)

    fun release()

    /**
     * R192 — toggle the OS-level media session's visibility (e.g. Android's `MediaSession.isActive`)
     * without releasing the underlying player/session objects. Android backgrounding (TV sleep,
     * remote/HDMI-CEC power-off) should deactivate the session so it stops being advertised to other
     * devices signed into the same account, while still allowing an in-app foreground return to
     * resume without a full player rebuild. No-op on platforms with no such OS concept (web).
     */
    fun setSessionActive(active: Boolean)

    /**
     * R157 (FR-R157-1.3, the documented fallback) — on web, the Compose canvas has no accessible
     * alpha/transparency toggle in this Compose Multiplatform version's `CanvasBasedWindow` API
     * (verified: no such parameter exists), so the video can't simply show through a transparent
     * scene as originally hoped. Instead the `<video>` element swaps z-order with the canvas: on top
     * (with `pointer-events: none`, so clicks still reach the canvas beneath) while chrome is hidden
     * so the picture is visible; back behind the canvas when chrome is shown so Compose's opaque
     * chrome paints over it and the canvas receives pointer events for the controls. No-op on
     * Android, where the video surface is already in-scene via a normal (non-Z-order-on-top) SurfaceView.
     */
    fun setChromeVisible(visible: Boolean)

    /** R244 (FR-R244-10) — the handset picker sheet's Subtitle size row (S · M · L ⇒ 0.85 / 1.0 / 1.25),
     *  applied live to the caption renderer. Phone-local: nothing is sent to the server, nothing is
     *  remembered across titles. The TV never calls this. */
    fun setSubtitleScale(scale: Float)

    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
    val isPlaying: Boolean
    val isEnded: Boolean

    /** R218 (FR-R218-1) — false from [load] until the first frame of THIS item actually renders; resets
     *  on every [load] call (unlike [qoeSnapshot]'s counters, which deliberately persist across a
     *  binge's episode-to-episode player reuse). Distinguishes moment B (cold start) from moment C
     *  (mid-playback stall) — the wait before this is B, any wait after it is C or D. */
    val hasRenderedFirstFrame: Boolean

    /** R218 (FR-R218-1) — true while the player is buffering for any reason. Combine with
     *  [hasRenderedFirstFrame] and [isSeeking] to pick a presentation; never render straight off this
     *  alone (a seek's buffering must show moment D, not C). */
    val isBuffering: Boolean

    /** R218 (FR-R218-1) — true from a seek's `DISCONTINUITY_REASON_SEEK` until playback is ready again,
     *  so a seek's own buffering reads as moment D ("the scrub tile carries a spinner") rather than
     *  moment C ("the chrome comes up on its own") — "a seek is an answer to the viewer's own input, not
     *  an interruption of it." Reuses the same signal [qoeSnapshot]'s rebuffer counting already consumes
     *  for the analogous suppression, per this phase's "do not add a second listener" instruction — see
     *  the Android actual's single `qoeListener`. */
    val isSeeking: Boolean

    /** Audio track list, discovered from the stream after load. May be empty until media is ready. */
    val audioTracks: List<PlayerAudioTrack>

    /** Subtitle track list, discovered from the stream after load (embedded + sideloaded externals). */
    val subtitleTracks: List<PlayerSubtitleTrack>

    /** R216 (FR-R216-4) — this session's accumulated playback-quality counters so far. Cheap/synchronous
     *  (a snapshot of counters the player already maintains, not a fresh measurement) — callable from
     *  PlayerStore's existing 10s heartbeat tick and at session end with no extra cost. Every field
     *  defaults to "nothing observed" on a platform/state with no real signal (see [PlayerQoeSnapshot]). */
    fun qoeSnapshot(): PlayerQoeSnapshot
}

/**
 * R216 (FR-R216-4) — one player's accumulated playback-quality counters for the current session, read by
 * [RaviloPlayer.qoeSnapshot] and posted via `TvApiClient.postPlaybackQoe`. Nothing here is ever surfaced
 * in the Ravilo UI (the product principle: viewers never see bitrates/buffers/quality) — this exists
 * purely so `POST /api/tv/playback/qoe` (Phase 177 §FR-177-5) has real evidence instead of yet another
 * after-the-fact forensic reconstruction from router/Jellyfin logs.
 */
data class PlayerQoeSnapshot(
    val droppedFrames: Int = 0,
    val rebufferCount: Int = 0,
    val rebufferMs: Long = 0,
    /** The player's own live bandwidth estimate, when the platform exposes one. Null = unknown. */
    val bandwidthEstimateBps: Long? = null,
    /** The selected video decoder's name, when the platform exposes one (diagnostic only). */
    val videoDecoder: String? = null,
    /** Phase 179 (FR-179-3) — count of sideloaded text-subtitle load errors this session. Always 0 on a
     *  platform with no equivalent signal (honest "nothing observed", matching this class's own rule). */
    val subtitleLoadErrors: Int = 0,
    /** Phase R220 (FR-R220-6) — count of times this session's video-output-loss recovery ladder fired
     *  (any rung). Android-only signal; always 0 elsewhere (honest "nothing observed", same rule as
     *  every other field here). A nonzero count on a title is itself the useful signal — if this stays
     *  at 0 across the fleet, the recovery ladder was never needed and phase-R220's rungs 1-3 are dead
     *  weight; if it's never 0, rung 4 (or prevention, FR-R220-4) needs another look. */
    val videoOutputRecoveries: Int = 0,
)

// R247 — `languageName()` and its table live in LanguageIdentity.kt (one table, keyed by canonical code).
