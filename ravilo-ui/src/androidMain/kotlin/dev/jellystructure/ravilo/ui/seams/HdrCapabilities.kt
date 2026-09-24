package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import dev.jellystructure.ravilo.ui.RaviloAppContext

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
    // R289 — the N/M guards that used to sit on the three lines below are gone: the floor is API 24.
    val hevcHdr10 = hasDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCMainTierLevel4)
    val hevcHdr10Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        hasDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10HDR10Plus, CodecProfileLevel.HEVCMainTierLevel4)
    // AV1: Jellyfin falls back to raw AOSP hex ints pre-Q for OEM firmware (e.g. Fire OS) that expose
    // AV1 decode below the official API level — skipped here as a known Ravilo TV fleet, not an
    // arbitrary-AOSP-fork client; pre-Q devices are exceedingly unlikely to have AV1 decode at all.
    val av1Hdr10 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        hasDecoder(MediaFormat.MIMETYPE_VIDEO_AV1, CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1Level5)

    // R183 — Dolby Vision. `video/dolby-vision` decoders appear on API 24+; the dual-layer (profile 7)
    // case additionally needs the dvhe.07 profile AND an HEVC decoder that can run two instances at once
    // (base + enhancement layer), exactly the pair of checks Jellyfin's Android TV client makes.
    val dolbyVision = decoders.any { info -> info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true) } }
    val multiInstanceHevc = decoders.any { info ->
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
 * target the player will accept (see [DecoderLimits]). Takes the widest-area AVC decoder's own
 * `VideoCapabilities` bounds and the highest level any of its High/Main/Baseline profile entries
 * advertises; nothing detected → [DecoderLimits.UNKNOWN] and the server picks a safe 1080p target.
 *
 * R216 (renamed from `detectAvcDecoderLimits`) additionally reads each relevant codec's own
 * `VideoCapabilities.getBitrateRange()` — an API this probe already calls for width/height/level, never
 * previously read for bitrate — restricted to **hardware-accelerated decoders only** ([isHardwareDecoder]):
 * a software fallback decoder (`OMX.google.*` / `c2.android.*`) can advertise a much higher, unreal
 * bitrate ceiling that would mask the hardware decoder actually selected for playback, which is exactly
 * the failure this field exists to prevent (see the type's own doc).
 */
actual fun detectDecoderLimits(): DecoderLimits {
    val decoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
    val hwDecoders = decoders.filter { isHardwareDecoder(it) }

    fun maxBitrateFor(mime: String): Int =
        hwDecoders.mapNotNull { runCatching { it.getCapabilitiesForType(mime) }.getOrNull()?.videoCapabilities }
            .mapNotNull { it.bitrateRange?.upper }
            .maxOrNull() ?: 0

    val avc = hwDecoders.mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() }
        .ifEmpty { decoders.mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() } }
    if (avc.isEmpty()) return DecoderLimits.UNKNOWN

    val widest = avc.mapNotNull { it.videoCapabilities }
        .maxByOrNull { it.supportedWidths.upper.toLong() * it.supportedHeights.upper.toLong() }
    val level = avc.flatMap { it.profileLevels.orEmpty().toList() }
        .mapNotNull { AVC_LEVELS[it.level] }
        .maxOrNull()

    val h264Bitrate = maxBitrateFor(MediaFormat.MIMETYPE_VIDEO_AVC)
    val hevcBitrate = maxBitrateFor(MediaFormat.MIMETYPE_VIDEO_HEVC)
    val dvBitrate = maxBitrateFor(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)
    return DecoderLimits(
        maxWidth = widest?.supportedWidths?.upper ?: 0,
        maxHeight = widest?.supportedHeights?.upper ?: 0,
        maxLevel = level ?: 0,
        maxVideoBitrate = maxOf(h264Bitrate, hevcBitrate, dvBitrate),
        maxHevcBitrate = hevcBitrate,
        maxH264Bitrate = h264Bitrate,
    )
}

/** R216 — `isHardwareAccelerated()` is API 29+; below that, fall back to the same name-prefix heuristic
 *  Jellyfin's own Android TV client and AOSP's CTS use to identify a software (`OMX.google.*` /
 *  `c2.android.*`) decoder, since Android has no other pre-Q signal for this. */
private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
    val name = info.name.lowercase()
    return !(name.startsWith("omx.google.") || name.startsWith("c2.android."))
}

/**
 * R216 — this device's own network link, read at `startPlayback` time only (see [LinkState]'s doc).
 * `ConnectivityManager`/`WifiManager` are gated by the normal (install-time, non-runtime) `ACCESS_NETWORK_
 * STATE`/`ACCESS_WIFI_STATE` permissions only — `WifiInfo.getLinkSpeed()`/`getFrequency()` do NOT require
 * `ACCESS_FINE_LOCATION` (unlike `getSSID()`/`getBSSID()`), so this never triggers a runtime permission
 * prompt, matching FR-R216-2's invariant. Falls back to `unknown` on any failure rather than guessing.
 */
actual fun detectLinkState(): LinkState = runCatching {
    val ctx: Context = RaviloAppContext.get()
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return LinkState.UNKNOWN
    val network = cm.activeNetwork ?: return LinkState.UNKNOWN
    val caps = cm.getNetworkCapabilities(network) ?: return LinkState.UNKNOWN
    when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
            val mbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.div(1000) ?: 0
            LinkState(kind = "ethernet", mbps = mbps)
        }
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
            // The real PHY link speed (e.g. 130 vs 585 Mbps) is what distinguishes a 2.4 GHz association
            // from a 5 GHz one — exactly the signal that would have caught 2026-08-27's `f=2462` case.
            // WifiInfo (not the capabilities' own bandwidth estimate) is the authoritative source for it.
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val linkSpeed = wifi?.connectionInfo?.linkSpeed?.takeIf { it > 0 }
            val mbps = linkSpeed ?: (caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.div(1000) ?: 0)
            LinkState(kind = "wifi", mbps = mbps)
        }
        else -> LinkState.UNKNOWN
    }
}.getOrDefault(LinkState.UNKNOWN)

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
