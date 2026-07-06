package dev.jellystructure.ravilo.ui.seams

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Decoder-capability-based HDR detection, ported from Jellyfin's own Android TV client
 * (`org.jellyfin.androidtv.util.profile.codec.{HevcCodecCapabilities,Av1CodecCapabilities,
 * MediaCodecQuery}` — verified against the live repo, not memory) — this is deliberately **not**
 * `Display.HdrCapabilities` (a prior version of this file queried the display panel instead, which
 * answers a different question: a panel can show HDR light levels fine while the SoC's decoder still
 * can't parse a given HDR-profile bitstream at all, so decoder support is the correct gate for "can I
 * direct-play this file." Jellyfin's own DeviceProfile negotiation uses the decoder signal exclusively).
 *
 * HLG has no distinct `CodecProfileLevel` constant on Android — Jellyfin treats plain Main10 decode
 * support as sufficient for it (same 10-bit profile, just a different transfer function), so
 * [HdrSupport.hlg] mirrors that: it's just HEVC Main10 decode capability, not a separate HDR query.
 *
 * Dolby Vision is intentionally not covered (see phase-R173's non-goals — no DOVI pipeline exists here;
 * Jellyfin's own DoVi handling is also meaningfully more involved: base/enhancement-layer variants,
 * multi-instance decode requirements, per-model defect blocklists).
 */
actual fun detectHdrSupport(): HdrSupport {
    val decoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
    fun hasDecoder(mime: String, profile: Int, level: Int): Boolean =
        decoders.any { info ->
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
            caps?.profileLevels?.any { it.profile == profile && it.level >= level } ?: false
        }

    val hevcMain10 = hasDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCMainTierLevel4)
    val hevcHdr10 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
        hasDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCMainTierLevel4)
    val hevcHdr10Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        hasDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10HDR10Plus, CodecProfileLevel.HEVCMainTierLevel4)
    // AV1: Jellyfin falls back to raw AOSP hex ints pre-Q for OEM firmware (e.g. Fire OS) that expose
    // AV1 decode below the official API level — skipped here as a known Ravilo TV fleet, not an
    // arbitrary-AOSP-fork client; minSdk 21 devices are exceedingly unlikely to have AV1 decode at all.
    val av1Hdr10 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        hasDecoder(MediaFormat.MIMETYPE_VIDEO_AV1, CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1Level5)

    return HdrSupport(hdr10 = hevcHdr10 || hevcHdr10Plus || av1Hdr10, hlg = hevcMain10)
}
