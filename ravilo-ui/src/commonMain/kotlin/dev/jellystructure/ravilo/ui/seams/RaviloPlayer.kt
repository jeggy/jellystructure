package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.SubTrack

data class PlayerAudioTrack(val index: Int, val label: String, val language: String?)
data class PlayerSubtitleTrack(
    val index: Int,
    val label: String,
    val language: String?,
    val forced: Boolean = false,
    val isDefault: Boolean = false,
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
     */
    fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>, audio: List<AudioTrack>)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** Select an audio track by its index in [audioTracks]. */
    fun selectAudioTrack(index: Int)

    /** Select a subtitle track by its index in [subtitleTracks], or -1 to disable subtitles. */
    fun selectSubtitleTrack(index: Int)

    fun release()

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
