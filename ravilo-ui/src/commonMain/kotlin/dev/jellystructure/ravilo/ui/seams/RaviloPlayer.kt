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

    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
    val isPlaying: Boolean
    val isEnded: Boolean

    /** Audio track list, discovered from the stream after load. May be empty until media is ready. */
    val audioTracks: List<PlayerAudioTrack>

    /** Subtitle track list, discovered from the stream after load (embedded + sideloaded externals). */
    val subtitleTracks: List<PlayerSubtitleTrack>
}

/**
 * Map a 2- or 3-letter language code to an English display name (R46); null when unknown so callers
 * can fall back to the raw code. Covers the common library languages (incl. da/en/fo).
 */
fun languageName(code: String?): String? =
    code?.lowercase()?.trim()?.let { LANGUAGE_NAMES[it] }

private val LANGUAGE_NAMES: Map<String, String> = mapOf(
    "da" to "Danish", "dan" to "Danish",
    "en" to "English", "eng" to "English",
    "fo" to "Faroese", "fao" to "Faroese",
    "de" to "German", "ger" to "German", "deu" to "German",
    "fr" to "French", "fre" to "French", "fra" to "French",
    "es" to "Spanish", "spa" to "Spanish",
    "it" to "Italian", "ita" to "Italian",
    "sv" to "Swedish", "swe" to "Swedish",
    "no" to "Norwegian", "nor" to "Norwegian", "nb" to "Norwegian", "nob" to "Norwegian",
    "nl" to "Dutch", "dut" to "Dutch", "nld" to "Dutch",
    "fi" to "Finnish", "fin" to "Finnish",
    "is" to "Icelandic", "isl" to "Icelandic", "ice" to "Icelandic",
    "pl" to "Polish", "pol" to "Polish",
    "pt" to "Portuguese", "por" to "Portuguese",
    "ru" to "Russian", "rus" to "Russian",
    "ja" to "Japanese", "jpn" to "Japanese",
    "ko" to "Korean", "kor" to "Korean",
    "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
    "ar" to "Arabic", "ara" to "Arabic",
    "hi" to "Hindi", "hin" to "Hindi",
    "cs" to "Czech", "cze" to "Czech", "ces" to "Czech",
    "tr" to "Turkish", "tur" to "Turkish",
    "und" to "Unknown",
)
