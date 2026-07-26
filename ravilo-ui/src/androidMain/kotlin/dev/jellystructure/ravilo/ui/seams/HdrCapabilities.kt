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
 * R183 adds Dolby Vision (R173 deliberately left it out). Only the two signals Jellyfin's own client
 * derives are reported — a plain DV decoder and dual-layer (enhancement-layer) DV — because that is all
 * the server needs: DV **profile 8** rides an HDR10/HDR10+/HLG/SDR base layer and so is unlocked by the
 * plain HDR flags above, while profile 5 (`DOVI`) and profile 7 (`DOVIWithEL`) genuinely need a DV
 * decoder. Mirrors `HevcCodecCapabilities.supportsHevcDolbyVision{,EL}()` in Jellyfin's Android TV
 * client, including its multi-instance-HEVC requirement for the dual-layer case.
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

    // R183 — Dolby Vision. `video/dolby-vision` decoders appear on API 24+; the dual-layer (profile 7)
    // case additionally needs the dvhe.07 profile AND an HEVC decoder that can run two instances at once
    // (base + enhancement layer), exactly the pair of checks Jellyfin's Android TV client makes.
    val dolbyVision = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
        decoders.any { info -> info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true) } }
    val multiInstanceHevc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        decoders.any { info ->
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC) }.getOrNull()
            (caps?.maxSupportedInstances ?: 0) >= 2
        }
    val dolbyVisionEl = dolbyVision && multiInstanceHevc && hasDecoder(
        MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION,
        CodecProfileLevel.DolbyVisionProfileDvheDtb,
        CodecProfileLevel.DolbyVisionLevelHd24,
    )

    return HdrSupport(
        hdr10 = hevcHdr10 || hevcHdr10Plus || av1Hdr10,
        hlg = hevcMain10,
        dolbyVision = dolbyVision,
        dolbyVisionEl = dolbyVisionEl,
    )
}

/**
 * R183 — the real H.264 decode ceiling of this device, reported so Jellyfin can declare a transcode
 * target the player will accept (see [AvcDecoderLimits]). Takes the widest-area AVC decoder's own
 * `VideoCapabilities` bounds and the highest level any of its High/Main/Baseline profile entries
 * advertises; nothing detected → [AvcDecoderLimits.UNKNOWN] and the server picks a safe 1080p target.
 */
actual fun detectAvcDecoderLimits(): AvcDecoderLimits {
    val avc = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        .filter { !it.isEncoder }
        .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() }
    if (avc.isEmpty()) return AvcDecoderLimits.UNKNOWN

    val widest = avc.mapNotNull { it.videoCapabilities }
        .maxByOrNull { it.supportedWidths.upper.toLong() * it.supportedHeights.upper.toLong() }
    val level = avc.flatMap { it.profileLevels.orEmpty().toList() }
        .mapNotNull { AVC_LEVELS[it.level] }
        .maxOrNull()
    return AvcDecoderLimits(
        maxWidth = widest?.supportedWidths?.upper ?: 0,
        maxHeight = widest?.supportedHeights?.upper ?: 0,
        maxLevel = level ?: 0,
    )
}

/** `CodecProfileLevel.AVCLevel*` → the level ×10 Jellyfin's `VideoLevel` condition expects (`51` = 5.1).
 *  Written out as a map because the AOSP constants are an unordered bit-flag set, not an ordinal scale
 *  (`AVCLevel4 = 0x800` sorts below `AVCLevel31 = 0x200`'s successor, so `max()` over the raw ints lies). */
private val AVC_LEVELS: Map<Int, Int> = mapOf(
    CodecProfileLevel.AVCLevel1 to 10,
    CodecProfileLevel.AVCLevel1b to 10,
    CodecProfileLevel.AVCLevel11 to 11,
    CodecProfileLevel.AVCLevel12 to 12,
    CodecProfileLevel.AVCLevel13 to 13,
    CodecProfileLevel.AVCLevel2 to 20,
    CodecProfileLevel.AVCLevel21 to 21,
    CodecProfileLevel.AVCLevel22 to 22,
    CodecProfileLevel.AVCLevel3 to 30,
    CodecProfileLevel.AVCLevel31 to 31,
    CodecProfileLevel.AVCLevel32 to 32,
    CodecProfileLevel.AVCLevel4 to 40,
    CodecProfileLevel.AVCLevel41 to 41,
    CodecProfileLevel.AVCLevel42 to 42,
    CodecProfileLevel.AVCLevel5 to 50,
    CodecProfileLevel.AVCLevel51 to 51,
    CodecProfileLevel.AVCLevel52 to 52,
    CodecProfileLevel.AVCLevel6 to 60,
    CodecProfileLevel.AVCLevel61 to 61,
    CodecProfileLevel.AVCLevel62 to 62,
)
