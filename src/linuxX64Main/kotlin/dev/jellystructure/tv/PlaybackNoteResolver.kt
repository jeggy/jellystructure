package dev.jellystructure.tv

import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.PlaybackNote

// Phase 185 (FR-185-6) — "one predicate, two consumers": the SAME 0.9 safety margin Phase 177's own
// forced-transcode VideoBitrate condition already uses (JellyfinClient.kt's BITRATE_SAFETY_MARGIN). If
// that margin is ever retuned, both must move together — a note without a re-encode behind it, or a
// re-encode with no note, is a bug in this phase.
private const val NOTE_MARGIN = 0.9

/**
 * Phase 185 (FR-185-6) — the pure predicate, separated out so it's testable without a live store: does
 * [videoTrack]'s own bitrate exceed [NOTE_MARGIN] of [capabilities]'s ceiling **for that track's own
 * codec**? Exactly Phase 177's own forced-transcode comparison, and nothing else. `false` for a codec
 * with no recorded ceiling (AV1/VP9/etc., or one Phase 185 simply hasn't measured) — never inferred.
 */
internal fun playbackNoteFires(videoTrack: Track?, capabilities: DeviceDecodeCapabilities?): Boolean {
    if (videoTrack == null || videoTrack.kind != TrackKind.VIDEO) return false
    val bitrate = videoTrack.videoBitrate ?: return false
    val ceiling = capabilities?.let {
        when (videoTrack.codec.lowercase()) {
            "hevc", "h265" -> it.hevcMaxBitrate
            "h264", "avc", "avc1" -> it.h264MaxBitrate
            else -> null
        }
    } ?: return false
    return bitrate > ceiling * NOTE_MARGIN
}

/**
 * Phase 185 (FR-185-5/FR-185-6/FR-185-7) — the whole resolved verdict for one (device, file), or null
 * when there's nothing to say. Deterministic and cheap: no network call, no TMDB, nothing that can flap
 * between two requests for the same file except a genuinely new [PlaybackStartSampleStore] sample
 * landing (FR-185-7 — re-derived only on session completion).
 *
 * [videoTrack] must be the FILE's own video track (`kind == VIDEO`) — pass null when the item has none
 * probed yet, which correctly resolves to no note rather than a false one.
 */
internal fun resolvePlaybackNote(
    videoTrack: Track?,
    capabilities: DeviceDecodeCapabilities?,
    deviceId: String,
    deviceDisplayName: String,
    fileId: String,
    startSampleStore: PlaybackStartSampleStore,
): PlaybackNote? {
    if (!playbackNoteFires(videoTrack, capabilities)) return null
    return when (val basis = startSampleStore.basisFor(deviceId, fileId)) {
        StartHistoryBasis.Expected -> PlaybackNote(device = deviceDisplayName, basis = "expected")
        is StartHistoryBasis.Measured -> PlaybackNote(device = deviceDisplayName, basis = "measured", seconds = basis.seconds)
    }
}
