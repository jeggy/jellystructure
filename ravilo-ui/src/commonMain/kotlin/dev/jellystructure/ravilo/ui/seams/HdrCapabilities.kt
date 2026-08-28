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
 *
 * R216 generalises this from an H.264-only probe (renamed from `AvcDecoderLimits`/
 * `detectAvcDecoderLimits()`) to also report the device's real video-decode **bitrate** ceiling — the
 * investigation behind stue-tv-4k-playback-stutter-2026-08-28.md found the TV's decoders declare
 * `bitrate-range = "1-60000000"` (60 Mbps) on both the Dolby Vision and plain-HEVC paths, an API this
 * probe already calls (`VideoCapabilities`) but never read. `0` = unknown for any bitrate field, same
 * "never guess" convention as [maxWidth]/[maxHeight]/[maxLevel].
 */
data class DecoderLimits(
    val maxWidth: Int,
    val maxHeight: Int,
    val maxLevel: Int,
    /** The highest video decode bitrate advertised across the codecs this device would actually be
     *  offered (bits/s) — an overall ceiling for Phase 177's `MaxStreamingBitrate` cap. */
    val maxVideoBitrate: Int = 0,
    /** HEVC's own decode-bitrate ceiling (also the effective ceiling for a Dolby Vision profile-8 file,
     *  since Jellyfin still keys its per-codec `VideoBitrate` condition on Codec="hevc" for those). */
    val maxHevcBitrate: Int = 0,
    /** H.264's own decode-bitrate ceiling. */
    val maxH264Bitrate: Int = 0,
) {
    companion object {
        /** Nothing detected — the server falls back to a universally-decodable 1080p High/L5.1 target
         *  and emits no `VideoBitrate` condition (Phase 177 §FR-177-2 — never invent a ceiling). */
        val UNKNOWN = DecoderLimits(maxWidth = 0, maxHeight = 0, maxLevel = 0)
    }
}

/** R216 — this device's own view of its network link, sampled once at `startPlayback` (see
 *  `PlayerStore`). [kind] is `"ethernet"` / `"wifi"` / `"unknown"`; [mbps] is the real link rate where
 *  available (on Wi-Fi, the PHY `linkSpeed` — the signal that distinguishes a 2.4 GHz association from
 *  a 5 GHz one, e.g. 130 vs 585 Mbps, which is exactly what caught the 2026-08-27 stue TV incident). */
data class LinkState(val kind: String, val mbps: Int) {
    companion object {
        val UNKNOWN = LinkState(kind = "unknown", mbps = 0)
    }
}

/** Platform-specific: Android queries the real `MediaCodec` decoder capabilities; other targets report
 *  [HdrSupport.NONE]. */
expect fun detectHdrSupport(): HdrSupport

/** Platform-specific: Android queries the real `MediaCodec` decoder limits (H.264 dimensions/level +
 *  R216's decode-bitrate ceilings); other targets report [DecoderLimits.UNKNOWN]. */
expect fun detectDecoderLimits(): DecoderLimits

/** R216 — platform-specific network link probe. Android reads it from `ConnectivityManager`/`WifiInfo`
 *  (no new runtime permission — see the androidMain doc); other targets report [LinkState.UNKNOWN]. */
expect fun detectLinkState(): LinkState
