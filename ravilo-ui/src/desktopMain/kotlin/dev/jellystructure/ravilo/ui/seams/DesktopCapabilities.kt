package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.desktop.MacNative
import java.net.NetworkInterface

/*
 * R329 (FR-R329-3) — what this Mac really decodes, asked of AVFoundation (`isPlayableExtendedMIMEType`) through the
 * Swift library: the Mac tells the server the truth (the R284/R302 rule). Without the library (Linux) nothing plays,
 * and the answers are the conservative ones.
 */

private fun playable(mime: String): Boolean = MacNative.lib?.ravilo_caps_playable(mime) == 1

private val hevc: Boolean by lazy { playable("video/mp4; codecs=\"hvc1.1.6.L150.90\"") }

/** AAC and MP3 everywhere AVFoundation is; AC-3 and E-AC-3 where the probe says yes; never DTS or TrueHD. */
actual fun supportedAudioCodecs(): List<String> = audioCodecs

private val audioCodecs: List<String> by lazy {
    if (MacNative.lib == null) listOf("aac", "mp3")
    else listOfNotNull(
        "aac", "mp3",
        "ac3".takeIf { playable("audio/mp4; codecs=\"ac-3\"") },
        "eac3".takeIf { playable("audio/mp4; codecs=\"ec-3\"") },
    )
}

actual fun supportsHevcOverHls(): Boolean = hevc

actual fun supportedVideoCodecs(): List<String> = if (hevc) listOf("h264", "hevc") else listOf("h264")

/** AVPlayer plays no MKV: every film arrives as HLS (the Chromecast receiver's negotiation, unchanged). */
actual fun playsOnlyHls(): Boolean = MacNative.lib != null

actual fun playsHlsForAirPlay(): Boolean = false

/** FR-R329-4 — AVPlayer switches the composed master's audio renditions in place (R291). */
actual fun switchesHlsAudioRenditions(): Boolean = MacNative.lib != null

actual fun supportsEmbeddedTextSubtitles(): Boolean = false
actual fun warmAudioRendition(index: Int) {}

/** AirPlay from the Mac is out of scope (R329 §Out of scope). */
actual val platformAirPlay: AirPlay? = null

/**
 * No HDR is claimed. FR-R329-1's frame path hands Skia 8-bit BGRA, so an HDR10, HLG or Dolby Vision stream would
 * reach the screen as whatever AVFoundation's 8-bit conversion makes of it; the server's tone-mapped SDR is the
 * honest answer until the spike shows that conversion is right. `-Dravilo.hdr=true` claims what the probes allow,
 * for that measurement.
 */
actual fun detectHdrSupport(): HdrSupport =
    if (System.getProperty("ravilo.hdr") == "true" && hevc) HdrSupport(
        hdr10 = true,
        hlg = true,
        dolbyVision = playable("video/mp4; codecs=\"dvh1.08.06\""),
    ) else HdrSupport.NONE

/** Phase 185's honest *not measured yet*: the desktop never guesses a decoder ceiling (R329 dev review 10). */
actual fun detectDecoderLimits(): DecoderLimits = DecoderLimits.UNKNOWN

/**
 * FR-R328-3 — wired or Wi-Fi, from the interfaces that are up and carry an address. macOS names Wi-Fi `en0` on a
 * MacBook, so the name alone cannot tell; an interface whose display name says Wi-Fi/wireless/AirPort is Wi-Fi, any
 * other `en*`/`eth*` is wired. No speed is ever claimed (0 = unknown, the "never guess" rule).
 */
actual fun detectLinkState(): LinkState = runCatching {
    val up = NetworkInterface.getNetworkInterfaces().toList().filter { nif ->
        nif.isUp && !nif.isLoopback && !nif.isVirtual && nif.inetAddresses.toList().any { !it.isLinkLocalAddress && !it.isLoopbackAddress }
    }
    fun isWifi(nif: NetworkInterface): Boolean {
        val label = "${nif.name} ${nif.displayName}".lowercase()
        return listOf("wi-fi", "wifi", "wlan", "wireless", "airport", "wlp").any { it in label }
    }
    when {
        up.any(::isWifi) && up.none { !isWifi(it) && (it.name.startsWith("en") || it.name.startsWith("eth")) } -> LinkState("wifi", 0)
        up.any { it.name.startsWith("eth") || (it.name.startsWith("en") && !isWifi(it)) } -> LinkState("ethernet", 0)
        else -> LinkState.UNKNOWN
    }
}.getOrDefault(LinkState.UNKNOWN)
