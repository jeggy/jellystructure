package dev.jellystructure.tv

import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.ClientCapabilities

/**
 * DEB-5506 (DEB-5503, FR-46) — `[ravilo] samsung_direct_play = false` negotiates a Samsung's (`platform = tizen`) start, prepare,
 * prewarm and restream as if its capabilities said `hls_only = true`, which is the negotiation every `.wgt` had before FR-43.
 * Every other platform, and a Samsung with the switch on, keeps what it sent. No wire change: it is read per request.
 */
internal fun capabilitiesForPlatform(caps: ClientCapabilities, platform: String?, samsungDirectPlay: Boolean, audioOffDefault: Boolean = false): ClientCapabilities =
    if (platform == "tizen" && (!samsungDirectPlay || audioOffDefault)) caps.copy(hlsOnly = true) else caps

/**
 * DEB-5916 (DEB-5503, FR-44) — true when the audio a negotiation asks for is not the file's default track: Jellyfin's default, else the
 * first (the track a ticket carries, [dev.jellystructure.tv.PlaybackService.downloadCopies]'s `carried`), among the tracks that hold
 * packets. A Samsung's in-file audio switch is unreliable (`setSelectTrack` throws or does not take), so such a start leaves direct
 * play, and the answer is our ladder carrying that audio. No choice, a file with one real audio track, or the default chosen: false.
 * A track jellystructure added as a device's copy ([AudioTrack.copyOf]) counts as the track it copies.
 */
internal fun audioIsOffDefault(tracks: List<AudioTrack>, emptyStreams: Set<Int>, chosenIndex: Int?): Boolean {
    chosenIndex ?: return false
    val real = tracks.filter { it.index !in emptyStreams }
    if (real.size <= 1) return false
    val default = real.firstOrNull { it.isDefault } ?: real.first()
    val chosen = real.firstOrNull { it.index == chosenIndex }?.let { it.copyOf ?: it.index } ?: chosenIndex
    return chosen != default.index
}
