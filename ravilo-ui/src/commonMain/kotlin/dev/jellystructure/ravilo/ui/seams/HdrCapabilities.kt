package dev.jellystructure.ravilo.ui.seams

/** Real display/decoder HDR support — reported to the server (via `ClientCapabilities`) so it knows
 *  whether it's safe to direct-play HDR10/HLG content or whether Jellyfin should tone-map-transcode
 *  it to SDR first (see the bug this fixes: an HDR10+ file played very dark on Ravilo but fine in
 *  Jellyfin's own client, because jellystructure's DeviceProfile never declared a VideoRangeType
 *  constraint and so never gave Jellyfin a reason to tone-map). */
data class HdrSupport(
    val hdr10: Boolean,
    val hlg: Boolean,
    /** R183 — a real Dolby Vision decoder (needed for DV profile 5, which has no HDR10 base layer). */
    val dolbyVision: Boolean = false,
    /** R183 — dual-layer DV (profile 7's enhancement layer): a DV decoder *plus* multi-instance HEVC. */
    val dolbyVisionEl: Boolean = false,
) {
    companion object {
        /** Conservative default (assume SDR-only) for platforms with no reliable HDR-passthrough
         *  detection — forces a correctly tone-mapped transcode rather than risking a dark picture. */
        val NONE = HdrSupport(hdr10 = false, hlg = false)
    }
}

/**
 * R183 — the client's real H.264 decode ceiling, reported to the server so Jellyfin declares a
 * transcode target the player can actually decode. With nothing declared Jellyfin advertised its HLS
 * variant as Baseline level 4.1 while still targeting the source's native 4K, and ExoPlayer dropped
 * that variant outright — playback failed before the first frame ("Contact Week", R183).
 *
 * [maxLevel] is the level ×10 (`51` = level 5.1), matching what Jellyfin's `VideoLevel` condition wants.
 */
data class AvcDecoderLimits(val maxWidth: Int, val maxHeight: Int, val maxLevel: Int) {
    companion object {
        /** Nothing detected — the server falls back to a universally-decodable 1080p High/L5.1 target. */
        val UNKNOWN = AvcDecoderLimits(maxWidth = 0, maxHeight = 0, maxLevel = 0)
    }
}

/** Platform-specific: Android queries the real `MediaCodec` decoder capabilities; other targets report
 *  [HdrSupport.NONE]. */
expect fun detectHdrSupport(): HdrSupport

/** Platform-specific: Android queries the real `MediaCodec` H.264 decoder limits; other targets report
 *  [AvcDecoderLimits.UNKNOWN]. */
expect fun detectAvcDecoderLimits(): AvcDecoderLimits
